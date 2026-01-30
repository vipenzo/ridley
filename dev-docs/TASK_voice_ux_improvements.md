# Task: Voice UX Improvements

## Overview

Improve the voice input experience with:
1. Push-to-talk mode (default)
2. Better error feedback to user
3. Visual state indicators on the button

## Files to Modify

### 1. `src/ridley/ai/voice.cljs` — Add push-to-talk and error messages

Replace the entire file:

```clojure
(ns ridley.ai.voice
  "Web Speech API integration for voice input."
  (:require [ridley.ai.state :as state]))

(defonce ^:private recognition (atom nil))
(defonce ^:private current-callback (atom nil))

(defn supported? []
  (or (.-webkitSpeechRecognition js/window)
      (.-SpeechRecognition js/window)))

(defn- create-recognition []
  (let [SpeechRecognition (or (.-webkitSpeechRecognition js/window)
                              (.-SpeechRecognition js/window))]
    (when SpeechRecognition
      (let [rec (SpeechRecognition.)]
        ;; For push-to-talk: single result, not continuous
        (set! (.-continuous rec) false)
        (set! (.-interimResults rec) true)
        (set! (.-lang rec) "it-IT")
        rec))))

(defn- on-result [callback event]
  (let [results (.-results event)
        last-idx (dec (.-length results))
        result (aget results last-idx)
        transcript (.-transcript (aget result 0))
        is-final (.-isFinal result)]
    ;; Update partial transcript
    (state/update-voice! {:partial-transcript transcript})
    ;; When final, trigger callback
    (when is-final
      (state/update-voice! {:last-utterance transcript
                            :partial-transcript ""
                            :pending-speech nil})
      (when callback
        (callback transcript)))))

(defn- on-error [event]
  (let [error (.-error event)
        msg (case error
              "no-speech" "Nessun audio rilevato"
              "network" "Errore di rete — prova Chrome"
              "not-allowed" "Microfono non autorizzato"
              "audio-capture" "Nessun microfono trovato"
              "aborted" nil  ; user cancelled, no message needed
              (str "Errore: " error))]
    (js/console.warn "Speech recognition error:" error)
    (state/update-voice! {:listening? false
                          :partial-transcript ""
                          :pending-speech msg})))

(defn- on-end []
  ;; Recognition ended (either got result, error, or timeout)
  ;; For push-to-talk, we don't auto-restart
  (state/update-voice! {:listening? false}))

(defn start!
  "Start listening for a single utterance (push-to-talk style).
   Calls on-utterance with final transcript string."
  [on-utterance]
  (when (supported?)
    ;; Create fresh recognition each time for clean state
    (when-let [old-rec @recognition]
      (try (.abort old-rec) (catch :default _)))
    (let [rec (create-recognition)]
      (reset! recognition rec)
      (reset! current-callback on-utterance)
      (when rec
        (set! (.-onresult rec) (partial on-result on-utterance))
        (set! (.-onerror rec) on-error)
        (set! (.-onend rec) on-end)
        (try
          (.start rec)
          (state/update-voice! {:listening? true
                                :partial-transcript ""
                                :pending-speech nil})
          (catch :default e
            (js/console.error "Failed to start speech recognition:" e)
            (state/update-voice! {:listening? false
                                  :pending-speech "Impossibile avviare il microfono"})))))))

(defn stop!
  "Stop listening and process any pending result."
  []
  (when-let [rec @recognition]
    (try
      (.stop rec)  ; .stop() triggers onend and processes pending results
      (catch :default _)))
  (state/update-voice! {:listening? false}))

(defn abort!
  "Abort listening without processing results."
  []
  (when-let [rec @recognition]
    (try
      (.abort rec)  ; .abort() cancels without result
      (catch :default _)))
  (state/update-voice! {:listening? false
                        :partial-transcript ""}))

(defn listening? []
  (:listening? (:voice @state/ai-state)))
```

### 2. `src/ridley/ai/core.cljs` — Update toggle for push-to-talk

```clojure
(ns ridley.ai.core
  "Main AI extension integration."
  (:require [ridley.ai.state :as state]
            [ridley.ai.voice :as voice]
            [ridley.ai.llm :as llm]
            [ridley.ai.actions :as actions]
            [ridley.ai.tts :as tts]
            [ridley.ai.panel :as panel]))

(defn- on-utterance [transcript]
  "Handle a completed voice utterance."
  (js/console.log "Voice input:" transcript)
  ;; Show "processing" feedback
  (state/update-voice! {:pending-speech "Elaboro..."})
  (llm/process-utterance
   transcript
   (fn [action]
     (js/console.log "LLM action:" (pr-str action))
     ;; Clear "processing" message
     (state/update-voice! {:pending-speech nil})
     (actions/execute! action))))

(defn init!
  "Initialize AI extension with editor handlers.
   handlers: {:insert fn, :edit fn, :navigate fn, :execute fn}"
  [handlers]
  (actions/set-handlers! (assoc handlers :speak tts/speak!))
  (js/console.log "AI extension initialized"))

(defn start-listening!
  "Start listening for voice input (push-to-talk)."
  []
  (state/enable!)
  (voice/start! on-utterance))

(defn stop-listening!
  "Stop listening and process result."
  []
  (voice/stop!))

(defn cancel-listening!
  "Cancel listening without processing."
  []
  (voice/abort!))

(defn toggle-voice!
  "Toggle voice input. If listening, stop. If not, start."
  []
  (if (voice/listening?)
    (stop-listening!)
    (start-listening!)))

(defn voice-active? []
  (voice/listening?))

(defn enabled? []
  (state/enabled?))

(defn get-transcript []
  "Get current partial transcript for UI display."
  (get-in @state/ai-state [:voice :partial-transcript]))

(defn get-pending-speech []
  "Get pending speech/status text for UI display."
  (get-in @state/ai-state [:voice :pending-speech]))

;; Panel rendering (for UI integration)
(defn render-panel-text []
  "Render AI status panel as text (for 3D text panel or console)."
  (panel/render-text))

(defn render-panel-html []
  "Render AI status panel as hiccup HTML structure."
  (panel/render-html))

(defn get-panel-css []
  "Get CSS for AI panel styling."
  panel/panel-css)

;; Expose for REPL/debugging
(defn status []
  {:enabled (state/enabled?)
   :listening (voice-active?)
   :transcript (get-transcript)
   :pending-speech (get-pending-speech)
   :mode (:mode @state/ai-state)})
```

### 3. `src/ridley/core.cljs` — Update button behavior for push-to-talk

Find the AI button setup and replace with:

```clojure
(defn- setup-ai-ui
  "Setup AI voice button with push-to-talk behavior."
  []
  (when-let [toolbar (.getElementById js/document "viewport-toolbar")]
    (let [ai-btn (.createElement js/document "button")]
      (set! (.-id ai-btn) "btn-ai-voice")
      (set! (.-className ai-btn) "toolbar-button")
      (set! (.-textContent ai-btn) "🎤")
      (set! (.-title ai-btn) "Push to talk — hold or click")
      
      ;; Track if we're doing push-to-talk (hold) or toggle (click)
      (let [press-start (atom nil)
            hold-threshold 200]  ; ms - if held longer than this, it's push-to-talk
        
        ;; Mouse down — start listening immediately
        (.addEventListener ai-btn "mousedown"
          (fn [e]
            (.preventDefault e)
            (reset! press-start (js/Date.now))
            (ai/start-listening!)
            (.add (.-classList ai-btn) "active")))
        
        ;; Mouse up — if held, stop listening; if quick click, keep listening
        (.addEventListener ai-btn "mouseup"
          (fn [_]
            (when-let [start @press-start]
              (let [held (- (js/Date.now) start)]
                (if (> held hold-threshold)
                  ;; Held — this was push-to-talk, stop now
                  (do
                    (ai/stop-listening!)
                    (.remove (.-classList ai-btn) "active"))
                  ;; Quick click — toggle mode, will stop when speech ends
                  nil)))))
        
        ;; Mouse leave while pressed — treat as release
        (.addEventListener ai-btn "mouseleave"
          (fn [_]
            (when @press-start
              (reset! press-start nil)
              (when (ai/voice-active?)
                (ai/stop-listening!))
              (.remove (.-classList ai-btn) "active")))))
      
      ;; Watch state to update button appearance
      (add-watch state/ai-state :ai-button-update
        (fn [_ _ old-state new-state]
          (let [was-listening (get-in old-state [:voice :listening?])
                is-listening (get-in new-state [:voice :listening?])
                pending (get-in new-state [:voice :pending-speech])]
            ;; Update active class
            (when (not= was-listening is-listening)
              (if is-listening
                (.add (.-classList ai-btn) "active")
                (.remove (.-classList ai-btn) "active")))
            ;; Show error/status as tooltip
            (when pending
              (set! (.-title ai-btn) pending))
            (when (and (not pending) (not is-listening))
              (set! (.-title ai-btn) "Push to talk — hold or click")))))
      
      (.appendChild toolbar ai-btn)))
  
  ;; Create AI status panel (for debugging, hidden by default)
  (let [panel-el (.createElement js/document "div")]
    (set! (.-id panel-el) "ai-status-panel")
    (set! (.-className panel-el) "ai-panel")
    (set! (.-style.display panel-el) "none")
    (.appendChild js/document.body panel-el)
    
    ;; Update panel content when state changes
    (add-watch state/ai-state :ai-panel-update
      (fn [_ _ _ new-state]
        (when (state/enabled?)
          (set! (.-style.display panel-el) "block")
          ;; Convert hiccup to HTML string (simple version)
          (let [voice (:voice new-state)
                listening (:listening? voice)
                transcript (:partial-transcript voice)
                pending (:pending-speech voice)]
            (set! (.-innerHTML panel-el)
              (str "<div class='ai-header'>"
                   (if listening "🎤 Listening..." "⚫ Ready")
                   "</div>"
                   (when (seq transcript)
                     (str "<div class='ai-voice'>" transcript "</div>"))
                   (when pending
                     (str "<div class='ai-feedback'>" pending "</div>"))))))
        (when-not (state/enabled?)
          (set! (.-style.display panel-el) "none"))))))
```

### 4. `public/css/style.css` — Add button states

```css
/* AI Voice Button States */
#btn-ai-voice {
  transition: all 0.15s ease;
}

#btn-ai-voice.active {
  background: #e74c3c;
  color: white;
  animation: pulse-recording 1s infinite;
}

@keyframes pulse-recording {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

/* AI Panel - compact version for status */
#ai-status-panel {
  position: fixed;
  bottom: 60px;
  right: 10px;
  z-index: 1000;
  font-family: 'SF Mono', Monaco, monospace;
  font-size: 12px;
  background: rgba(30, 30, 30, 0.95);
  color: #eee;
  padding: 8px 12px;
  border-radius: 4px;
  border: 1px solid #444;
  min-width: 200px;
  max-width: 300px;
}

#ai-status-panel .ai-header {
  margin-bottom: 4px;
}

#ai-status-panel .ai-voice {
  color: #8ff;
  padding: 4px;
  background: #252540;
  border-radius: 2px;
  margin: 4px 0;
}

#ai-status-panel .ai-feedback {
  color: #f88;
  font-size: 11px;
  margin-top: 4px;
}
```

## Behavior Summary

**Push-to-talk (hold > 200ms):**
1. Press and hold 🎤
2. Button turns red, shows "Listening..."
3. Speak your command
4. Release button
5. Processes speech → sends to LLM → executes action

**Click mode (quick click < 200ms):**
1. Click 🎤
2. Button turns red, listens
3. Speak your command
4. Recognition auto-stops when you stop talking
5. Processes speech → sends to LLM → executes action

**Error handling:**
- "Nessun audio rilevato" — shows in tooltip and panel
- "Errore di rete" — shows in tooltip
- "Microfono non autorizzato" — shows in tooltip

## Testing

1. Click 🎤 quickly — should listen until you stop talking
2. Hold 🎤, speak, release — should process immediately on release
3. Click 🎤, stay silent — should show "Nessun audio rilevato" after timeout
4. Check panel shows transcript while speaking

## Notes

- `continuous: false` on SpeechRecognition for single-utterance mode
- `.stop()` processes pending results, `.abort()` discards them
- Button state syncs via `add-watch` on the state atom
- Error messages in Italian to match voice commands
