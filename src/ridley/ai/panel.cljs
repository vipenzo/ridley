(ns ridley.ai.panel
  "AI status panel component - shows shared state for debugging.
   Can be rendered in DOM (desktop) or as Three.js text panel (VR)."
  (:require [clojure.string :as str]
            [ridley.ai.state :as state]))

(defn format-state
  "Format AI state for display."
  []
  (let [st @state/ai-state
        voice (:voice st)
        cursor (:cursor st)
        scene (:scene st)]
    {:enabled (state/enabled?)
     :listening (:listening? voice)
     :mode (:mode st)
     :target (:target cursor)
     :current-form (:current-form cursor)
     :transcript (or (:partial-transcript voice) "")
     :last-utterance (:last-utterance voice)
     :pending-speech (:pending-speech voice)
     :meshes (:meshes scene)
     :shapes (:shapes scene)
     :paths (:paths scene)
     :last-mentioned (:last-mentioned scene)}))

(defn render-text
  "Render state as plain text (for text panel or console)."
  []
  (let [{:keys [enabled listening mode target current-form
                transcript last-utterance pending-speech
                meshes shapes paths]} (format-state)]
    (str
     "┌─ AI Status ─────────────────────┐\n"
     "│ " (if enabled "🟢 ENABLED" "⚫ DISABLED")
     (when listening " 🎤 LISTENING") "\n"
     "│ Mode: " (name mode) " | Target: " (name target) "\n"
     "├─ Cursor ────────────────────────┤\n"
     "│ " (or current-form "(no form)") "\n"
     "├─ Voice ─────────────────────────┤\n"
     (when (seq transcript)
       (str "│ 🎤 \"" transcript "...\"\n"))
     (when last-utterance
       (str "│ Last: \"" last-utterance "\"\n"))
     (when pending-speech
       (str "│ 🔊 \"" pending-speech "\"\n"))
     "├─ Scene ─────────────────────────┤\n"
     "│ Meshes: " (if (seq meshes) (str/join ", " (map name meshes)) "(none)") "\n"
     "│ Shapes: " (if (seq shapes) (str/join ", " (map name shapes)) "(none)") "\n"
     "│ Paths:  " (if (seq paths) (str/join ", " (map name paths)) "(none)") "\n"
     "└─────────────────────────────────┘")))

(defn render-html
  "Render state as hiccup-style HTML structure."
  []
  (let [{:keys [enabled listening mode target current-form
                transcript last-utterance pending-speech
                meshes shapes paths last-mentioned]} (format-state)]
    [:div.ai-panel
     [:div.ai-header
      [:span.status (if enabled "🟢" "⚫")]
      (when listening [:span.listening "🎤"])
      [:span.mode (str "[" (name mode) "]")]
      [:span.target (name target)]]

     [:div.ai-cursor
      [:code (or current-form "(no form)")]]

     (when (or (seq transcript) last-utterance pending-speech)
       [:div.ai-voice
        (when (seq transcript)
          [:div.transcript "🎤 \"" transcript "...\""])
        (when last-utterance
          [:div.last-utterance "Last: \"" last-utterance "\""])
        (when pending-speech
          [:div.pending-speech "🔊 \"" pending-speech "\""])])

     [:div.ai-scene
      [:div "Meshes: " (if (seq meshes) (str/join ", " (map name meshes)) "—")]
      [:div "Shapes: " (if (seq shapes) (str/join ", " (map name shapes)) "—")]
      [:div "Paths: " (if (seq paths) (str/join ", " (map name paths)) "—")]
      (when last-mentioned
        [:div "Last: " (name last-mentioned)])]]))

;; CSS for the panel (add to style.css)
(def panel-css
  "
.ai-panel {
  font-family: 'SF Mono', Monaco, monospace;
  font-size: 12px;
  background: #1a1a2e;
  color: #eee;
  padding: 8px;
  border-radius: 4px;
  border: 1px solid #333;
  min-width: 280px;
}
.ai-header {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  padding-bottom: 4px;
  border-bottom: 1px solid #333;
}
.ai-header .status { font-size: 10px; }
.ai-header .listening { animation: pulse 1s infinite; }
.ai-header .mode { color: #88f; }
.ai-header .target { color: #8f8; }
.ai-cursor code {
  display: block;
  background: #252540;
  padding: 4px 8px;
  border-radius: 2px;
  color: #ff8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 260px;
}
.ai-voice {
  margin: 8px 0;
  padding: 4px;
  background: #252540;
  border-radius: 2px;
}
.ai-voice .transcript { color: #8ff; }
.ai-voice .last-utterance { color: #888; font-size: 11px; }
.ai-voice .pending-speech { color: #f88; }
.ai-scene {
  font-size: 11px;
  color: #aaa;
}
.ai-scene div { margin: 2px 0; }
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}
")
