(ns ridley.editor.codemirror
  "CodeMirror 6 integration for Clojure editing with paredit support."
  (:require ["@codemirror/view" :as view :refer [EditorView keymap
                                                  highlightActiveLine
                                                  highlightActiveLineGutter
                                                  drawSelection
                                                  rectangularSelection
                                                  crosshairCursor
                                                  highlightSpecialChars]]
            ["@codemirror/state" :refer [EditorState]]
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
         ;; Selection
         "&.cm-focused .cm-selectionBackground" #js {:backgroundColor "#264f78"}
         ".cm-selectionBackground" #js {:backgroundColor "#3a3d41"}}
    #js {:dark true}))

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

(defn create-editor
  "Create a CodeMirror editor instance.
   Options:
   - parent: DOM element to mount editor
   - initial-value: initial content string
   - on-change: callback when content changes
   - on-run: callback for Cmd+Enter"
  [{:keys [parent initial-value on-change on-run]}]
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
                            ;; Theme
                            (create-theme)
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
                     on-change (conj (create-change-listener on-change)))
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
