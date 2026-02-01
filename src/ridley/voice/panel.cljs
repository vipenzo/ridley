(ns ridley.voice.panel
  "Voice status panel UI."
  (:require [clojure.string :as str]
            [ridley.voice.state :as state]))

(def panel-css
  ".voice-panel {
     position: fixed;
     bottom: 8px;
     right: 8px;
     background: #1e1e1e;
     border: 1px solid #333;
     border-radius: 6px;
     padding: 8px 12px;
     font-family: 'SF Mono', Monaco, monospace;
     font-size: 11px;
     color: #ccc;
     min-width: 180px;
     z-index: 1000;
   }
   .voice-header {
     display: flex;
     align-items: center;
     gap: 6px;
     margin-bottom: 4px;
   }
   .voice-mode {
     color: #4fc1ff;
     font-weight: bold;
   }
   .voice-sub-mode {
     color: #ce9178;
   }
   .voice-listening {
     color: #4ec9b0;
     animation: voice-pulse 1s ease-in-out infinite;
   }
   @keyframes voice-pulse {
     0%, 100% { opacity: 1; }
     50% { opacity: 0.5; }
   }
   .voice-transcript {
     color: #9cdcfe;
     margin-top: 4px;
     font-style: italic;
   }
   .voice-pending {
     color: #dcdcaa;
     margin-top: 2px;
   }
   .voice-last {
     color: #666;
     margin-top: 2px;
   }
   .voice-continuous {
     color: #f44;
     font-weight: bold;
     animation: voice-pulse 1.5s ease-in-out infinite;
   }
   #btn-voice.continuous {
     background: #600;
     border-color: #f44;
     animation: voice-pulse 1.5s ease-in-out infinite;
   }")

(defn render-html
  "Render voice status panel as HTML string."
  []
  (let [st @state/voice-state
        mode (:mode st)
        sub-mode (:sub-mode st)
        voice (:voice st)
        listening (:listening? voice)
        continuous (:continuous? voice)
        transcript (:partial-transcript voice)
        pending (:pending-speech voice)
        last-utt (:last-utterance voice)]
    (str
     "<div class='voice-header'>"
     "<span>" (if listening "&#x1F534;" "&#x26AB;") "</span>"
     (when continuous " <span class='voice-continuous'>CONTINUOUS</span>")
     (when (and listening (not continuous)) " <span class='voice-listening'>MIC</span>")
     " <span class='voice-mode'>" (str/upper-case (name mode)) "</span>"
     (when sub-mode
       (str " <span class='voice-sub-mode'>[" (name sub-mode) "]</span>"))
     "</div>"
     (when (and transcript (seq transcript))
       (str "<div class='voice-transcript'>\"" transcript "...\"</div>"))
     (when pending
       (str "<div class='voice-pending'>" pending "</div>"))
     (when last-utt
       (str "<div class='voice-last'>Last: \"" last-utt "\"</div>")))))
