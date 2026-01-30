# Task: Fix CodeMirror Selection and AI Integration

## Overview

Fix several related issues with CodeMirror selection visibility and AI integration:

1. Selection not visible (z-index issue)
2. AI panel doesn't update when cursor/selection changes
3. After AI insert, the inserted form should be selected
4. Editor loses focus when clicking AI button

## Files to Modify

### 1. `src/ridley/editor/codemirror.cljs` — Fix selection visibility

In the `create-theme` function, add the selection layer fix:

```clojure
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
         ;; SELECTION FIX - layer must be above content with blend mode
         ".cm-selectionLayer" #js {:zIndex "100"
                                   :pointerEvents "none"
                                   :mixBlendMode "screen"}
         ;; Syntax highlighting - brighter colors
         ".cm-keyword" #js {:color "#c586c0"}
         ".cm-atom" #js {:color "#4fc1ff"}
         ".cm-number" #js {:color "#b5cea8"}
         ".cm-string" #js {:color "#ce9178"}
         ".cm-comment" #js {:color "#6a9955"
                            :fontStyle "italic"}
         ".cm-variableName" #js {:color "#9cdcfe"}
         ".cm-definition" #js {:color "#dcdcaa"}
         ".cm-punctuation" #js {:color "#d4d4d4"}
         ".cm-bracket" #js {:color "#ffd700"}
         ".cm-matchingBracket" #js {:backgroundColor "#3a3d41"
                                    :color "#ffd700"
                                    :fontWeight "bold"}
         ".cm-selectionMatch" #js {:backgroundColor "#3a3d41"}
         ".cm-cursor" #js {:borderLeftColor "#fff"
                           :borderLeftWidth "2px"}
         ;; Selection background colors
         "&.cm-focused .cm-selectionBackground" #js {:backgroundColor "#264f78"}
         ".cm-selectionBackground" #js {:backgroundColor "#3a3d41"}}
    #js {:dark true}))
```

### 2. `src/ridley/editor/codemirror.cljs` — Add selection change callback

Add a new function to create a selection change listener:

```clojure
(defn- create-selection-listener
  "Create update listener that calls on-selection-change when selection changes."
  [on-selection-change]
  (.of (.-updateListener EditorView)
    (fn [^js update]
      (when (and on-selection-change (.-selectionSet update))
        (on-selection-change)))))
```

Update `create-editor` to accept `on-selection-change` callback:

```clojure
(defn create-editor
  "Create a CodeMirror editor instance.
   Options:
   - parent: DOM element to mount editor
   - initial-value: initial content string
   - on-change: callback when content changes
   - on-run: callback for Cmd+Enter
   - on-selection-change: callback when selection/cursor changes"
  [{:keys [parent initial-value on-change on-run on-selection-change]}]
  (let [extensions (cond-> [;; ... existing extensions ...
                            ]
                     on-change (conj (create-change-listener on-change))
                     on-selection-change (conj (create-selection-listener on-selection-change)))
        ;; ... rest of function
```

### 3. `src/ridley/editor/codemirror.cljs` — Add select-range function

Add a function to select a range of text:

```clojure
(defn select-range
  "Select text from position `from` to position `to`."
  ([from to] (select-range @editor-instance from to))
  ([view from to]
   (when view
     (.dispatch view
       #js {:selection #js {:anchor from :head to}})
     ;; Return the selection info
     {:from from :to to :text (.sliceDoc (.-state view) from to)})))
```

### 4. `src/ridley/core.cljs` — Update editor creation with selection listener

Find where the editor is created and add the selection change callback:

```clojure
(reset! editor-view
  (cm/create-editor
    {:parent editor-container
     :initial-value initial-content
     :on-change (fn []
                  (save-to-storage)
                  (send-script-debounced))
     :on-run evaluate-definitions
     :on-selection-change (fn []
                            (sync-ai-state))}))
```

### 5. `src/ridley/core.cljs` — Update ai-insert-code to select inserted form

Modify the `ai-insert-code` function to select the inserted code:

```clojure
(defn- ai-insert-code
  "Insert code into editor or REPL based on target and position."
  [{:keys [target code position]}]
  (case target
    :script
    (when-let [view @editor-view]
      (let [insert-pos (case position
                         :cursor (.. view -state -selection -main -head)
                         :end (.. view -state -doc -length)
                         :after-current-form
                         (if-let [form (cm/get-form-at-cursor view)]
                           (:to form)
                           (.. view -state -selection -main -head))
                         ;; Default: at cursor
                         (.. view -state -selection -main -head))
            ;; Add newline prefix for after-current-form
            actual-code (if (= position :after-current-form)
                          (str "\n" code)
                          code)
            code-length (count actual-code)
            start-pos (if (= position :after-current-form)
                        (inc insert-pos)  ; skip the newline
                        insert-pos)
            end-pos (+ insert-pos code-length)]
        ;; Insert the code
        (case position
          :cursor (cm/insert-at-cursor view actual-code)
          :end (cm/insert-at-end view actual-code)
          :after-current-form
          (do
            (cm/set-cursor-position view {:pos insert-pos})
            (cm/insert-at-cursor view actual-code))
          ;; Default
          (cm/insert-at-cursor view actual-code))
        ;; Select the inserted code (excluding leading newline if any)
        (cm/select-range view start-pos end-pos)
        ;; Keep focus on editor
        (cm/focus view))
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
```

### 6. `src/ridley/core.cljs` — Keep focus on editor when clicking AI button

In `setup-ai-ui`, update the button handlers to refocus the editor:

```clojure
;; Mouse down — start listening, but remember to refocus editor
(.addEventListener ai-btn "mousedown"
  (fn [e]
    (.preventDefault e)
    (.stopPropagation e)  ; Prevent focus loss
    (reset! press-start (js/Date.now))
    (ai/start-listening!)
    (.add (.-classList ai-btn) "active")
    ;; Refocus editor after a brief delay
    (js/setTimeout #(when @editor-view (cm/focus @editor-view)) 50)))
```

Also update mouseup:

```clojure
(.addEventListener ai-btn "mouseup"
  (fn [_]
    (when-let [start @press-start]
      (reset! press-start nil)
      (let [held (- (js/Date.now) start)]
        (if (> held hold-threshold)
          ;; Held — this was push-to-talk, stop now
          (do
            (ai/stop-listening!)
            (.remove (.-classList ai-btn) "active"))
          ;; Quick click — toggle mode, keep listening
          nil))
      ;; Always refocus editor
      (when @editor-view (cm/focus @editor-view)))))
```

### 7. `src/ridley/core.cljs` — Ensure sync-ai-state updates cursor info

Make sure `sync-ai-state` properly updates cursor and form info:

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
      
      ;; Update cursor and form info
      (let [cursor (cm/get-cursor-position view)
            form (cm/get-form-at-cursor view)
            selection (cm/get-selection view)]
        (ai/update-cursor! {:line (:line cursor)
                            :col (:col cursor)
                            :current-form (:text form)
                            :selection (when (not= (:from selection) (:to selection))
                                         (:text selection))}))
      
      ;; Update scene info
      (ai/update-scene! {:meshes (vec (registry/registered-names))
                         :visible (vec (registry/visible-names))
                         :shapes (vec (registry/shape-names))
                         :paths (vec (registry/path-names))}))))
```

## Summary of Changes

1. **Selection CSS fix**: `.cm-selectionLayer` with `zIndex: 100`, `pointerEvents: none`, `mixBlendMode: screen`

2. **Selection change listener**: New `on-selection-change` callback in `create-editor`

3. **Select inserted code**: After AI insert, the code is selected so it's visible and wrap operations work

4. **Keep editor focus**: `preventDefault` and `stopPropagation` on AI button, plus explicit `focus()` calls

5. **Sync AI state on selection change**: Panel updates when cursor moves

## Testing

1. Select text in editor — should be visible with screen blend mode
2. Say "cubo 50" — should insert `(box 50)` AND select it
3. Say "registra come mio cubo" — should wrap the selected form
4. Click 🎤 — editor should keep cursor visible
5. Move cursor around — AI panel should update "Cursor" section
