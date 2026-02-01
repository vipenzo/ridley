(ns ridley.voice.state
  "Global state for voice input system.
   All voice components read from this atom.")

(defonce voice-enabled? (atom true))

(defonce voice-state
  (atom {:mode :structure          ; :structure :turtle :help :ai
         :sub-mode nil             ; :selection :dictation nil
         :language :it             ; :it :en

         :voice
         {:listening? false
          :partial-transcript ""
          :pending-speech nil
          :last-utterance nil}

         ;; Editor context (synced from core.cljs)
         :buffer {:script "" :repl ""}
         :cursor {:target :script :line 1 :col 0
                  :current-form nil :parent-form nil}
         :selection nil
         :scene {:meshes [] :visible [] :shapes [] :paths []}}))

;; Enable/disable

(defn enable! [] (reset! voice-enabled? true))
(defn disable! [] (reset! voice-enabled? false))
(defn enabled? [] @voice-enabled?)

;; Mode management

(defn set-mode! [mode]
  (swap! voice-state assoc :mode mode :sub-mode nil))

(defn set-sub-mode! [sub-mode]
  (swap! voice-state assoc :sub-mode sub-mode))

(defn clear-sub-mode! []
  (swap! voice-state assoc :sub-mode nil))

(defn get-mode [] (:mode @voice-state))
(defn get-sub-mode [] (:sub-mode @voice-state))

;; Language

(defn set-language! [lang]
  (swap! voice-state assoc :language lang))

(defn get-language [] (:language @voice-state))

;; Voice state

(defn update-voice! [voice-data]
  (swap! voice-state update :voice merge voice-data))

;; Editor context (synced from core.cljs)

(defn update-buffer! [target content]
  (swap! voice-state assoc-in [:buffer target] content))

(defn update-cursor! [cursor-data]
  (swap! voice-state update :cursor merge cursor-data))

(defn update-scene! [scene-data]
  (swap! voice-state update :scene merge scene-data))

(defn get-state [] @voice-state)
