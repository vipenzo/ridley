(ns ridley.editor.codemirror
  "CodeMirror 6 integration for Clojure editing with paredit support."
  (:require [clojure.string :as str]
            ["@codemirror/view" :as view :refer [EditorView ViewPlugin Decoration
                                                  keymap
                                                  highlightActiveLine
                                                  highlightActiveLineGutter
                                                  drawSelection
                                                  rectangularSelection
                                                  crosshairCursor
                                                  highlightSpecialChars]]
            ["@codemirror/state" :refer [EditorState StateField StateEffect]]
            ["@codemirror/commands" :as commands :refer [history historyKeymap
                                                         defaultKeymap
                                                         indentWithTab
                                                         undo redo]]
            ["@codemirror/language" :refer [indentOnInput bracketMatching
                                            foldGutter foldKeymap
                                            syntaxHighlighting
                                            HighlightStyle]]
            ["@codemirror/search" :refer [searchKeymap highlightSelectionMatches]]
            ["@codemirror/autocomplete" :refer [closeBrackets closeBracketsKeymap
                                                autocompletion]]
            ["@lezer/highlight" :refer [tags]]
            ["@nextjournal/clojure-mode" :as clojure-mode]))

(defonce ^:private editor-instance (atom nil))

;; ============================================================
;; AI Focus Indicator — highlights current form for AI context
;; ============================================================

;; State effect to update AI focus range
(def set-ai-focus-effect (.define StateEffect))

;; State field to track AI focus range {from, to} or nil
(def ai-focus-field
  (.define StateField
    #js {:create (fn [] nil)
         :update (fn [value tr]
                   (let [effects (.-effects tr)]
                     ;; Check for our effect first
                     (loop [i 0]
                       (if (< i (.-length effects))
                         (let [effect (aget effects i)]
                           (if (.is effect set-ai-focus-effect)
                             (.-value effect)
                             (recur (inc i))))
                         ;; No effect found — map positions through doc changes
                         (when value
                           (let [from (.-from value)
                                 to (.-to value)]
                             (when (and from to)
                               #js {:from (.mapPos (.-changes tr) from)
                                    :to (.mapPos (.-changes tr) to)})))))))}))

;; Mark decoration for AI focus
(def ^:private ai-focus-mark
  (.mark Decoration #js {:class "cm-ai-focus"}))

;; Plugin that provides decorations from the state field
(def ai-focus-plugin
  (.fromClass ViewPlugin
    (fn [^js view]
      #js {:decorations (let [range (.field (.-state view) ai-focus-field)]
                          (if (and range (.-from range) (.-to range)
                                   (< (.-from range) (.-to range)))
                            (.set Decoration
                              #js [(.range ai-focus-mark (.-from range) (.-to range))])
                            (.-none Decoration)))
           :update (fn [^js update]
                     (this-as this
                       (set! (.-decorations this)
                             (let [range (.field (.-state (.-view update)) ai-focus-field)]
                               (if (and range (.-from range) (.-to range)
                                        (< (.-from range) (.-to range)))
                                 (.set Decoration
                                   #js [(.range ai-focus-mark (.-from range) (.-to range))])
                                 (.-none Decoration))))))})
    #js {:decorations (fn [v] (.-decorations v))}))

(defn- create-highlight-style
  "Create a bright syntax highlighting style for Clojure."
  []
  (.define HighlightStyle
    #js [;; Comments - green italic
         #js {:tag (.-lineComment tags) :color "#6a9955" :fontStyle "italic"}
         #js {:tag (.-blockComment tags) :color "#6a9955" :fontStyle "italic"}
         ;; Keywords (:foo) - bright cyan
         #js {:tag (.-atom tags) :color "#4fc1ff"}
         ;; Strings - orange
         #js {:tag (.-string tags) :color "#ce9178"}
         ;; Numbers - light green
         #js {:tag (.-number tags) :color "#b5cea8"}
         ;; def, defn, let, fn, etc. - purple
         #js {:tag (.-keyword tags) :color "#c586c0"}
         ;; Function/var definitions - yellow
         #js {:tag (.definition tags (.-variableName tags)) :color "#dcdcaa"}
         ;; Variable names - light blue
         #js {:tag (.-variableName tags) :color "#9cdcfe"}
         ;; Boolean, nil - blue
         #js {:tag (.-bool tags) :color "#569cd6"}
         #js {:tag (.-null tags) :color "#569cd6"}
         ;; Operators - light gray
         #js {:tag (.-operator tags) :color "#d4d4d4"}
         ;; Punctuation/brackets - white
         #js {:tag (.-punctuation tags) :color "#d4d4d4"}
         #js {:tag (.-bracket tags) :color "#d4d4d4"}
         ;; Special/meta
         #js {:tag (.-meta tags) :color "#d4d4d4"}
         ;; Emphasis (docstrings)
         #js {:tag (.-emphasis tags) :color "#ce9178" :fontStyle "italic"}
         ;; Regexp
         #js {:tag (.-regexp tags) :color "#d16969"}]))

(defn- create-theme
  "Create a custom dark theme with bright, readable colors."
  []
  (.theme EditorView
    #js {"&" #js {:height "100%"
                  :fontSize "13px"
                  :backgroundColor "#1e1e1e"}
         ".cm-scroller" #js {:fontFamily "'SF Mono', 'Monaco', 'Menlo', 'Consolas', monospace"
                             :lineHeight "1.5"
                             :overflow "auto"}
         ".cm-content" #js {:padding "12px 0"
                            :caretColor "#fff"}
         ".cm-line" #js {:padding "0 16px"}
         "&.cm-focused" #js {:outline "none"}
         ".cm-gutters" #js {:backgroundColor "#1e1e1e"
                            :color "#858585"
                            :border "none"
                            :paddingLeft "8px"}
         ".cm-activeLineGutter" #js {:backgroundColor "#252526"
                                     :color "#c6c6c6"}
         ".cm-activeLine" #js {:backgroundColor "#252526"}
         ;; Syntax highlighting - brighter colors
         ".cm-keyword" #js {:color "#c586c0"}           ; purple - def, defn, let, etc.
         ".cm-atom" #js {:color "#4fc1ff"}              ; bright cyan - keywords like :foo
         ".cm-number" #js {:color "#b5cea8"}            ; light green - numbers
         ".cm-string" #js {:color "#ce9178"}            ; orange - strings
         ".cm-comment" #js {:color "#6a9955"            ; green - comments
                            :fontStyle "italic"}
         ".cm-variableName" #js {:color "#9cdcfe"}      ; light blue - variables
         ".cm-definition" #js {:color "#dcdcaa"}        ; yellow - function names
         ".cm-punctuation" #js {:color "#d4d4d4"}       ; light gray - parens
         ".cm-bracket" #js {:color "#ffd700"}           ; gold - brackets
         ".cm-matchingBracket" #js {:backgroundColor "#3a3d41"
                                    :color "#ffd700"
                                    :fontWeight "bold"}
         ".cm-selectionMatch" #js {:backgroundColor "#3a3d41"}
         ".cm-cursor" #js {:borderLeftColor "#fff"
                           :borderLeftWidth "2px"}
         ;; Selection background colors
         "&.cm-focused .cm-selectionBackground" #js {:backgroundColor "#264f78"}
         ".cm-selectionBackground" #js {:backgroundColor "#3a3d41"}
         ;; AI focus indicator — orange highlight around current form
         ".cm-ai-focus" #js {:backgroundColor "rgba(255, 152, 0, 0.12)"
                             :borderBottom "2px solid #ff9800"
                             :borderRadius "2px"}}
    #js {:dark true}))

(defn- create-selection-layer-fix
  "ViewPlugin that applies inline styles to .cm-selectionLayer,
   bypassing CSS specificity issues with CodeMirror's theme system."
  []
  (.define ViewPlugin
    (fn [^js view]
      (let [apply-fix (fn []
                        (when-let [layer (.querySelector (.-dom view) ".cm-selectionLayer")]
                          (let [s (.-style layer)]
                            (set! (.-zIndex s) "2")
                            (set! (.-mixBlendMode s) "screen"))))]
        (apply-fix)
        #js {:update (fn [_update] (apply-fix))}))))

(defn- create-run-keymap
  "Create keymap for Cmd+Enter to run code."
  [on-run]
  (.of keymap
    #js [#js {:key "Mod-Enter"
              :run (fn [_view]
                     (when on-run (on-run))
                     true)}]))

(defn- create-change-listener
  "Create update listener that calls on-change when document changes."
  [on-change]
  (.of (.-updateListener EditorView)
    (fn [^js update]
      (when (and on-change (.-docChanged update))
        (on-change)))))

(defn- create-selection-listener
  "Create update listener that calls on-selection-change when selection changes."
  [on-selection-change]
  (.of (.-updateListener EditorView)
    (fn [^js update]
      (when (and on-selection-change (.-selectionSet update))
        (on-selection-change)))))

(defn create-editor
  "Create a CodeMirror editor instance.
   Options:
   - parent: DOM element to mount editor
   - initial-value: initial content string
   - on-change: callback when content changes
   - on-run: callback for Cmd+Enter
   - on-selection-change: callback when selection/cursor changes"
  [{:keys [parent initial-value on-change on-run on-selection-change]}]
  (let [extensions (cond-> [;; Basic editor features
                            (highlightSpecialChars)
                            (history)
                            (foldGutter)
                            (drawSelection)
                            (.of (.-allowMultipleSelections EditorState) true)
                            (indentOnInput)
                            (syntaxHighlighting (create-highlight-style))
                            (bracketMatching)
                            (closeBrackets)
                            (autocompletion)
                            (rectangularSelection)
                            (crosshairCursor)
                            (highlightActiveLine)
                            (highlightActiveLineGutter)
                            (highlightSelectionMatches)
                            ;; Clojure language support (syntax + paredit)
                            clojure-mode/default_extensions
                            ;; AI focus indicator
                            ai-focus-field
                            ai-focus-plugin
                            ;; Theme
                            (create-theme)
                            ;; Selection layer inline style fix
                            (create-selection-layer-fix)
                            ;; Keymaps (run-keymap first for priority)
                            (create-run-keymap on-run)
                            (.of keymap clojure-mode/paredit_keymap)
                            (.of keymap historyKeymap)
                            (.of keymap closeBracketsKeymap)
                            (.of keymap defaultKeymap)
                            (.of keymap searchKeymap)
                            (.of keymap foldKeymap)
                            (.of keymap #js [indentWithTab])]
                     ;; Add change listener if provided
                     on-change (conj (create-change-listener on-change))
                     ;; Add selection change listener if provided
                     on-selection-change (conj (create-selection-listener on-selection-change)))
        ;; Flatten nested arrays and filter nils
        flat-extensions (-> extensions
                            flatten
                            (->> (remove nil?))
                            to-array)
        state (.create EditorState
                #js {:doc (or initial-value "")
                     :extensions flat-extensions})
        view (EditorView. #js {:state state
                               :parent parent})]
    (reset! editor-instance view)
    view))

(defn get-value
  "Get the current editor content."
  ([]
   (get-value @editor-instance))
  ([view]
   (when view
     (.. view -state -doc (toString)))))

(defn set-value
  "Set the editor content."
  ([value]
   (set-value @editor-instance value))
  ([view value]
   (when view
     (.dispatch view
       #js {:changes #js {:from 0
                          :to (.. view -state -doc -length)
                          :insert (or value "")}}))))

(defn focus
  "Focus the editor."
  ([]
   (focus @editor-instance))
  ([view]
   (when view
     (.focus view))))

(defn destroy
  "Destroy the editor instance."
  ([]
   (destroy @editor-instance))
  ([view]
   (when view
     (.destroy view)
     (when (= view @editor-instance)
       (reset! editor-instance nil)))))

(defn get-editor
  "Get the current editor instance."
  []
  @editor-instance)

(defn get-cursor-position
  "Get current cursor position as {:line :col :pos}."
  ([] (get-cursor-position @editor-instance))
  ([view]
   (when view
     (let [state (.-state view)
           pos (.. state -selection -main -head)
           line (.lineAt (.-doc state) pos)]
       {:line (.-number line)
        :col (- pos (.-from line))
        :pos pos}))))

(defn set-cursor-position
  "Set cursor position. Accepts {:pos n} or {:line l :col c}."
  ([position] (set-cursor-position @editor-instance position))
  ([view {:keys [pos line col]}]
   (when view
     (let [actual-pos (if pos
                        pos
                        (let [state (.-state view)
                              line-obj (.line (.-doc state) line)]
                          (+ (.-from line-obj) col)))]
       (.dispatch view
         #js {:selection #js {:anchor actual-pos :head actual-pos}})))))

(defn insert-at-cursor
  "Insert text at current cursor position."
  ([text] (insert-at-cursor @editor-instance text))
  ([view text]
   (when view
     (let [pos (.. view -state -selection -main -head)]
       (.dispatch view
         #js {:changes #js {:from pos :to pos :insert text}
              :selection #js {:anchor (+ pos (count text))}})))))

(defn insert-at-end
  "Insert text at end of document."
  ([text] (insert-at-end @editor-instance text))
  ([view text]
   (when view
     (let [end (.. view -state -doc -length)]
       (.dispatch view
         #js {:changes #js {:from end :to end :insert text}
              :selection #js {:anchor (+ end (count text))}})))))

(defn get-selection
  "Get current selection as {:from :to :text}."
  ([] (get-selection @editor-instance))
  ([view]
   (when view
     (let [sel (.. view -state -selection -main)
           from (.-from sel)
           to (.-to sel)
           text (.sliceDoc (.-state view) from to)]
       {:from from :to to :text text}))))

(defn replace-range
  "Replace text in range [from, to) with new text."
  ([from to text] (replace-range @editor-instance from to text))
  ([view from to text]
   (when view
     (.dispatch view
       #js {:changes #js {:from from :to to :insert text}
            :selection #js {:anchor (+ from (count text))}}))))

(defn delete-range
  "Delete text in range [from, to)."
  ([from to] (delete-range @editor-instance from to))
  ([view from to]
   (replace-range view from to "")))

(defn get-word-at-cursor
  "Get word under cursor as {:from :to :text}."
  ([] (get-word-at-cursor @editor-instance))
  ([view]
   (when view
     (let [state (.-state view)
           pos (.. state -selection -main -head)
           doc (.-doc state)
           line (.lineAt doc pos)
           line-text (.-text line)
           line-start (.-from line)
           col (- pos line-start)
           ;; Find word boundaries (simple: alphanumeric + hyphen)
           before (subs line-text 0 col)
           after (subs line-text col)
           word-start (- col (count (re-find #"[\w\-]*$" before)))
           word-end (+ col (count (re-find #"^[\w\-]*" after)))
           from (+ line-start word-start)
           to (+ line-start word-end)
           text (.sliceDoc state from to)]
       {:from from :to to :text text}))))

;; ============================================================
;; Structural navigation helpers — bracket-aware
;; ============================================================

(def ^:private open-brackets #{\( \[ \{})
(def ^:private close-brackets #{\) \] \}})
(def ^:private bracket-pairs {\( \) \[ \] \{ \}})
(def ^:private bracket-pairs-rev {\) \( \] \[ \} \{})
(def ^:private whitespace-chars #{\space \newline \tab \return \,})

(defn- find-matching-close
  "From an open bracket at pos, find the matching close. Returns end pos (exclusive) or nil."
  [doc-text pos doc-len]
  (let [open-ch (.charAt doc-text pos)
        close-ch (bracket-pairs open-ch)]
    (loop [j (inc pos) depth 1]
      (when (< j doc-len)
        (let [c (.charAt doc-text j)]
          (cond
            (= c \\) (recur (+ j 2) depth) ;; skip escaped char
            (= c open-ch) (recur (inc j) (inc depth))
            (= c close-ch) (if (= depth 1) (inc j) (recur (inc j) (dec depth)))
            (= c \") (let [end (loop [k (inc j)]
                                 (when (< k doc-len)
                                   (let [sc (.charAt doc-text k)]
                                     (cond
                                       (= sc \\) (recur (+ k 2))
                                       (= sc \") (inc k)
                                       :else (recur (inc k))))))]
                       (if end (recur end depth) nil))
            :else (recur (inc j) depth)))))))

(defn- find-string-end
  "From a \" at pos, find the closing \". Returns end pos (exclusive) or nil."
  [doc-text pos doc-len]
  (loop [j (inc pos)]
    (when (< j doc-len)
      (let [c (.charAt doc-text j)]
        (cond
          (= c \\) (recur (+ j 2))
          (= c \") (inc j)
          :else (recur (inc j)))))))

(defn- find-atom-end
  "From pos on a non-bracket non-whitespace char, find end of atom token."
  [doc-text pos doc-len]
  (loop [j pos]
    (if (>= j doc-len)
      j
      (let [c (.charAt doc-text j)]
        (if (or (whitespace-chars c) (open-brackets c) (close-brackets c) (= c \"))
          j
          (recur (inc j)))))))

(defn- find-atom-start
  "From pos, scan backwards to find start of atom token."
  [doc-text pos]
  (loop [i pos]
    (if (< i 0)
      0
      (let [c (.charAt doc-text i)]
        (if (or (whitespace-chars c) (open-brackets c) (close-brackets c) (= c \"))
          (inc i)
          (recur (dec i)))))))

(defn- read-form-forward
  "Read one form starting at pos. Returns {:from :to :text} or nil.
   Handles () [] {} strings and atoms."
  [doc-text pos doc-len]
  (when (< pos doc-len)
    (let [ch (.charAt doc-text pos)]
      (cond
        ;; Delimited form
        (open-brackets ch)
        (when-let [end (find-matching-close doc-text pos doc-len)]
          {:from pos :to end :text (subs doc-text pos end)})

        ;; String
        (= ch \")
        (when-let [end (find-string-end doc-text pos doc-len)]
          {:from pos :to end :text (subs doc-text pos end)})

        ;; Atom (symbol, keyword, number)
        (not (or (whitespace-chars ch) (close-brackets ch)))
        (let [end (find-atom-end doc-text pos doc-len)]
          (when (> end pos)
            {:from pos :to end :text (subs doc-text pos end)}))

        :else nil))))

(defn- skip-whitespace-forward [doc-text pos doc-len]
  (loop [p pos]
    (if (and (< p doc-len) (whitespace-chars (.charAt doc-text p)))
      (recur (inc p))
      p)))

(defn- skip-whitespace-backward [doc-text pos]
  (loop [p pos]
    (if (and (>= p 0) (whitespace-chars (.charAt doc-text p)))
      (recur (dec p))
      p)))

(defn get-form-at-cursor
  "Get the delimited form () [] {} at or containing the cursor.
   When cursor is directly on an opening bracket, returns that form.
   Otherwise scans backwards for the containing form.
   Returns {:from :to :text} or nil if not in a form."
  ([] (get-form-at-cursor @editor-instance))
  ([view]
   (when view
     (let [state (.-state view)
           pos (.. state -selection -main -head)
           doc-text (.toString (.-doc state))
           doc-len (count doc-text)]
       ;; If cursor is directly on an opening bracket, return that form
       (if (and (< pos doc-len) (open-brackets (.charAt doc-text pos)))
         (when-let [end (find-matching-close doc-text pos doc-len)]
           {:from pos :to end :text (subs doc-text pos end)})
         ;; Scan backwards looking for an unmatched open bracket
         (loop [i (dec pos)
              ;; Stack of close-brackets we need to skip
              stack []]
         (when (>= i 0)
           (let [ch (.charAt doc-text i)]
             (cond
               ;; Skip string backwards
               (and (= ch \") (or (zero? i) (not= (.charAt doc-text (dec i)) \\)))
               (let [str-start (loop [k (dec i)]
                                 (cond
                                   (< k 0) nil
                                   (and (= (.charAt doc-text k) \")
                                        (or (zero? k) (not= (.charAt doc-text (dec k)) \\)))
                                   k
                                   :else (recur (dec k))))]
                 (if str-start
                   (recur (dec str-start) stack)
                   nil))

               ;; Close bracket — push to stack
               (close-brackets ch)
               (recur (dec i) (conj stack ch))

               ;; Open bracket — check if it matches top of stack or is our parent
               (open-brackets ch)
               (let [expected-close (bracket-pairs ch)]
                 (if (and (seq stack) (= (peek stack) expected-close))
                   ;; Matches a close we saw — pop and continue
                   (recur (dec i) (pop stack))
                   ;; Unmatched open bracket — this is our containing form
                   (when-let [end (find-matching-close doc-text i doc-len)]
                     {:from i :to end :text (subs doc-text i end)})))

               :else (recur (dec i) stack))))))))))

(defn- read-form-backward
  "Read the form ending at position p (inclusive). Returns {:from :to :text} or nil."
  [doc-text p]
  (when (>= p 0)
    (let [ch (.charAt doc-text p)]
      (cond
        ;; Close bracket — find matching open
        (close-brackets ch)
        (let [open-ch (bracket-pairs-rev ch)
              end (inc p)]
          (loop [i (dec p) depth 1]
            (when (>= i 0)
              (let [c (.charAt doc-text i)]
                (cond
                  (= c ch) (recur (dec i) (inc depth))
                  (= c open-ch) (if (= depth 1)
                                  {:from i :to end :text (subs doc-text i end)}
                                  (recur (dec i) (dec depth)))
                  ;; Skip strings inside brackets
                  (= c \") (let [str-start (loop [k (dec i)]
                                              (cond
                                                (< k 0) nil
                                                (and (= (.charAt doc-text k) \")
                                                     (or (zero? k) (not= (.charAt doc-text (dec k)) \\)))
                                                k
                                                :else (recur (dec k))))]
                              (if str-start
                                (recur (dec str-start) depth)
                                nil))
                  :else (recur (dec i) depth))))))

        ;; End of string — find opening quote
        (= ch \")
        (let [end (inc p)
              start (loop [k (dec p)]
                      (cond
                        (< k 0) nil
                        (and (= (.charAt doc-text k) \")
                             (or (zero? k) (not= (.charAt doc-text (dec k)) \\)))
                        k
                        :else (recur (dec k))))]
          (when start
            {:from start :to end :text (subs doc-text start end)}))

        ;; Atom — scan backwards for start
        (not (or (whitespace-chars ch) (open-brackets ch)))
        (let [end (inc p)
              start (find-atom-start doc-text p)]
          {:from start :to end :text (subs doc-text start end)})

        :else nil))))

(defn get-previous-form
  "Get the form immediately before the cursor (any type: () [] {} string atom).
   When cursor is on an atom, finds the previous sibling before that atom.
   When cursor is right after (, finds the previous sibling before the (."
  ([] (get-previous-form @editor-instance))
  ([view]
   (when view
     (let [state (.-state view)
           doc-text (.toString (.-doc state))
           doc-len (count doc-text)
           pos (.. state -selection -main -head)
           ch-at-pos (when (< pos doc-len) (.charAt doc-text pos))
           ;; If cursor is on an atom, find its start first
           effective-pos
           (if (and ch-at-pos
                    (not (or (whitespace-chars ch-at-pos) (open-brackets ch-at-pos)
                             (close-brackets ch-at-pos) (= ch-at-pos \"))))
             (find-atom-start doc-text pos)
             pos)
           p (skip-whitespace-backward doc-text (dec effective-pos))]
       (when (>= p 0)
         (let [ch (.charAt doc-text p)]
           (if (open-brackets ch)
             ;; We're at the start of a form's children (right after open bracket).
             ;; Look for previous sibling before this entire delimited form.
             (let [p2 (skip-whitespace-backward doc-text (dec p))]
               (when (>= p2 0)
                 (read-form-backward doc-text p2)))
             ;; Normal case — read form ending at p
             (read-form-backward doc-text p))))))))

(defn get-element-at-cursor
  "Get the element at cursor — atom, delimited form, or string.
   Unlike get-form-at-cursor which only returns delimited forms,
   this returns whatever the cursor is sitting on, including atoms.
   Falls back to get-form-at-cursor for whitespace/close-bracket positions."
  ([] (get-element-at-cursor @editor-instance))
  ([view]
   (when view
     (let [state (.-state view)
           doc-text (.toString (.-doc state))
           doc-len (count doc-text)
           pos (.. state -selection -main -head)]
       (when (< pos doc-len)
         (let [ch (.charAt doc-text pos)]
           (cond
             ;; On open bracket — return that delimited form
             (open-brackets ch)
             (when-let [end (find-matching-close doc-text pos doc-len)]
               {:from pos :to end :text (subs doc-text pos end)})

             ;; On string quote — return the string
             (= ch \")
             (when-let [end (find-string-end doc-text pos doc-len)]
               {:from pos :to end :text (subs doc-text pos end)})

             ;; On atom (symbol, keyword, number) — return the atom
             (not (or (whitespace-chars ch) (close-brackets ch)))
             (let [start (find-atom-start doc-text pos)
                   end (find-atom-end doc-text pos doc-len)]
               (when (> end start)
                 {:from start :to end :text (subs doc-text start end)}))

             ;; On whitespace or close bracket — fall back to containing form
             :else
             (get-form-at-cursor view))))))))

(defn get-first-child-form
  "Get the first child element inside the current delimited form.
   For '(sphere (box 10))' returns 'sphere'. Handles all form types and atoms."
  ([] (get-first-child-form @editor-instance))
  ([view]
   (when view
     (when-let [{:keys [from to]} (get-form-at-cursor view)]
       (let [doc-text (.toString (.-doc (.-state view)))
             ;; Start after the opening bracket, skip whitespace
             start (skip-whitespace-forward doc-text (inc from) to)]
         (when (< start to)
           (read-form-forward doc-text start to)))))))

(defn get-next-form
  "Get the next sibling form after the current one.
   Handles () [] {} strings and atoms. Stops at parent's closing bracket."
  ([] (get-next-form @editor-instance))
  ([view]
   (when view
     (let [state (.-state view)
           doc-text (.toString (.-doc state))
           doc-len (count doc-text)
           pos (.. state -selection -main -head)
           ch (when (< pos doc-len) (.charAt doc-text pos))
           ;; Find end of current element at cursor position
           end-pos
           (cond
             (nil? ch) pos
             ;; On atom — find atom end
             (not (or (whitespace-chars ch) (open-brackets ch) (close-brackets ch) (= ch \")))
             (find-atom-end doc-text pos doc-len)
             ;; On open bracket — find matching close (whole delimited form)
             (open-brackets ch)
             (or (find-matching-close doc-text pos doc-len) pos)
             ;; On string quote — find string end
             (= ch \")
             (or (find-string-end doc-text pos doc-len) pos)
             ;; On whitespace or close bracket — use containing form's end
             :else
             (if-let [form (get-form-at-cursor view)]
               (:to form)
               pos))
           p (skip-whitespace-forward doc-text end-pos doc-len)]
       ;; Don't cross into parent's closing bracket
       (when (and (< p doc-len) (not (close-brackets (.charAt doc-text p))))
         (read-form-forward doc-text p doc-len))))))

(defn select-range
  "Select text from position `from` to position `to`."
  ([from to] (select-range @editor-instance from to))
  ([view from to]
   (when view
     (.dispatch view
       #js {:selection #js {:anchor from :head to}})
     ;; Return the selection info
     {:from from :to to :text (.sliceDoc (.-state view) from to)})))

(defn move-cursor
  "Move cursor by direction. Returns new position.
   direction: :left :right :up :down :start :end"
  ([direction] (move-cursor @editor-instance direction))
  ([view direction]
   (when view
     (case direction
       :left (do (.dispatch view #js {:selection #js {:anchor (max 0 (dec (.. view -state -selection -main -head)))}})
                 (get-cursor-position view))
       :right (let [max-pos (.. view -state -doc -length)]
                (.dispatch view #js {:selection #js {:anchor (min max-pos (inc (.. view -state -selection -main -head)))}})
                (get-cursor-position view))
       :up (do (commands/cursorLineUp view)
               (get-cursor-position view))
       :down (do (commands/cursorLineDown view)
                 (get-cursor-position view))
       :word-right (do (commands/cursorGroupForward view)
                       (get-cursor-position view))
       :word-left (do (commands/cursorGroupBackward view)
                      (get-cursor-position view))
       :start (do (.dispatch view #js {:selection #js {:anchor 0}})
                  (get-cursor-position view))
       :end (do (.dispatch view #js {:selection #js {:anchor (.. view -state -doc -length)}})
                (get-cursor-position view))
       nil))))

;; ============================================================
;; AI Focus — public API
;; ============================================================

(defn set-ai-focus!
  "Set the AI focus highlight range. Pass nil to clear."
  ([range] (set-ai-focus! @editor-instance range))
  ([view range]
   (when view
     (let [effect (if range
                    (.of set-ai-focus-effect #js {:from (:from range) :to (:to range)})
                    (.of set-ai-focus-effect nil))]
       (.dispatch view #js {:effects #js [effect]})))))

(defn update-ai-focus!
  "Update AI focus to highlight the element at cursor (atom or delimited form)."
  ([] (update-ai-focus! @editor-instance))
  ([view]
   (when view
     (let [element (get-element-at-cursor view)]
       (set-ai-focus! view element)))))

(defn clear-ai-focus!
  "Clear the AI focus highlight."
  ([] (clear-ai-focus! @editor-instance))
  ([view]
   (set-ai-focus! view nil)))

(defn parse-form-elements
  "Parse a form string into its elements, respecting nested parentheses.
   Returns vector of element strings.
   Example: '(register cubo (box 30))' -> ['register' 'cubo' '(box 30)']"
  [form-text]
  (when (and form-text
             (str/starts-with? form-text "(")
             (str/ends-with? form-text ")"))
    (let [inner (subs form-text 1 (dec (count form-text)))
          len (count inner)]
      (loop [i 0
             depth 0
             current ""
             elements []]
        (if (>= i len)
          (if (seq (str/trim current))
            (conj elements (str/trim current))
            elements)
          (let [ch (nth inner i)]
            (cond
              (= ch \()
              (recur (inc i) (inc depth) (str current ch) elements)

              (= ch \))
              (recur (inc i) (dec depth) (str current ch) elements)

              (and (= ch \space) (zero? depth))
              (if (seq (str/trim current))
                (recur (inc i) depth "" (conj elements (str/trim current)))
                (recur (inc i) depth "" elements))

              :else
              (recur (inc i) depth (str current ch) elements))))))))

(defn replace-form-element
  "Replace element at index in a form string.
   Index 0 = function name, 1 = first arg, etc.
   Negative index counts from end (-1 = last element).
   Returns new form string or nil if index out of bounds."
  [form-text element-index new-value]
  (when-let [elements (parse-form-elements form-text)]
    (let [idx (if (neg? element-index)
                (+ (count elements) element-index)
                element-index)]
      (when (and (>= idx 0) (< idx (count elements)))
        (let [new-elements (assoc elements idx new-value)]
          (str "(" (str/join " " new-elements) ")"))))))

(defn insert-form-element
  "Insert a new element at index in a form string.
   Index 1 = before first arg, 2 = before second arg, etc.
   Use -1 to append as last element.
   Returns new form string or nil if index out of bounds."
  [form-text element-index new-value]
  (when-let [elements (parse-form-elements form-text)]
    (let [n (count elements)
          idx (cond
                (= element-index -1) n
                (neg? element-index) (+ n element-index 1)
                :else element-index)]
      (when (and (>= idx 0) (<= idx n))
        (let [new-elements (into (subvec elements 0 idx)
                                 (cons new-value (subvec elements idx)))]
          (str "(" (str/join " " new-elements) ")"))))))

(defn delete-form-element
  "Delete element at index in a form string.
   Index 0 = function name, 1 = first arg, etc.
   Negative index counts from end (-1 = last element).
   Returns new form string or nil if index out of bounds."
  [form-text element-index]
  (when-let [elements (parse-form-elements form-text)]
    (let [idx (if (neg? element-index)
                (+ (count elements) element-index)
                element-index)]
      (when (and (>= idx 0) (< idx (count elements)))
        (let [new-elements (into (subvec elements 0 idx)
                                 (subvec elements (inc idx)))]
          (str "(" (str/join " " new-elements) ")"))))))

(defn barf-form
  "Barf: eject the last element from the current form.
   (foo a b c) -> (foo a b) c"
  ([] (barf-form @editor-instance))
  ([view]
   (when view
     (when-let [{:keys [from to text]} (get-form-at-cursor view)]
       (when-let [elements (parse-form-elements text)]
         (when (> (count elements) 1)
           (let [last-el (peek elements)
                 remaining (pop elements)
                 new-form (str "(" (str/join " " remaining) ")")
                 new-text (str new-form " " last-el)]
             (replace-range view from to new-text))))))))

(defn slurp-form
  "Slurp: absorb the next element after the form into the form.
   (foo a b) c -> (foo a b c)"
  ([] (slurp-form @editor-instance))
  ([view]
   (when view
     (when-let [{:keys [from to text]} (get-form-at-cursor view)]
       (let [doc-text (.toString (.-doc (.-state view)))
             doc-len (count doc-text)]
         ;; Find next token/form after closing paren
         (loop [p to]
           (when (< p doc-len)
             (let [ch (.charAt doc-text p)]
               (cond
                 ;; Skip whitespace
                 (#{\space \newline \tab \return} ch)
                 (recur (inc p))

                 ;; Found opening paren — slurp the whole form
                 (= ch \()
                 (let [end (loop [j (inc p) depth 1]
                             (when (< j doc-len)
                               (let [c (.charAt doc-text j)]
                                 (cond
                                   (= c \() (recur (inc j) (inc depth))
                                   (= c \)) (if (= depth 1) (inc j) (recur (inc j) (dec depth)))
                                   :else (recur (inc j) depth)))))]
                   (when end
                     (let [next-el (subs doc-text p end)
                           elements (parse-form-elements text)
                           new-form (str "(" (str/join " " (conj elements next-el)) ")")]
                       ;; Replace from form start to end of absorbed element
                       (replace-range view from end new-form))))

                 ;; Found closing paren — stop, nothing to slurp
                 (= ch \))
                 nil

                 ;; Found an atom — slurp it
                 :else
                 (let [end (loop [j p]
                             (if (and (< j doc-len)
                                      (not (#{\space \newline \tab \return \) \(} (.charAt doc-text j))))
                               (recur (inc j))
                               j))
                       next-el (subs doc-text p end)
                       elements (parse-form-elements text)
                       new-form (str "(" (str/join " " (conj elements next-el)) ")")]
                   (replace-range view from end new-form)))))))))))

(defn editor-undo!
  "Programmatic undo."
  ([] (editor-undo! @editor-instance))
  ([view]
   (when view
     (undo #js {:state (.-state view) :dispatch #(.dispatch view %)}))))

(defn editor-redo!
  "Programmatic redo."
  ([] (editor-redo! @editor-instance))
  ([view]
   (when view
     (redo #js {:state (.-state view) :dispatch #(.dispatch view %)}))))
