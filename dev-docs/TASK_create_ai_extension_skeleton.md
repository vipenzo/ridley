# Task: Create AI Voice Extension Skeleton

## Overview

Create the foundation for Ridley's AI voice extension. This is a non-destructive addition — the feature is disabled by default and doesn't affect existing functionality.

## Files to Create

### 1. `src/ridley/ai/state.cljs` — Shared State

```clojure
(ns ridley.ai.state
  "Shared state for AI voice extension.
   All components read from this atom, only specific writers modify it.")

(defonce ai-enabled? (atom false))

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
```

### 2. `src/ridley/ai/voice.cljs` — Web Speech API

```clojure
(ns ridley.ai.voice
  "Web Speech API integration for voice input."
  (:require [ridley.ai.state :as state]))

(defonce ^:private recognition (atom nil))

(defn supported? []
  (or (.-webkitSpeechRecognition js/window)
      (.-SpeechRecognition js/window)))

(defn- create-recognition []
  (let [SpeechRecognition (or (.-webkitSpeechRecognition js/window)
                              (.-SpeechRecognition js/window))]
    (when SpeechRecognition
      (let [rec (SpeechRecognition.)]
        (set! (.-continuous rec) true)
        (set! (.-interimResults rec) true)
        (set! (.-lang rec) "it-IT")
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
                            :partial-transcript ""})
      (callback transcript))))

(defn- on-error [event]
  (js/console.error "Speech recognition error:" (.-error event))
  (state/update-voice! {:listening? false}))

(defn- on-end []
  ;; Auto-restart if still enabled
  (when (and (state/enabled?) (:listening? (:voice @state/ai-state)))
    (when-let [rec @recognition]
      (.start rec))))

(defn start! [on-utterance]
  "Start listening. Calls on-utterance with final transcript string."
  (when (supported?)
    (when-not @recognition
      (reset! recognition (create-recognition)))
    (when-let [rec @recognition]
      (set! (.-onresult rec) (partial on-result on-utterance))
      (set! (.-onerror rec) on-error)
      (set! (.-onend rec) on-end)
      (.start rec)
      (state/update-voice! {:listening? true}))))

(defn stop! []
  "Stop listening."
  (when-let [rec @recognition]
    (.stop rec)
    (state/update-voice! {:listening? false
                          :partial-transcript ""})))

(defn toggle! [on-utterance]
  "Toggle listening state."
  (if (:listening? (:voice @state/ai-state))
    (stop!)
    (start! on-utterance)))
```

### 3. `src/ridley/ai/llm.cljs` — LLM Integration

```clojure
(ns ridley.ai.llm
  "LLM integration for converting voice to actions."
  (:require [ridley.ai.state :as state]))

(def ^:private config
  (atom {:provider :ollama
         :model "llama3.2:3b"
         :endpoint "http://localhost:11434"
         :tier :basic}))

(defn configure! [opts]
  (swap! config merge opts))

(def ^:private system-prompt
  "You are a voice command interpreter for Ridley, a 3D CAD tool. Convert Italian commands to JSON actions.

Output ONLY valid JSON. If unclear: {\"action\": \"speak\", \"text\": \"Non ho capito\"}

## Commands

Movement:
- \"vai avanti di 20\" → {\"action\": \"insert\", \"target\": \"script\", \"code\": \"(f 20)\", \"position\": \"after-current-form\"}
- \"gira a destra di 45\" → {\"action\": \"insert\", \"target\": \"script\", \"code\": \"(th -45)\", \"position\": \"after-current-form\"}
- \"gira a sinistra di 90\" → {\"action\": \"insert\", \"target\": \"script\", \"code\": \"(th 90)\", \"position\": \"after-current-form\"}
- \"alza di 30\" → {\"action\": \"insert\", \"target\": \"script\", \"code\": \"(tv 30)\", \"position\": \"after-current-form\"}

Primitives:
- \"crea un cubo di 30\" → {\"action\": \"insert\", \"target\": \"script\", \"code\": \"(box 30)\", \"position\": \"after-current-form\"}
- \"crea una sfera raggio 15\" → {\"action\": \"insert\", \"target\": \"script\", \"code\": \"(sphere 15)\", \"position\": \"after-current-form\"}

Shapes:
- \"cerchio raggio 5\" → {\"action\": \"insert\", \"target\": \"script\", \"code\": \"(circle 5)\", \"position\": \"after-current-form\"}

Extrusion:
- \"estrudi cerchio 5 di 30\" → {\"action\": \"insert\", \"target\": \"script\", \"code\": \"(extrude (circle 5) (f 30))\", \"position\": \"after-current-form\"}

Register:
- \"registra come pippo\" → {\"action\": \"edit\", \"operation\": \"wrap\", \"target\": {\"type\": \"form\"}, \"value\": \"(register pippo $)\"}

Navigation:
- \"prossimo\" → {\"action\": \"navigate\", \"direction\": \"next\", \"mode\": \"structure\"}
- \"precedente\" → {\"action\": \"navigate\", \"direction\": \"prev\", \"mode\": \"structure\"}
- \"entra\" → {\"action\": \"navigate\", \"direction\": \"child\", \"mode\": \"structure\"}
- \"esci\" → {\"action\": \"navigate\", \"direction\": \"parent\", \"mode\": \"structure\"}

Editing:
- \"cambia in 30\" → {\"action\": \"edit\", \"operation\": \"replace\", \"target\": {\"type\": \"word\"}, \"value\": \"30\"}
- \"cancella\" → {\"action\": \"edit\", \"operation\": \"delete\", \"target\": {\"type\": \"form\"}}

Mode:
- \"modalità testo\" → {\"action\": \"mode\", \"set\": \"text\"}
- \"modalità struttura\" → {\"action\": \"mode\", \"set\": \"structure\"}

Execute:
- \"esegui\" → {\"action\": \"execute\", \"target\": \"script\"}

REPL:
- \"pippo è manifold\" → {\"actions\": [{\"action\": \"insert\", \"target\": \"repl\", \"code\": \"(manifold? pippo)\"}, {\"action\": \"execute\", \"target\": \"repl\"}]}
- \"nascondi pippo\" → {\"actions\": [{\"action\": \"insert\", \"target\": \"repl\", \"code\": \"(hide pippo)\"}, {\"action\": \"execute\", \"target\": \"repl\"}]}")

(defn- build-context []
  (let [st (state/get-state)]
    {:mode (:mode st)
     :target (get-in st [:cursor :target])
     :cursor {:line (get-in st [:cursor :line])
              :col (get-in st [:cursor :col])
              :current_form (get-in st [:cursor :current-form])
              :parent_form (get-in st [:cursor :parent-form])}
     :scene (:scene st)
     :last_repl_result (get-in st [:repl :last-result])}))

(defn- call-ollama [utterance callback]
  (let [context (build-context)
        body (clj->js {:model (:model @config)
                       :messages [{:role "system" :content system-prompt}
                                  {:role "user" :content (str "Context: " (pr-str context) "\n\nCommand: " utterance)}]
                       :stream false
                       :format "json"})]
    (-> (js/fetch (str (:endpoint @config) "/api/chat")
                  (clj->js {:method "POST"
                            :headers {"Content-Type" "application/json"}
                            :body (js/JSON.stringify body)}))
        (.then #(.json %))
        (.then (fn [response]
                 (let [content (.-content (.-message response))]
                   (try
                     (callback (js->clj (js/JSON.parse content) :keywordize-keys true))
                     (catch :default e
                       (js/console.error "Failed to parse LLM response:" content)
                       (callback {:action "speak" :text "Errore di parsing"}))))))
        (.catch (fn [err]
                  (js/console.error "LLM call failed:" err)
                  (callback {:action "speak" :text "Errore di connessione"}))))))

(defn process-utterance [utterance callback]
  "Process a voice utterance through the LLM. Calls callback with parsed action(s)."
  (case (:provider @config)
    :ollama (call-ollama utterance callback)
    ;; Future: :anthropic, :openai
    (callback {:action "speak" :text "Provider non configurato"})))
```

### 4. `src/ridley/ai/actions.cljs` — Action Executor

```clojure
(ns ridley.ai.actions
  "Execute actions returned by LLM."
  (:require [ridley.ai.state :as state]))

;; These will be set by core.cljs to avoid circular deps
(defonce ^:private editor-insert! (atom nil))
(defonce ^:private editor-edit! (atom nil))
(defonce ^:private editor-navigate! (atom nil))
(defonce ^:private editor-execute! (atom nil))
(defonce ^:private speak! (atom nil))

(defn set-handlers!
  "Set handler functions from core.cljs"
  [{:keys [insert edit navigate execute speak]}]
  (reset! editor-insert! insert)
  (reset! editor-edit! edit)
  (reset! editor-navigate! navigate)
  (reset! editor-execute! execute)
  (reset! speak! speak))

(defmulti execute-action :action)

(defmethod execute-action "insert" [{:keys [target code position]}]
  (when @editor-insert!
    (@editor-insert! {:target (keyword target)
                      :code code
                      :position (keyword position)})))

(defmethod execute-action "edit" [{:keys [operation target value]}]
  (when @editor-edit!
    (@editor-edit! {:operation (keyword operation)
                    :target target
                    :value value})))

(defmethod execute-action "navigate" [{:keys [direction mode count]}]
  (when @editor-navigate!
    (@editor-navigate! {:direction (keyword direction)
                        :mode (keyword mode)
                        :count (or count 1)})))

(defmethod execute-action "mode" [{:keys [set]}]
  (state/set-mode! (keyword set)))

(defmethod execute-action "target" [{:keys [set]}]
  (state/update-cursor! {:target (keyword set)}))

(defmethod execute-action "execute" [{:keys [target]}]
  (when @editor-execute!
    (@editor-execute! {:target (keyword target)})))

(defmethod execute-action "select" [{:keys [what extend]}]
  ;; TODO: implement selection
  nil)

(defmethod execute-action "speak" [{:keys [text]}]
  (when @speak!
    (@speak! text))
  (state/update-voice! {:pending-speech text}))

(defmethod execute-action :default [action]
  (js/console.warn "Unknown action:" (pr-str action)))

(defn execute! [action-or-actions]
  "Execute one action or a vector of actions."
  (let [actions (if (contains? action-or-actions :actions)
                  (:actions action-or-actions)
                  [action-or-actions])]
    (doseq [action actions]
      (execute-action action))))
```

### 5. `src/ridley/ai/tts.cljs` — Text-to-Speech (optional)

```clojure
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
```

### 6. `src/ridley/ai/panel.cljs` — Debug/Status Panel

```clojure
(ns ridley.ai.panel
  "AI status panel component - shows shared state for debugging.
   Can be rendered in DOM (desktop) or as Three.js text panel (VR)."
  (:require [ridley.ai.state :as state]))

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
     "│ Meshes: " (if (seq meshes) (clojure.string/join ", " (map name meshes)) "(none)") "\n"
     "│ Shapes: " (if (seq shapes) (clojure.string/join ", " (map name shapes)) "(none)") "\n"
     "│ Paths:  " (if (seq paths) (clojure.string/join ", " (map name paths)) "(none)") "\n"
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
      [:div "Meshes: " (if (seq meshes) (clojure.string/join ", " (map name meshes)) "—")]
      [:div "Shapes: " (if (seq shapes) (clojure.string/join ", " (map name shapes)) "—")]
      [:div "Paths: " (if (seq paths) (clojure.string/join ", " (map name paths)) "—")]
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
```

### 7. `src/ridley/ai/core.cljs` — Main Integration

```clojure
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
  (llm/process-utterance
   transcript
   (fn [action]
     (js/console.log "LLM action:" (pr-str action))
     (actions/execute! action))))

(defn init!
  "Initialize AI extension with editor handlers.
   handlers: {:insert fn, :edit fn, :navigate fn, :execute fn}"
  [handlers]
  (actions/set-handlers! (assoc handlers :speak tts/speak!))
  (js/console.log "AI extension initialized"))

(defn toggle-voice! []
  "Toggle voice input on/off."
  (if (state/enabled?)
    (do
      (voice/stop!)
      (state/disable!))
    (do
      (state/enable!)
      (voice/start! on-utterance))))

(defn voice-active? []
  (:listening? (:voice @state/ai-state)))

(defn get-transcript []
  "Get current partial transcript for UI display."
  (get-in @state/ai-state [:voice :partial-transcript]))

(defn get-pending-speech []
  "Get pending speech text for UI display."
  (get-in @state/ai-state [:voice :pending-speech]))

;; Expose for REPL/debugging
(defn status []
  {:enabled (state/enabled?)
   :listening (voice-active?)
   :transcript (get-transcript)
   :mode (:mode @state/ai-state)})

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
```

## Files to Modify

### `src/ridley/core.cljs` — Add AI toggle button and panel

Add to the UI (near other toolbar buttons):

```clojure
;; Import at top
[ridley.ai.core :as ai]

;; In init or setup function, initialize AI with editor handlers
(ai/init! {:insert (fn [{:keys [target code position]}]
                     ;; Insert code into editor
                     ;; Implementation depends on your editor integration
                     )
           :edit (fn [{:keys [operation target value]}]
                   ;; Edit operation
                   )
           :navigate (fn [{:keys [direction mode count]}]
                       ;; Navigation
                       )
           :execute (fn [{:keys [target]}]
                      ;; Execute script or REPL
                      )})

;; Add button to toolbar (pseudo-code, adapt to your UI)
[:button {:class (if (ai/voice-active?) "active" "")
          :on-click ai/toggle-voice!}
 "🎤"]

;; Show AI status panel (can be toggled or always visible during dev)
;; Option 1: As HTML in the DOM
(when (ai/voice-active?)
  (ai/render-panel-html))

;; Option 2: Print to console for debugging
(js/console.log (ai/render-panel-text))
```

### `public/css/style.css` — Add AI panel styles

Append the CSS from `panel/panel-css` or copy it directly:

```css
/* AI Panel Styles */
.ai-panel {
  font-family: 'SF Mono', Monaco, monospace;
  font-size: 12px;
  background: #1a1a2e;
  color: #eee;
  padding: 8px;
  border-radius: 4px;
  border: 1px solid #333;
  min-width: 280px;
  position: fixed;
  bottom: 10px;
  right: 10px;
  z-index: 1000;
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
```
```

## Testing

1. Start Ollama with a small model:
   ```bash
   ollama run llama3.2:3b
   ```

2. Open Ridley, click the 🎤 button

3. Say "vai avanti di venti"

4. Should insert `(f 20)` into the script

## Notes

- Web Speech API requires HTTPS or localhost
- Ollama must be running on localhost:11434
- The system prompt is intentionally minimal for small models
- Error handling is basic — improve as needed
- The action handlers in core.cljs need to be implemented based on your editor API
