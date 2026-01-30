# Task: AI Focus Indicator Layer

## Overview

Create a visual indicator that highlights the current form that AI operations will affect. This is separate from CodeMirror's selection and provides constant feedback about what the AI "sees" as the current form.

## Design

- A colored border/highlight around the form shown in the AI panel's "CURSOR" field
- Updates whenever the cursor moves or content changes
- Distinct from CM selection (use orange/yellow border instead of blue background)
- Works even when editor doesn't have focus

```
┌─────────────────────────────────────┐
│ (f 30) ┌───────────────────────┐    │
│        │ (register mio-cubo    │    │  ← orange border = AI focus
│        │   (box 30))           │    │
│        └───────────────────────┘    │
│ (pippo (era andato))                │
└─────────────────────────────────────┘
```

## Files to Modify

### 1. `src/ridley/editor/codemirror.cljs` — Add AI focus layer

Add a StateField and decoration for the AI focus indicator:

```clojure
(ns ridley.editor.codemirror
  (:require ["@codemirror/view" :as view :refer [EditorView keymap
                                                  Decoration
                                                  ViewPlugin
                                                  WidgetType
                                                  ;; ... existing imports
                                                  ]]
            ["@codemirror/state" :refer [EditorState StateField StateEffect]]
            ;; ... rest of imports
            ))

;; State effect to update AI focus range
(def set-ai-focus-effect (.define StateEffect))

;; State field to track AI focus range
(def ai-focus-field
  (.define StateField
    #js {:create (fn [] nil)
         :update (fn [value tr]
                   (let [effects (.-effects tr)]
                     (loop [i 0]
                       (if (< i (.-length effects))
                         (let [effect (aget effects i)]
                           (if (.is effect set-ai-focus-effect)
                             (.-value effect)
                             (recur (inc i))))
                         ;; No effect found, keep current value
                         ;; But adjust for document changes
                         (when value
                           (let [from (.-from value)
                                 to (.-to value)]
                             (when (and from to)
                               #js {:from (.mapPos (.-changes tr) from)
                                    :to (.mapPos (.-changes tr) to)})))))))}))

;; Decoration mark for AI focus
(def ai-focus-decoration
  (.mark Decoration #js {:class "cm-ai-focus"}))

;; Function to create decorations from state
(defn- ai-focus-decorations [state]
  (let [range (.field state ai-focus-field)]
    (if (and range (.-from range) (.-to range))
      (.set Decoration
        #js [(.range ai-focus-decoration (.-from range) (.-to range))])
      (.none Decoration))))

;; Plugin that provides decorations
(def ai-focus-plugin
  (.fromClass ViewPlugin
    (fn [view]
      #js {:decorations (ai-focus-decorations (.-state view))})
    #js {:decorations (fn [v] (.-decorations v))}))

;; Public function to set AI focus range
(defn set-ai-focus!
  "Set the AI focus highlight range. Pass nil to clear."
  ([range] (set-ai-focus! @editor-instance range))
  ([view range]
   (when view
     (let [effect (if range
                    (.of set-ai-focus-effect #js {:from (:from range) :to (:to range)})
                    (.of set-ai-focus-effect nil))]
       (.dispatch view #js {:effects #js [effect]})))))

;; Update AI focus to current form
(defn update-ai-focus!
  "Update AI focus to highlight the form at cursor."
  ([] (update-ai-focus! @editor-instance))
  ([view]
   (when view
     (let [form (get-form-at-cursor view)]
       (set-ai-focus! view form)))))

;; Clear AI focus
(defn clear-ai-focus!
  "Clear the AI focus highlight."
  ([] (clear-ai-focus! @editor-instance))
  ([view]
   (set-ai-focus! view nil)))
```

Update `create-editor` to include the AI focus extensions:

```clojure
(defn create-editor
  [{:keys [parent initial-value on-change on-run on-selection-change]}]
  (let [extensions (cond-> [;; ... existing extensions ...
                            
                            ;; AI focus indicator
                            ai-focus-field
                            ai-focus-plugin
                            
                            ;; ... rest of extensions
                            ]
```

### 2. `src/ridley/editor/codemirror.cljs` — Add CSS for AI focus

In `create-theme`, add the AI focus style:

```clojure
;; AI focus indicator - orange border around current form
".cm-ai-focus" #js {:outline "2px solid #ff9800"
                    :outlineOffset "-1px"
                    :borderRadius "3px"
                    :backgroundColor "rgba(255, 152, 0, 0.1)"}
```

Or if you prefer a more subtle look:

```clojure
;; AI focus indicator - subtle highlight
".cm-ai-focus" #js {:backgroundColor "rgba(255, 152, 0, 0.15)"
                    :borderBottom "2px solid #ff9800"}
```

### 3. `src/ridley/core.cljs` — Update AI focus when cursor moves

Update the selection change handler to also update AI focus:

```clojure
:on-selection-change (fn []
                       (cm/update-ai-focus!)
                       (sync-ai-state))
```

### 4. `src/ridley/core.cljs` — Update AI focus after insert

In `ai-insert-code`, after inserting code, update the focus:

```clojure
;; After inserting and selecting
(cm/select-range view start-pos end-pos)
;; Update AI focus to the inserted form
(cm/update-ai-focus! view)
;; Keep focus on editor
(cm/focus view)
```

### 5. `src/ridley/core.cljs` — Clear AI focus when AI is disabled

When AI is disabled, clear the focus indicator:

```clojure
;; In toggle-voice! or wherever AI is disabled
(when-not (ai/enabled?)
  (cm/clear-ai-focus!))
```

## Alternative Simpler Implementation

If the StateField/StateEffect approach is too complex, here's a simpler DOM-based approach:

### `src/ridley/editor/codemirror.cljs` — Simple overlay approach

```clojure
(defonce ^:private ai-focus-overlay (atom nil))

(defn update-ai-focus!
  "Update AI focus overlay to highlight the form at cursor."
  ([] (update-ai-focus! @editor-instance))
  ([view]
   (when view
     ;; Remove existing overlay
     (when-let [el @ai-focus-overlay]
       (.remove el))
     
     ;; Get current form
     (when-let [form (get-form-at-cursor view)]
       (let [{:keys [from to]} form
             ;; Get coordinates for the range
             start-coords (.coordsAtPos view from)
             end-coords (.coordsAtPos view to)
             ;; Get editor container for positioning
             editor-dom (.-dom view)
             editor-rect (.getBoundingClientRect editor-dom)
             ;; Create overlay element
             overlay (.createElement js/document "div")]
         
         (set! (.-className overlay) "cm-ai-focus-overlay")
         (set! (.-style.position overlay) "absolute")
         (set! (.-style.left overlay) (str (- (.-left start-coords) (.-left editor-rect)) "px"))
         (set! (.-style.top overlay) (str (- (.-top start-coords) (.-left editor-rect)) "px"))
         (set! (.-style.width overlay) (str (- (.-right end-coords) (.-left start-coords)) "px"))
         (set! (.-style.height overlay) (str (- (.-bottom end-coords) (.-top start-coords)) "px"))
         (set! (.-style.pointerEvents overlay) "none")
         
         ;; Add to editor
         (.appendChild editor-dom overlay)
         (reset! ai-focus-overlay overlay))))))

(defn clear-ai-focus!
  "Clear the AI focus overlay."
  []
  (when-let [el @ai-focus-overlay]
    (.remove el)
    (reset! ai-focus-overlay nil)))
```

And add CSS:

```css
.cm-ai-focus-overlay {
  border: 2px solid #ff9800;
  border-radius: 3px;
  background: rgba(255, 152, 0, 0.1);
  pointer-events: none;
  z-index: 50;
}
```

## Recommendation

Start with the simpler DOM overlay approach. It's easier to debug and doesn't require deep CodeMirror internals. If it works well, we can later migrate to the StateField approach for better integration.

## Testing

1. Click in editor — orange highlight should appear around the containing form
2. Move cursor between forms — highlight should follow
3. Cursor outside any form — highlight should disappear (or show nothing)
4. Say "cubo 50" — highlight should show around `(box 50)`
5. Say "registra come test" — should wrap the highlighted form
6. Disable AI — highlight should disappear

## Visual Design Options

**Option A: Border only**
```css
.cm-ai-focus { outline: 2px solid #ff9800; }
```

**Option B: Background + border**
```css
.cm-ai-focus { 
  background: rgba(255, 152, 0, 0.1);
  outline: 2px solid #ff9800;
}
```

**Option C: Underline only (subtle)**
```css
.cm-ai-focus { 
  border-bottom: 2px solid #ff9800;
}
```

Pick whichever looks best with your theme. Orange (#ff9800) contrasts well with the blue selection (#264f78).
