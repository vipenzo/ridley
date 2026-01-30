# Task: Restore Full AI Debug Panel

## Problem

The AI panel was simplified too much in the UX update. It now only shows "Ready" or "Listening". We need to restore the full debug information.

## Files to Modify

### 1. `src/ridley/core.cljs` — Restore full panel rendering

Find the `setup-ai-ui` function and replace the panel update section with:

```clojure
;; Create AI status panel (for debugging)
(let [panel-el (.createElement js/document "div")]
  (set! (.-id panel-el) "ai-status-panel")
  (set! (.-className panel-el) "ai-panel")
  (set! (.-style.display panel-el) "none")
  (.appendChild js/document.body panel-el)
  
  ;; Update panel content when state changes
  (add-watch state/ai-state :ai-panel-update
    (fn [_ _ _ new-state]
      (if (state/enabled?)
        (do
          (set! (.-style.display panel-el) "block")
          (let [voice (:voice new-state)
                cursor (:cursor new-state)
                scene (:scene new-state)
                mode (:mode new-state)
                listening (:listening? voice)
                transcript (:partial-transcript voice)
                pending (:pending-speech voice)
                last-utterance (:last-utterance voice)
                meshes (:meshes scene)
                shapes (:shapes scene)
                paths (:paths scene)]
            (set! (.-innerHTML panel-el)
              (str 
               ;; Header
               "<div class='ai-header'>"
               "<span class='ai-status'>" (if listening "🟢" "⚫") "</span>"
               (when listening " <span class='ai-listening'>🎤 LISTENING</span>")
               " <span class='ai-mode'>[" (name mode) "]</span>"
               " <span class='ai-target'>" (name (or (:target cursor) :script)) "</span>"
               "</div>"
               
               ;; Cursor info
               "<div class='ai-section'>"
               "<div class='ai-section-title'>Cursor</div>"
               "<code class='ai-current-form'>" 
               (or (:current-form cursor) "(no form)") 
               "</code>"
               "</div>"
               
               ;; Voice info
               "<div class='ai-section'>"
               "<div class='ai-section-title'>Voice</div>"
               (when (and transcript (seq transcript))
                 (str "<div class='ai-transcript'>🎤 \"" transcript "...\"</div>"))
               (when last-utterance
                 (str "<div class='ai-last-utterance'>Last: \"" last-utterance "\"</div>"))
               (when pending
                 (str "<div class='ai-pending'>💬 " pending "</div>"))
               (when (and (not (seq transcript)) (not last-utterance) (not pending))
                 "<div class='ai-empty'>—</div>")
               "</div>"
               
               ;; Scene info
               "<div class='ai-section'>"
               "<div class='ai-section-title'>Scene</div>"
               "<div class='ai-scene-row'>Meshes: " 
               (if (seq meshes) 
                 (clojure.string/join ", " (map name meshes)) 
                 "—") 
               "</div>"
               "<div class='ai-scene-row'>Shapes: " 
               (if (seq shapes) 
                 (clojure.string/join ", " (map name shapes)) 
                 "—") 
               "</div>"
               "<div class='ai-scene-row'>Paths: " 
               (if (seq paths) 
                 (clojure.string/join ", " (map name paths)) 
                 "—") 
               "</div>"
               "</div>"))))
        ;; Not enabled - hide panel
        (set! (.-style.display panel-el) "none")))))
```

### 2. `public/css/style.css` — Add full panel styles

Replace/extend the AI panel styles:

```css
/* AI Debug Panel - Full Version */
#ai-status-panel {
  position: fixed;
  bottom: 60px;
  right: 10px;
  z-index: 1000;
  font-family: 'SF Mono', Monaco, monospace;
  font-size: 11px;
  background: rgba(26, 26, 46, 0.95);
  color: #eee;
  padding: 10px;
  border-radius: 6px;
  border: 1px solid #444;
  min-width: 260px;
  max-width: 320px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.3);
}

#ai-status-panel .ai-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-bottom: 8px;
  margin-bottom: 8px;
  border-bottom: 1px solid #333;
  font-size: 12px;
}

#ai-status-panel .ai-status {
  font-size: 8px;
}

#ai-status-panel .ai-listening {
  color: #4fc3f7;
  animation: pulse 1s infinite;
}

#ai-status-panel .ai-mode {
  color: #88f;
}

#ai-status-panel .ai-target {
  color: #8f8;
  margin-left: auto;
}

#ai-status-panel .ai-section {
  margin-bottom: 8px;
}

#ai-status-panel .ai-section:last-child {
  margin-bottom: 0;
}

#ai-status-panel .ai-section-title {
  color: #888;
  font-size: 10px;
  text-transform: uppercase;
  margin-bottom: 4px;
}

#ai-status-panel .ai-current-form {
  display: block;
  background: #252540;
  padding: 4px 8px;
  border-radius: 3px;
  color: #ff8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

#ai-status-panel .ai-transcript {
  color: #8ff;
  padding: 4px;
  background: #252540;
  border-radius: 3px;
  margin-bottom: 4px;
}

#ai-status-panel .ai-last-utterance {
  color: #888;
  font-size: 10px;
}

#ai-status-panel .ai-pending {
  color: #f88;
  padding: 4px;
  background: #3a2525;
  border-radius: 3px;
  margin-top: 4px;
}

#ai-status-panel .ai-empty {
  color: #555;
}

#ai-status-panel .ai-scene-row {
  color: #aaa;
  margin: 2px 0;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}
```

## Result

The panel should now show:
- Status indicator (🟢/⚫) + LISTENING when active
- Current mode [structure/text/dictation]
- Current target (script/repl)
- Current form under cursor
- Live transcript while speaking
- Last utterance after recognition
- Pending speech/status messages
- Registered meshes, shapes, paths

## Notes

- Make sure `state/ai-state` is required in core.cljs (should be via `ridley.ai.state :as state`)
- The `sync-ai-state` function should be called to populate scene info
- If scene info is always empty, check that `registry/shape-names` etc. are being called
