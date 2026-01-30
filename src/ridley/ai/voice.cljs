(ns ridley.ai.voice
  "Web Speech API integration for voice input."
  (:require [ridley.ai.state :as state]))

(defonce ^:private recognition (atom nil))
(defonce ^:private current-callback (atom nil))
(defonce ^:private voice-lang (atom "it-IT"))

(defn set-language!
  "Set voice recognition language: 'it-IT' or 'en-US'"
  [lang]
  (reset! voice-lang lang))

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
        (set! (.-lang rec) @voice-lang)
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
