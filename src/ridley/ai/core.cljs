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

(defn get-transcript
  "Get current partial transcript for UI display."
  []
  (get-in @state/ai-state [:voice :partial-transcript]))

(defn get-pending-speech
  "Get pending speech/status text for UI display."
  []
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
