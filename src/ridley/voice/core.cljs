(ns ridley.voice.core
  "Main voice input orchestrator.
   Routes speech-to-text through deterministic parser, dispatches to editor handlers."
  (:require [clojure.string :as str]
            [ridley.voice.state :as state]
            [ridley.voice.speech :as speech]
            [ridley.voice.parser :as parser]
            [ridley.voice.panel :as panel]))

;; Handler callbacks (set by core.cljs to avoid circular deps)
(defonce ^:private handlers (atom {}))

;; ============================================================
;; Action execution
;; ============================================================

(defn- execute-action
  "Execute a parsed command by dispatching to the appropriate handler."
  [{:keys [action params]}]
  (when action
    (let [{:keys [insert edit navigate execute undo speak
                  copy-fn cut-fn paste-fn select-fn]} @handlers]
      (case action
        ;; Mode switching
        :mode-switch
        (let [new-mode (:mode params)]
          (state/set-mode! new-mode)
          (when speak (speak (str "Modo " (name new-mode)))))

        ;; Language switching
        :language-switch
        (let [new-lang (:language params)]
          (state/set-language! new-lang)
          (js/console.log "Voice language switched to:" (name new-lang))
          (when speak (speak (case new-lang :it "Italiano" :en "English" (name new-lang)))))

        ;; Meta
        :undo (when undo (undo {:operation :undo :count (or (:count params) 1)}))
        :redo (when undo (undo {:operation :redo :count (or (:count params) 1)}))
        :run  (when execute (execute {:target :script}))

        ;; Structure mode: navigation
        :navigate
        (when navigate
          (let [{:keys [direction count unit]} params
                mode (state/get-mode)
                ;; Translate i18n direction keys to ai-navigate keys
                struct-dir (case direction
                             :into :child
                             :out :parent
                             :first :start
                             :last :end
                             :top :start
                             :bottom :end
                             ;; :next, :previous, :prev pass through
                             :previous :prev
                             direction)]
            (js/console.log "Voice navigate:" (name direction) "→" (name struct-dir)
                            "mode:" (name mode) "count:" (or count 1))
            (case mode
              :structure
              (navigate {:direction struct-dir :mode :structure :count (or count 1)})
              :text
              (navigate {:direction direction :mode :text :count (or count 1) :unit (or unit :char)})
              ;; Default
              (navigate {:direction struct-dir :mode :structure :count (or count 1)}))))

        ;; Goto line (text mode)
        :goto-line
        (when navigate
          (navigate {:direction :goto-line :line (:line params)}))

        ;; Find (text mode)
        :find
        (when navigate
          (navigate {:direction :find :query (:query params)}))

        ;; Structure mode: editing
        :delete
        (when edit
          (edit {:operation :delete :target {:type "form"} :unit (:unit params)}))

        :copy
        (when copy-fn (copy-fn (:unit params)))

        :cut
        (when cut-fn (cut-fn (:unit params)))

        :paste
        (when paste-fn (paste-fn (:position params)))

        :change
        (when edit
          (edit {:operation :replace :target {:type "word"} :value (:value params)}))

        ;; Structural operations
        :slurp  (when edit (edit {:operation :slurp :target {:type "form"}}))
        :barf   (when edit (edit {:operation :barf :target {:type "form"}}))
        :wrap   (when edit (edit {:operation :wrap :target {:type "form"} :value "($ )"}))
        :unwrap (when edit (edit {:operation :unwrap :target {:type "form"}}))
        :raise  (when edit (edit {:operation :raise :target {:type "form"}}))

        ;; Insertion
        :insert
        (when insert
          (let [{:keys [text position]} params]
            (insert {:target :script :code text
                     :position (if (= position :before) :before-current-form :after-current-form)})))

        :append
        (when insert
          (insert {:target :script :code (:text params) :position :append-child}))

        :new-form
        (when insert
          (let [head (:head params)
                code (if head (str "(" head " )") "()")
                pos (:position params)]
            (insert {:target :script :code code
                     :position (if (= pos :before) :before-current-form :after-current-form)})))

        :new-list
        (when insert
          (let [items (:items params)
                code (if items (str "[" (str/join " " items) "]") "[]")]
            (insert {:target :script :code code :position :after-current-form})))

        :new-map
        (when insert
          (let [head (:head params)
                code (if head (str "{" head " }") "{}")]
            (insert {:target :script :code code :position :after-current-form})))

        ;; Selection
        :selection-enter
        (do (state/set-sub-mode! :selection)
            (when speak (speak "Selezione")))

        :selection-exit
        (do (state/clear-sub-mode!)
            (when select-fn (select-fn :cancel)))

        :select-immediate
        (when select-fn (select-fn (:target params)))

        ;; Dictation
        :dictation-enter
        (do (state/set-sub-mode! :dictation)
            (when speak (speak "Dettatura")))

        ;; Default
        (js/console.warn "Voice: unhandled action" (pr-str action))))))

;; ============================================================
;; Utterance handling
;; ============================================================

(defn- on-utterance [transcript]
  (js/console.log "Voice input:" transcript)
  (let [mode (state/get-mode)
        sub-mode (state/get-sub-mode)
        lang (state/get-language)]

    (cond
      ;; Dictation mode: insert text literally, or exit on empty
      (= sub-mode :dictation)
      (let [text (str/trim transcript)]
        (if (empty? text)
          ;; Empty input = exit dictation
          (do (state/clear-sub-mode!)
              (js/console.log "Exiting dictation mode"))
          ;; Insert literal text
          (when-let [insert (:insert @handlers)]
            (insert {:target :script :code text :position :at-cursor}))))

      ;; AI mode: log (LLM passthrough — future implementation)
      (= mode :ai)
      (js/console.log "AI mode — LLM not connected:" transcript)

      ;; Normal: parse and execute
      :else
      (if-let [cmd (parser/parse-command transcript mode lang)]
        (do
          (js/console.log "Parsed:" (pr-str cmd))
          (execute-action cmd))
        (do
          (js/console.warn "Voice: unrecognized command:" transcript)
          (state/update-voice! {:pending-speech (str "? " transcript)}))))))

;; ============================================================
;; Public API
;; ============================================================

(defn init!
  "Initialize voice input with editor handler callbacks.
   handlers map:
     :insert   fn [{:keys [target code position]}]
     :edit     fn [{:keys [operation target value element]}]
     :navigate fn [{:keys [direction mode count unit]}]
     :execute  fn [{:keys [target]}]
     :undo     fn [{:keys [operation count]}]
     :speak    fn [text] (optional)
     :copy-fn  fn [unit] (optional)
     :cut-fn   fn [unit] (optional)
     :paste-fn fn [position] (optional)
     :select-fn fn [target] (optional)"
  [handler-map]
  (reset! handlers handler-map)
  (js/console.log "Voice input system initialized"))

(defn start-listening! []
  (state/enable!)
  (speech/start! on-utterance))

(defn stop-listening! []
  (speech/stop!))

(defn cancel-listening! []
  (speech/abort!))

(defn toggle-voice! []
  (if (speech/listening?)
    (stop-listening!)
    (start-listening!)))

(defn voice-active? []
  (speech/listening?))

(defn enabled? []
  (state/enabled?))

(defn get-transcript []
  (get-in @state/voice-state [:voice :partial-transcript]))

(defn get-pending-speech []
  (get-in @state/voice-state [:voice :pending-speech]))

(defn status []
  {:enabled (state/enabled?)
   :listening (voice-active?)
   :transcript (get-transcript)
   :pending-speech (get-pending-speech)
   :mode (state/get-mode)
   :sub-mode (state/get-sub-mode)
   :language (state/get-language)})

;; Panel rendering
(defn render-panel-html [] (panel/render-html))
(defn get-panel-css [] panel/panel-css)
