(ns ridley.audio
  "Audio feedback for code evaluation using Web Audio API.
   Plays synthesized tones on eval success/error for accessibility."
  (:require [ridley.settings :as settings]))

;; Lazily initialized AudioContext — created on first use to avoid
;; browser autoplay policy blocking.
(defonce ^:private audio-ctx (atom nil))

(defn- ensure-ctx!
  "Return a running AudioContext, creating one if needed.
   Resumes a suspended context (browsers suspend until user gesture)."
  []
  (let [ctx @audio-ctx]
    (if (and ctx (not= "closed" (.-state ctx)))
      (do
        (when (= "suspended" (.-state ctx))
          (.resume ctx))
        ctx)
      (let [new-ctx (js/AudioContext.)]
        (reset! audio-ctx new-ctx)
        new-ctx))))

(defn play-success!
  "Play a short high-pitched ping — sine wave ~880Hz, 100ms, quick fade."
  []
  (let [ctx   (ensure-ctx!)
        now   (.-currentTime ctx)
        osc   (.createOscillator ctx)
        gain  (.createGain ctx)]
    (set! (.-type osc) "sine")
    (set! (.. osc -frequency -value) 880)
    (.setValueAtTime (.-gain gain) 0.3 now)
    (.exponentialRampToValueAtTime (.-gain gain) 0.001 (+ now 0.1))
    (.connect osc gain)
    (.connect gain (.-destination ctx))
    (.start osc now)
    (.stop osc (+ now 0.15))))

(defn play-error!
  "Play a short low buzz — square wave ~220Hz, 150ms."
  []
  (let [ctx   (ensure-ctx!)
        now   (.-currentTime ctx)
        osc   (.createOscillator ctx)
        gain  (.createGain ctx)]
    (set! (.-type osc) "square")
    (set! (.. osc -frequency -value) 220)
    (.setValueAtTime (.-gain gain) 0.2 now)
    (.exponentialRampToValueAtTime (.-gain gain) 0.001 (+ now 0.15))
    (.connect osc gain)
    (.connect gain (.-destination ctx))
    (.start osc now)
    (.stop osc (+ now 0.2))))

(defn play-feedback!
  "Play success or error sound if audio feedback is enabled."
  [success?]
  (when (settings/audio-feedback?)
    (if success?
      (play-success!)
      (play-error!))))
