(ns ridley.ai.state
  "Shared state for AI voice extension.
   All components read from this atom, only specific writers modify it.")

(defonce ai-enabled? (atom true))

(defonce ai-state
  (atom
    {:buffer
     {:script ""
      :repl ""}

     :cursor
     {:target :script
      :line 1
      :col 0
      :form-path []
      :current-form nil
      :parent-form nil}

     :selection nil

     :mode :structure  ; :structure | :text | :dictation

     :scene
     {:meshes []
      :visible []
      :shapes []
      :paths []
      :last-mentioned nil}

     :repl
     {:last-input nil
      :last-result nil
      :history []}

     :voice
     {:listening? false
      :partial-transcript ""
      :pending-speech nil
      :last-utterance nil}}))

(defn enable! []
  (reset! ai-enabled? true))

(defn disable! []
  (reset! ai-enabled? false))

(defn enabled? []
  @ai-enabled?)

;; State update functions

(defn update-buffer! [target content]
  (swap! ai-state assoc-in [:buffer target] content))

(defn update-cursor! [cursor-data]
  (swap! ai-state update :cursor merge cursor-data))

(defn update-scene! [scene-data]
  (swap! ai-state update :scene merge scene-data))

(defn update-voice! [voice-data]
  (swap! ai-state update :voice merge voice-data))

(defn set-mode! [mode]
  (swap! ai-state assoc :mode mode))

(defn set-last-mentioned! [name]
  (swap! ai-state assoc-in [:scene :last-mentioned] name))

(defn get-state []
  @ai-state)
