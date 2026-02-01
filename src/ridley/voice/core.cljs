(ns ridley.voice.core
  "Main voice input orchestrator.
   Routes speech-to-text through deterministic parser, dispatches to editor handlers.
   Supports single utterance (push-to-talk) and continuous listening modes."
  (:require [clojure.string :as str]
            [ridley.voice.state :as state]
            [ridley.voice.speech :as speech]
            [ridley.voice.parser :as parser]
            [ridley.voice.i18n :as i18n]
            [ridley.voice.panel :as panel]))

;; Handler callbacks (set by core.cljs to avoid circular deps)
(defonce ^:private handlers (atom {}))

(declare stop-continuous-listening!)
(declare start-continuous-listening!)

(def ^:private dictation-exit-words
  "Words that exit dictation mode instead of being inserted."
  {:it #{"basta" "stop" "esci" "fine dettatura"}
   :en #{"stop" "exit" "end dictation"}})

(defn- dictation-exit? [text lang]
  (let [exits (get dictation-exit-words lang #{})
        trimmed (str/trim (str/lower-case text))]
    (contains? exits trimmed)))

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
          ;; Restart continuous recognition with new language
          (when (speech/continuous?)
            (speech/abort!)
            (js/setTimeout #(start-continuous-listening!) 400))
          (when speak (speak (case new-lang :it "Italiano" :en "English" (name new-lang)))))

        ;; Meta
        :undo (when undo (undo {:operation :undo :count (or (:count params) 1)}))
        :redo (when undo (undo {:operation :redo :count (or (:count params) 1)}))
        :run  (when execute (execute {:target :script}))
        :stop (do (stop-continuous-listening!)
                  (when speak (speak "Stop")))

        ;; Structure mode: navigation
        :navigate
        (when navigate
          (let [{:keys [direction count]} params
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
                            "count:" (or count 1))
            (navigate {:direction struct-dir :mode :structure :count (or count 1)})))

        ;; Goto line
        :goto-line
        (when navigate
          (navigate {:direction :goto-line :line (:line params)}))

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
        :slurp    (when edit (edit {:operation :slurp :target {:type "form"}}))
        :barf     (when edit (edit {:operation :barf :target {:type "form"}}))
        :wrap     (when edit
                    (let [head (:head params)
                          tmpl (if head (str "(" head " $)") "($ )")]
                      (edit {:operation :wrap :target {:type "form"} :value tmpl})))
        :unwrap   (when edit (edit {:operation :unwrap :target {:type "form"}}))
        :raise    (when edit (edit {:operation :raise :target {:type "form"}}))
        :join     (when edit (edit {:operation :join :target {:type "form"}}))

        ;; Transforms (keyword, symbol, hash, deref, capitalize, uppercase, number)
        :transform
        (when edit
          (edit {:operation :transform :target {:type "form"}
                 :transform-type (:type params)}))

        ;; Insertion
        :insert
        (when insert
          (let [{:keys [text position]} params]
            (insert {:target :script :code text
                     :position (case position
                                 :before :before-current-form
                                 ;; Default: insert as child of current form
                                 :append-child)})))

        :append
        (when insert
          (insert {:target :script :code (:text params) :position :append-child}))

        :new-form
        (when insert
          (let [head (:head params)
                code (if head (str "(" head " )") "()")
                pos (:position params)]
            (insert {:target :script :code code
                     :position (case pos
                                 :before :before-current-form
                                 :append-child :append-child
                                 :after-current-form)})))

        :new-list
        (when insert
          (let [items (:items params)
                code (if items (str "[" (str/join " " items) "]") "[]")
                pos (:position params)]
            (insert {:target :script :code code
                     :position (case pos
                                 :append-child :append-child
                                 :after-current-form)})))

        :new-map
        (when insert
          (let [head (:head params)
                code (if head (str "{" head " }") "{}")
                pos (:position params)]
            (insert {:target :script :code code
                     :position (case pos
                                 :append-child :append-child
                                 :after-current-form)})))

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
      ;; Dictation mode: insert text literally, or exit on stop/esci/basta
      (= sub-mode :dictation)
      (let [text (str/trim transcript)]
        (cond
          (empty? text)
          (do (state/clear-sub-mode!)
              (js/console.log "Exiting dictation mode (empty)"))
          (dictation-exit? text lang)
          (do (state/clear-sub-mode!)
              (js/console.log "Exiting dictation mode"))
          :else
          (when-let [insert (:insert @handlers)]
            (insert {:target :script :code text :position :at-cursor}))))

      ;; AI mode: LLM passthrough (future)
      (= mode :ai)
      (js/console.log "AI mode — LLM not connected:" transcript)

      ;; Normal: parse and execute
      :else
      (if-let [cmd (parser/parse-command transcript mode lang :sub-mode sub-mode)]
        (do
          (js/console.log "Parsed:" (pr-str cmd))
          (execute-action cmd))
        (do
          (js/console.warn "Voice: unrecognized command:" transcript)
          (state/update-voice! {:pending-speech (str "? " transcript)}))))))

;; ============================================================
;; Continuous mode — command splitting
;; ============================================================

(defn- split-on-delimiters
  "Split text on delimiter words (poi, then, vai, go, etc.).
   Returns a vector of command strings."
  [text lang]
  (let [delims (get i18n/command-delimiters lang #{})
        ;; Build regex from delimiter set, sorted longest first
        sorted-delims (sort-by count > (seq delims))
        pattern (re-pattern (str "(?i)\\b(?:" (str/join "|" (map #(str/replace % #" " "\\\\s+") sorted-delims)) ")\\b"))]
    (->> (str/split text pattern)
         (map str/trim)
         (remove empty?)
         vec)))

(defn- process-command-text
  "Parse and execute a single command text segment."
  [text]
  (let [mode (state/get-mode)
        sub-mode (state/get-sub-mode)
        lang (state/get-language)]
    (cond
      (= sub-mode :dictation)
      (let [trimmed (str/trim text)]
        (cond
          (empty? trimmed) nil
          (dictation-exit? trimmed lang)
          (do (state/clear-sub-mode!)
              (js/console.log "Exiting dictation mode"))
          :else
          (when-let [insert (:insert @handlers)]
            (insert {:target :script :code trimmed :position :at-cursor}))))

      (= mode :ai)
      (js/console.log "AI mode — LLM not connected:" text)

      :else
      (if-let [cmd (parser/parse-command text mode lang :sub-mode sub-mode)]
        (do
          (js/console.log "Parsed:" (pr-str cmd))
          (execute-action cmd))
        (do
          (js/console.warn "Voice: unrecognized command:" text)
          (state/update-voice! {:pending-speech (str "? " text)}))))))


(defn- on-continuous-event
  "Handle events from continuous speech recognition.
   Each finalized utterance is processed immediately — split on delimiters
   and each segment executed as a command. No cross-utterance buffering."
  [{:keys [type text]}]
  (case type
    :utterance
    (do
      (js/console.log "Continuous utterance:" text)
      (let [lang (state/get-language)
            segments (split-on-delimiters text lang)]
        ;; If no delimiters were found, segments is just [text]
        (doseq [seg segments]
          (process-command-text seg))))

    :silence nil ;; No action needed — utterances are processed immediately

    (js/console.warn "Continuous: unknown event type" type)))

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

(defn start-continuous-listening! []
  (state/enable!)
  (speech/start-continuous! on-continuous-event))

(defn stop-continuous-listening! []
  (speech/stop!))

(defn continuous-active? []
  (speech/continuous?))

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
