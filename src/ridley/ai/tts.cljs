(ns ridley.ai.tts
  "Text-to-speech for voice feedback.")

(defn supported? []
  (.-speechSynthesis js/window))

(defn speak! [text]
  "Speak text using Web Speech API."
  (when (supported?)
    (let [utterance (js/SpeechSynthesisUtterance. text)]
      (set! (.-lang utterance) "it-IT")
      (set! (.-rate utterance) 1.1)
      (.speak (.-speechSynthesis js/window) utterance))))

(defn stop! []
  "Stop any ongoing speech."
  (when (supported?)
    (.cancel (.-speechSynthesis js/window))))
