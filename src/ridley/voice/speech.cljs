(ns ridley.voice.speech
  "Web Speech API integration for voice input (push-to-talk)."
  (:require [ridley.voice.state :as state]))

(defonce ^:private recognition (atom nil))

;; Use plain atoms (not defonce) so hot-reload picks up changes
(def ^:private utterance-callback (atom nil))
(def ^:private finalized? (atom false))

(def ^:private lang-codes
  {:it "it-IT" :en "en-US"})

(defn supported? []
  (boolean (or (.-webkitSpeechRecognition js/window)
               (.-SpeechRecognition js/window))))

(defn- create-recognition []
  (let [SpeechRecognition (or (.-webkitSpeechRecognition js/window)
                              (.-SpeechRecognition js/window))]
    (when SpeechRecognition
      (let [rec (SpeechRecognition.)
            lang (get lang-codes (state/get-language) "it-IT")]
        (set! (.-continuous rec) false)
        (set! (.-interimResults rec) true)
        (set! (.-lang rec) lang)
        rec))))

(defn- on-result [callback event]
  (let [results (.-results event)
        last-idx (dec (.-length results))
        result (aget results last-idx)
        transcript (.-transcript (aget result 0))
        is-final (.-isFinal result)]
    (state/update-voice! {:partial-transcript transcript})
    (when is-final
      (reset! finalized? true)
      (state/update-voice! {:last-utterance transcript
                            :partial-transcript ""
                            :pending-speech nil})
      (when callback
        (callback transcript)))))

(defn- on-error [event]
  (let [error (.-error event)
        msg (case error
              "no-speech" "Nessun audio rilevato"
              "network" "Errore di rete"
              "not-allowed" "Microfono non autorizzato"
              "audio-capture" "Nessun microfono trovato"
              "aborted" nil
              (str "Errore: " error))]
    (js/console.warn "Speech recognition error:" error)
    (state/update-voice! {:listening? false
                          :partial-transcript ""
                          :pending-speech msg})))

(defn- on-end []
  ;; If recognition ended without a final result, promote the last partial
  (when-not @finalized?
    (let [partial (get-in @state/voice-state [:voice :partial-transcript])]
      (when (and (seq partial) @utterance-callback)
        (js/console.log "Speech: promoting partial →" partial)
        (state/update-voice! {:last-utterance partial
                              :partial-transcript ""
                              :pending-speech nil})
        (@utterance-callback partial))))
  (state/update-voice! {:listening? false}))

(defn start!
  "Start listening for a single utterance (push-to-talk).
   Calls on-utterance with final transcript string."
  [on-utterance]
  (when (supported?)
    (when-let [old-rec @recognition]
      (try (.abort old-rec) (catch :default _)))
    (reset! utterance-callback on-utterance)
    (reset! finalized? false)
    (let [rec (create-recognition)]
      (reset! recognition rec)
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
  "Stop listening — on-end will handle promoting partial if needed."
  []
  (when-let [rec @recognition]
    (try (.stop rec) (catch :default _))))

(defn abort!
  "Abort listening without processing results."
  []
  (reset! finalized? true) ;; Prevent on-end from promoting partial
  (when-let [rec @recognition]
    (try (.abort rec) (catch :default _)))
  (state/update-voice! {:listening? false
                        :partial-transcript ""}))

(defn listening? []
  (get-in @state/voice-state [:voice :listening?]))

(defn set-language!
  "Update speech recognition language. Takes effect on next start! call."
  [lang-key]
  (state/set-language! lang-key))
