(ns ridley.voice.speech
  "Web Speech API integration for voice input.
   Supports two modes:
   - Push-to-talk (single utterance)
   - Continuous listening (auto-restart, silence timer)"
  (:require [ridley.voice.state :as state]))

(defonce ^:private recognition (atom nil))

;; Use plain atoms (not defonce) so hot-reload picks up changes
(def ^:private utterance-callback (atom nil))
(def ^:private finalized? (atom false))
(def ^:private continuous-mode? (atom false))
(def ^:private partial-timer (atom nil))

(def ^:private lang-codes
  {:it "it-IT" :en "en-US"})

(defn supported? []
  (boolean (or (.-webkitSpeechRecognition js/window)
               (.-SpeechRecognition js/window))))

(defn- create-recognition [continuous?]
  (let [SpeechRecognition (or (.-webkitSpeechRecognition js/window)
                              (.-SpeechRecognition js/window))]
    (when SpeechRecognition
      (let [rec (SpeechRecognition.)
            lang (get lang-codes (state/get-language) "it-IT")]
        (set! (.-continuous rec) continuous?)
        (set! (.-interimResults rec) true)
        (set! (.-lang rec) lang)
        rec))))

;; ============================================================
;; Single utterance mode (push-to-talk)
;; ============================================================

(defn- on-result-single [callback event]
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

(defn- on-end-single []
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

;; ============================================================
;; Continuous listening mode
;; ============================================================

(declare on-error-continuous)
(def ^:private error-count (atom 0))

(defn- cancel-partial-timer! []
  (when-let [t @partial-timer]
    (js/clearTimeout t)
    (reset! partial-timer nil)))

(defn- promote-partial! [callback text]
  (cancel-partial-timer!)
  (let [trimmed (.trim text)]
    (when (seq trimmed)
      (js/console.log "Speech continuous: auto-promoting partial →" trimmed)
      (state/update-voice! {:last-utterance trimmed
                            :partial-transcript ""})
      (when callback
        (callback {:type :utterance :text trimmed})))))

(defn- on-result-continuous [callback event]
  (let [results (.-results event)
        last-idx (dec (.-length results))
        result (aget results last-idx)
        transcript (.-transcript (aget result 0))
        is-final (.-isFinal result)]
    (state/update-voice! {:partial-transcript transcript})
    (if is-final
      (do
        (cancel-partial-timer!)
        (reset! error-count 0) ;; Successful recognition — reset error counter
        (state/update-voice! {:last-utterance transcript
                              :partial-transcript ""})
        ;; Deliver the finalized segment immediately
        (when callback
          (callback {:type :utterance :text transcript})))
      ;; Not final — start/reset stability timer to auto-promote
      (do
        (cancel-partial-timer!)
        (reset! partial-timer
                (js/setTimeout #(promote-partial! callback transcript) 1500))))))

(defn- on-end-continuous [callback]
  ;; Cancel any pending auto-promote timer (on-end will promote if needed)
  (cancel-partial-timer!)
  ;; Promote partial if any
  (let [partial (get-in @state/voice-state [:voice :partial-transcript])]
    (when (seq partial)
      (js/console.log "Speech continuous: promoting partial →" partial)
      (state/update-voice! {:last-utterance partial
                            :partial-transcript ""})
      (when callback
        (callback {:type :utterance :text partial}))))
  ;; Auto-restart if still in continuous mode
  (if @continuous-mode?
    (let [rec (create-recognition true)]
      (reset! recognition rec)
      (when rec
        (set! (.-onresult rec) (partial on-result-continuous callback))
        (set! (.-onerror rec) on-error-continuous)
        (set! (.-onend rec) (partial on-end-continuous callback))
        (try
          (.start rec)
          (catch :default e
            (js/console.warn "Speech continuous: restart failed" e)
            (reset! continuous-mode? false)
            (state/update-voice! {:listening? false})))))
    (state/update-voice! {:listening? false})))

(defn- on-error-continuous [event]
  (let [error (.-error event)]
    (case error
      ;; no-speech and aborted are expected in continuous mode
      "no-speech" (reset! error-count 0)
      "aborted" nil
      ;; not-allowed is fatal — user revoked permission
      "not-allowed"
      (do
        (js/console.warn "Speech continuous: microphone not allowed")
        (reset! continuous-mode? false)
        (state/update-voice! {:listening? false
                              :partial-transcript ""
                              :pending-speech "Microfono non autorizzato"}))
      ;; Other errors: log but let on-end-continuous handle restart
      ;; Stop only after repeated failures
      (do
        (swap! error-count inc)
        (js/console.warn "Speech continuous error:" error "count:" @error-count)
        (when (>= @error-count 5)
          (js/console.error "Speech continuous: too many errors, stopping")
          (reset! continuous-mode? false)
          (state/update-voice! {:listening? false
                                :partial-transcript ""
                                :pending-speech (str "Errore: " error)}))))))

;; ============================================================
;; Error handler (single mode)
;; ============================================================

(defn- on-error-single [event]
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

;; ============================================================
;; Public API
;; ============================================================

(defn start!
  "Start listening for a single utterance (push-to-talk).
   Calls on-utterance with final transcript string."
  [on-utterance]
  (when (supported?)
    (when-let [old-rec @recognition]
      (try (.abort old-rec) (catch :default _)))
    (reset! utterance-callback on-utterance)
    (reset! finalized? false)
    (reset! continuous-mode? false)
    (let [rec (create-recognition false)]
      (reset! recognition rec)
      (when rec
        (set! (.-onresult rec) (partial on-result-single on-utterance))
        (set! (.-onerror rec) on-error-single)
        (set! (.-onend rec) on-end-single)
        (try
          (.start rec)
          (state/update-voice! {:listening? true
                                :partial-transcript ""
                                :pending-speech nil})
          (catch :default e
            (js/console.error "Failed to start speech recognition:" e)
            (state/update-voice! {:listening? false
                                  :pending-speech "Impossibile avviare il microfono"})))))))

(defn start-continuous!
  "Start continuous listening mode.
   Calls on-event with maps:
     {:type :utterance :text \"...\"}  — finalized speech segment
     {:type :silence}                  — 2s silence detected"
  [on-event]
  (when (supported?)
    (when-let [old-rec @recognition]
      (try (.abort old-rec) (catch :default _)))
    (reset! continuous-mode? true)
    (reset! utterance-callback on-event)
    (let [rec (create-recognition true)]
      (reset! recognition rec)
      (when rec
        (set! (.-onresult rec) (partial on-result-continuous on-event))
        (set! (.-onerror rec) on-error-continuous)
        (set! (.-onend rec) (partial on-end-continuous on-event))
        (try
          (.start rec)
          (state/update-voice! {:listening? true
                                :continuous? true
                                :partial-transcript ""
                                :pending-speech nil})
          (catch :default e
            (js/console.error "Failed to start continuous recognition:" e)
            (reset! continuous-mode? false)
            (state/update-voice! {:listening? false
                                  :pending-speech "Impossibile avviare il microfono"})))))))

(defn stop!
  "Stop listening — on-end will handle promoting partial if needed."
  []
  (cancel-partial-timer!)
  (reset! continuous-mode? false)
  (state/update-voice! {:continuous? false})
  (when-let [rec @recognition]
    (try (.stop rec) (catch :default _))))

(defn abort!
  "Abort listening without processing results."
  []
  (cancel-partial-timer!)
  (reset! finalized? true)
  (reset! continuous-mode? false)
  (when-let [rec @recognition]
    (try (.abort rec) (catch :default _)))
  (state/update-voice! {:listening? false
                        :continuous? false
                        :partial-transcript ""}))

(defn listening? []
  (get-in @state/voice-state [:voice :listening?]))

(defn continuous? []
  @continuous-mode?)

(defn set-language!
  "Update speech recognition language. Takes effect on next start! call."
  [lang-key]
  (state/set-language! lang-key))
