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
                                                         indentWithTab]]
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
                          (if (and range (.-from range) (.-to range))
                            (.set Decoration
                              #js [(.range ai-focus-mark (.-from range) (.-to range))])
                            (.-none Decoration)))
           :update (fn [^js update]
                     (this-as this
                       (set! (.-decorations this)
                             (let [range (.field (.-state (.-view update)) ai-focus-field)]
                               (if (and range (.-from range) (.-to range))
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

(defn get-form-at-cursor
  "Get the S-expression (form) containing the cursor.
   Returns {:from :to :text} or nil if not in a form."
  ([] (get-form-at-cursor @editor-instance))
  ([view]
   (when view
     (let [state (.-state view)
           pos (.. state -selection -main -head)
           doc-text (.toString (.-doc state))
           find-form (fn []
                       (loop [i (dec pos)
                              depth 0]
                         (when (>= i 0)
                           (let [ch (.charAt doc-text i)]
                             (cond
                               (= ch \)) (recur (dec i) (inc depth))
                               (= ch \() (if (zero? depth)
                                           ;; Found start, now find end
                                           (loop [j pos
                                                  d 1]
                                             (when (< j (count doc-text))
                                               (let [c (.charAt doc-text j)]
                                                 (cond
                                                   (= c \() (recur (inc j) (inc d))
                                                   (= c \)) (if (= d 1)
                                                              {:from i :to (inc j)
                                                               :text (subs doc-text i (inc j))}
                                                              (recur (inc j) (dec d)))
                                                   :else (recur (inc j) d)))))
                                           (recur (dec i) (dec depth)))
                               :else (recur (dec i) depth))))))]
       (find-form)))))

(defn get-previous-form
  "Get the form immediately before the cursor."
  ([] (get-previous-form @editor-instance))
  ([view]
   (when view
     (let [pos (.. view -state -selection -main -head)]
       ;; Cerca da pos-1 indietro
       (loop [p (dec pos)]
         (when (>= p 0)
           (let [ch (.sliceDoc (.-state view) p (inc p))]
             (if (= ch ")")
               ;; Trovata chiusura, cerca la form che finisce qui
               (let [end (inc p)]
                 ;; Vai indietro a trovare l'apertura
                 (loop [i (dec p) depth 1]
                   (when (>= i 0)
                     (let [c (.sliceDoc (.-state view) i (inc i))]
                       (cond
                         (= c ")") (recur (dec i) (inc depth))
                         (= c "(") (if (= depth 1)
                                     {:from i :to end
                                      :text (.sliceDoc (.-state view) i end)}
                                     (recur (dec i) (dec depth)))
                         :else (recur (dec i) depth))))))
               (recur (dec p))))))))))

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
  "Update AI focus to highlight the form at cursor."
  ([] (update-ai-focus! @editor-instance))
  ([view]
   (when view
     (let [form (get-form-at-cursor view)]
       (set-ai-focus! view form)))))

(defn clear-ai-focus!
  "Clear the AI focus highlight."
  ([] (clear-ai-focus! @editor-instance))
  ([view]
   (set-ai-focus! view nil)))

(defn parse-form-elements
  "Parse a form string into its elements, respecting nested parentheses.
   Returns vector of element strings.
   Example: '(register cubo (box 30))' → ['register' 'cubo' '(box 30)']"
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
