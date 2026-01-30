# Task: Implement AI Action Handlers

## Overview

Connect the AI extension to the actual editor. The skeleton is in place (`ridley.ai.*`), now we need real handlers that manipulate CodeMirror and execute REPL commands.

## Current State

In `core.cljs`, the AI is initialized with placeholder handlers:

```clojure
(ai/init! {:insert (fn [...] (js/console.log "AI insert:" ...))
           :edit (fn [...] (js/console.log "AI edit:" ...))
           :navigate (fn [...] (js/console.log "AI navigate:" ...))
           :execute (fn [...] ...)})
```

The `:execute` handler already works. We need to implement `:insert`, `:edit`, and `:navigate`.

## Files to Modify

### 1. `src/ridley/editor/codemirror.cljs` — Add editing functions

Add these functions for programmatic editing:

```clojure
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
     ;; Use clojure-mode's paredit utilities if available
     ;; Fallback: find matching parens manually
     (let [state (.-state view)
           pos (.. state -selection -main -head)
           doc-text (.toString (.-doc state))
           ;; Simple approach: scan backwards for unmatched '(', forwards for matching ')'
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
```

### 2. `src/ridley/core.cljs` — Implement real handlers

Replace the placeholder handlers with real implementations:

```clojure
;; Add these helper functions before init

(defn- ai-insert-code
  "Insert code into editor or REPL based on target and position."
  [{:keys [target code position]}]
  (case target
    :script
    (when-let [view @editor-view]
      (case position
        :cursor (cm/insert-at-cursor view code)
        :end (cm/insert-at-end view (str "\n" code))
        :after-current-form
        (if-let [form (cm/get-form-at-cursor view)]
          (do
            (cm/set-cursor-position view {:pos (:to form)})
            (cm/insert-at-cursor view (str "\n" code)))
          ;; No form found, insert at cursor
          (cm/insert-at-cursor view code))
        ;; Default: at cursor
        (cm/insert-at-cursor view code))
      ;; Auto-save
      (save-to-storage)
      (send-script-debounced))
    
    :repl
    (when-let [input-el @repl-input-el]
      (case position
        :cursor (let [start (.-selectionStart input-el)
                      value (.-value input-el)]
                  (set! (.-value input-el)
                        (str (subs value 0 start) code (subs value start))))
        :end (set! (.-value input-el)
                   (str (.-value input-el) code))
        ;; Default: replace all
        (set! (.-value input-el) code)))
    
    (js/console.warn "AI insert: unknown target" target)))

(defn- ai-edit-code
  "Edit code based on operation and target."
  [{:keys [operation target value]}]
  (when-let [view @editor-view]
    (case operation
      :replace
      (case (:type target)
        "word" (when-let [{:keys [from to]} (cm/get-word-at-cursor view)]
                 (cm/replace-range view from to value))
        "form" (when-let [{:keys [from to]} (cm/get-form-at-cursor view)]
                 (cm/replace-range view from to value))
        "selection" (let [{:keys [from to]} (cm/get-selection view)]
                      (when (not= from to)
                        (cm/replace-range view from to value)))
        (js/console.warn "AI edit replace: unknown target type" (:type target)))
      
      :delete
      (case (:type target)
        "word" (when-let [{:keys [from to]} (cm/get-word-at-cursor view)]
                 (cm/delete-range view from to))
        "form" (when-let [{:keys [from to]} (cm/get-form-at-cursor view)]
                 (cm/delete-range view from to))
        "selection" (let [{:keys [from to]} (cm/get-selection view)]
                      (when (not= from to)
                        (cm/delete-range view from to)))
        (js/console.warn "AI edit delete: unknown target type" (:type target)))
      
      :wrap
      (when-let [{:keys [from to text]} (cm/get-form-at-cursor view)]
        ;; value should have $ as placeholder for content
        (let [wrapped (clojure.string/replace value "$" text)]
          (cm/replace-range view from to wrapped)))
      
      :unwrap
      (when-let [{:keys [from to text]} (cm/get-form-at-cursor view)]
        ;; Remove outer parens and first symbol
        (when (and (str/starts-with? text "(")
                   (str/ends-with? text ")"))
          (let [inner (subs text 1 (dec (count text)))
                ;; Skip first token (the function name)
                content (str/trim (str/replace-first inner #"^\S+\s*" ""))]
            (cm/replace-range view from to content))))
      
      (js/console.warn "AI edit: unknown operation" operation))
    
    ;; Auto-save after edit
    (save-to-storage)
    (send-script-debounced)))

(defn- ai-navigate
  "Navigate cursor based on direction and mode."
  [{:keys [direction mode count]}]
  (when-let [view @editor-view]
    (let [n (or count 1)]
      (dotimes [_ n]
        (case mode
          :text
          (case direction
            :left (cm/move-cursor view :left)
            :right (cm/move-cursor view :right)
            :up (cm/move-cursor view :up)
            :down (cm/move-cursor view :down)
            :start (cm/move-cursor view :start)
            :end (cm/move-cursor view :end)
            nil)
          
          :structure
          (case direction
            :next (commands/cursorLineBoundaryForward view)
            :prev (commands/cursorLineBoundaryBackward view)
            :parent (when-let [{:keys [from]} (cm/get-form-at-cursor view)]
                      ;; Move to start of current form, then try to find parent
                      (cm/set-cursor-position view {:pos (max 0 (dec from))})
                      (when-let [parent (cm/get-form-at-cursor view)]
                        (cm/set-cursor-position view {:pos (:from parent)})))
            :child (when-let [{:keys [from]} (cm/get-form-at-cursor view)]
                     ;; Move inside the form (after opening paren)
                     (cm/set-cursor-position view {:pos (inc from)}))
            :start (cm/move-cursor view :start)
            :end (cm/move-cursor view :end)
            nil)
          
          ;; Default: text mode
          (cm/move-cursor view direction))))))

;; Update the init function to use real handlers

(ai/init! {:insert ai-insert-code
           :edit ai-edit-code
           :navigate ai-navigate
           :execute (fn [{:keys [target]}]
                      (case target
                        :script (evaluate-definitions)
                        :repl (evaluate-repl-input)
                        (js/console.warn "AI execute: unknown target" target)))})
```

### 3. `src/ridley/core.cljs` — Add AI state sync

Add a function to sync editor state to AI state (for context):

```clojure
(defn- sync-ai-state
  "Sync current editor state to AI state for LLM context."
  []
  (when (ai/enabled?)
    (when-let [view @editor-view]
      ;; Update buffer
      (ai/update-buffer! :script (cm/get-value view))
      (when-let [repl-el @repl-input-el]
        (ai/update-buffer! :repl (.-value repl-el)))
      
      ;; Update cursor info
      (let [cursor (cm/get-cursor-position view)
            form (cm/get-form-at-cursor view)]
        (ai/update-cursor! {:line (:line cursor)
                            :col (:col cursor)
                            :current-form (:text form)}))
      
      ;; Update scene info
      (ai/update-scene! {:meshes (registry/registered-names)
                         :visible (registry/visible-names)
                         :shapes (registry/shape-names)
                         :paths (registry/path-names)}))))
```

Call `sync-ai-state` after:
- Editor content changes (in on-change callback)
- Cursor moves (add a selection change listener)
- After code execution

### 4. `src/ridley/core.cljs` — Add UI button and panel

Add the AI toggle button to the toolbar HTML or create it dynamically:

```clojure
;; In setup-save-load or a new setup-ai function:

(defn- setup-ai-ui
  "Setup AI voice button and status panel."
  []
  ;; Create AI toggle button
  (when-let [toolbar (.getElementById js/document "viewport-toolbar")]
    (let [ai-btn (.createElement js/document "button")]
      (set! (.-id ai-btn) "btn-ai-voice")
      (set! (.-className ai-btn) "toolbar-button")
      (set! (.-textContent ai-btn) "🎤")
      (set! (.-title ai-btn) "Toggle AI Voice (disabled)")
      (.addEventListener ai-btn "click"
        (fn [_]
          (ai/toggle-voice!)
          (if (ai/voice-active?)
            (do
              (.add (.-classList ai-btn) "active")
              (set! (.-title ai-btn) "AI Voice Active - Click to disable"))
            (do
              (.remove (.-classList ai-btn) "active")
              (set! (.-title ai-btn) "Toggle AI Voice")))))
      (.appendChild toolbar ai-btn)))
  
  ;; Create AI status panel (for debugging)
  (let [panel (.createElement js/document "div")]
    (set! (.-id panel) "ai-status-panel")
    (set! (.-className panel) "ai-panel")
    (set! (.-style.display panel) "none")  ; Hidden by default
    (.appendChild js/document.body panel)
    
    ;; Update panel periodically when AI is active
    (js/setInterval
      (fn []
        (when (ai/enabled?)
          (set! (.-style.display panel) "block")
          (set! (.-innerHTML panel) (ai/render-panel-html)))
        (when-not (ai/enabled?)
          (set! (.-style.display panel) "none")))
      500)))
```

Call `(setup-ai-ui)` in `init`.

## Testing

1. Start Ollama: `ollama run llama3.2:3b`

2. Open Ridley in browser (localhost or HTTPS for speech API)

3. Click the 🎤 button to enable AI

4. Say commands:
   - "vai avanti di venti" → should insert `(f 20)`
   - "esegui" → should run the code
   - "cambia in trenta" → should change word under cursor to 30

5. Check the AI panel shows current state

## Notes

- The `get-form-at-cursor` function is a simple implementation. For better structural editing, we could use clojure-mode's paredit utilities.
- The navigate `:parent` and `:child` operations are approximations. Proper paredit navigation would be better.
- REPL input is a simple textarea, so position-based insert is simpler than CodeMirror.
- State sync should be efficient — don't send full buffer to AI on every keystroke, use debouncing.

## Dependencies

Make sure these imports are added to `codemirror.cljs`:
```clojure
["@codemirror/commands" :as commands ...]
```

And to `core.cljs`:
```clojure
[ridley.ai.core :as ai]  ; already there
```
