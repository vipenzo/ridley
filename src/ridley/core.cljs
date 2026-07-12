(ns ridley.core
  "Main entry point for Ridley application."
  (:require [clojure.string :as str]
            [ridley.editor.repl :as repl]
            [ridley.editor.state :as editor-state]
            [ridley.editor.codemirror :as cm]
            [ridley.viewport.core :as viewport]
            [ridley.viewport.xr :as xr]
            [ridley.manifold.core :as manifold]
            [ridley.turtle.text :as text]
            [ridley.fonts.core :as fonts]
            [ridley.fonts.storage :as fonts-storage]
            [ridley.scene.registry :as registry]
            [ridley.env :as env]
            [ridley.export.stl :as stl]
            [ridley.sync.peer :as sync]
            [ridley.manual.core :as manual]
            [ridley.manual.components :as manual-ui]
            [ridley.manual.draft-renderer :as manual-draft]
            [ridley.manual.reference-browser :as ref-browser]
            [ridley.voice.core :as voice]
            [ridley.voice.state :as voice-state]
            [ridley.voice.i18n :as voice-i18n]
            [ridley.voice.help.db :as help-db]
            [ridley.settings :as settings]
            [ridley.ai.core :as ai]
            [ridley.ai.describe :as ai-vision]
            [ridley.ai.capture-directives :as directives]
            [ridley.ai.batch :as batch]
            [ridley.ai.auto-session :as auto-session]
            [ridley.ai.describe-session :as describe-session]
            [ridley.ui.prompt-panel :as prompt-panel]
            [ridley.library.panel :as lib-panel]
            [ridley.library.core :as lib-core]
            [ridley.library.storage :as lib-storage]
            [ridley.library.builtin :as lib-builtin]
            [ridley.workspace.store :as workspace]
            [ridley.workspace.panel :as workspace-panel]
            [ridley.anim.core :as anim]
            [ridley.anim.playback :as anim-playback]
            [ridley.editor.modal-evaluator :as modal]
            [ridley.editor.tweak-mode :as tweak-mode]
            [ridley.version :as version]
            [ridley.audio :as audio]))

(defonce ^:private editor-view (atom nil))
(defonce ^:private repl-input-el (atom nil))
(defonce ^:private repl-history-el (atom nil))
(defonce ^:private error-el (atom nil))

;; Command history
(defonce ^:private command-history (atom []))
(defonce ^:private history-index (atom -1))

;; Sync state
(defonce ^:private sync-mode (atom nil))  ; nil, :host, or :client
(defonce ^:private sync-debounce-timer (atom nil))
(defonce ^:private share-modal-el (atom nil))  ; Reference to share modal for closing on connect
(defonce ^:private connected-host-id (atom nil))  ; Host peer-id when we're a client

;; Manual panel state
(defonce ^:private manual-panel (atom nil))

(declare evaluate-definitions)
(declare sync-voice-state)
(declare save-to-storage)
(declare send-script-debounced)
(declare maybe-update-ai-focus!)
(declare open-code-in-new-workspace!)

(defn- show-error [msg]
  (when-let [el @error-el]
    (set! (.-textContent el) msg)
    (.add (.-classList el) "visible")))

(defn- hide-error []
  (when-let [el @error-el]
    (set! (.-textContent el) "")
    (.remove (.-classList el) "visible")))

(defn- format-value
  "Format a Clojure value for REPL display."
  [v]
  (cond
    (nil? v) "nil"
    (string? v) (pr-str v)
    (keyword? v) (str v)
    (map? v) (pr-str v)
    (vector? v) (pr-str v)
    (seq? v) (pr-str (vec v))
    (fn? v) "#<function>"
    :else (str v)))

(defn- escape-html [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- add-repl-entry
  "Add an entry to the REPL history display.
   Optional print-output shows stdout before the result."
  ([input result error?] (add-repl-entry input result error? nil))
  ([input result error? print-output]
   (when-let [history-el @repl-history-el]
     (let [entry (.createElement js/document "div")
           formatted (if error?
                       (escape-html result)
                       (escape-html (format-value result)))
           result-class (cond
                          error? "repl-error"
                          (nil? result) "repl-nil"
                          :else "repl-result")
           print-html (when print-output
                        (str "<div class=\"repl-print-output\">"
                             (escape-html print-output)
                             "</div>"))]
       (.add (.-classList entry) "repl-entry")
       (set! (.-innerHTML entry)
             (str "<div class=\"repl-input-echo\">"
                  "<span class=\"repl-prompt\">&gt; </span>"
                  (escape-html input)
                  "</div>"
                  (or print-html "")
                  "<div class=\"" result-class "\">"
                  formatted
                  "</div>"))
       (.appendChild history-el entry)
       ;; Scroll to bottom
       (set! (.-scrollTop history-el) (.-scrollHeight history-el))))))

(defn- add-script-output
  "Add script output (from definitions/manual) to the REPL history.
   Shows only print output without input/result."
  [print-output]
  (when-let [history-el @repl-history-el]
    (let [entry (.createElement js/document "div")]
      (.add (.-classList entry) "repl-entry")
      (set! (.-innerHTML entry)
            (str "<div class=\"repl-print-output\">"
                 (escape-html print-output)
                 "</div>"))
      (.appendChild history-el entry)
      ;; Scroll to bottom
      (set! (.-scrollTop history-el) (.-scrollHeight history-el)))))

(defn- resolve-turtle-pose
  "Resolve the current turtle pose based on turtle-source setting."
  []
  (let [source (viewport/get-turtle-source)]
    (cond
      (= source :global)
      (editor-state/get-turtle-pose)

      (and (map? source) (:mesh source))
      (or (when-let [mesh (registry/get-mesh (:mesh source))]
            (:creation-pose mesh))
          (editor-state/get-turtle-pose))

      (and (map? source) (:custom source))
      (:custom source)

      :else
      (editor-state/get-turtle-pose))))

(defn- update-turtle-indicator
  "Update the turtle indicator with current pose from resolved source."
  []
  (viewport/update-turtle-pose (resolve-turtle-pose)))

(defn- rebuild-turtle-dropdown!
  "Rebuild turtle source select options from current registry."
  []
  (when-let [sel (.getElementById js/document "turtle-source-select")]
    (let [current-source (viewport/get-turtle-source)
          current-val (cond
                        (= current-source :global) "global"
                        (and (map? current-source) (:mesh current-source))
                        (name (:mesh current-source))
                        (and (map? current-source) (:custom current-source))
                        "custom"
                        :else "off")
          names (registry/registered-names)
          make-opt (fn [value text]
                     (let [opt (.createElement js/document "option")]
                       (set! (.-value opt) value)
                       (set! (.-textContent opt) text)
                       opt))]
      ;; Clear all options
      (set! (.-innerHTML sel) "")
      ;; Fixed options
      (.appendChild sel (make-opt "off" "Turtle: Off"))
      (.appendChild sel (make-opt "global" "Turtle: Global"))
      ;; One option per registered mesh
      (doseq [n names]
        (.appendChild sel (make-opt (name n) (str "Turtle: " (name n)))))
      ;; Custom pose option (only when active)
      (when (and (map? current-source) (:custom current-source))
        (.appendChild sel (make-opt "custom" "Turtle: Custom")))
      ;; Restore selection
      (set! (.-value sel) current-val)
      ;; If selected mesh was removed, fall back to global
      (when (= "" (.-value sel))
        (set! (.-value sel) "global")
        (viewport/set-turtle-source! :global))
      ;; Update styling based on off/on
      (if (= (.-value sel) "off")
        (.setAttribute sel "data-off" "")
        (.removeAttribute sel "data-off")))))

(defn- evaluate-definitions-sci
  "Evaluate definitions via SCI (browser-side). The standard path."
  [reset-camera?]
  (let [explicit-code (cm/get-value @editor-view)
        result (repl/evaluate-definitions explicit-code)]
    (viewport/hide-loading!)
    (if-let [error (:error result)]
      (do
        (show-error error)
        (audio/play-feedback! false)
        ;; A deferred modal session (edit-path / edit-bezier / edit-attach) claims the
        ;; mutex (editor read-only) during eval and is only entered by the success
        ;; branch below. If the eval errored, that never happens — tear the pending
        ;; session down so the editor isn't left read-only with no panel/keys to
        ;; escape.
        (when (modal/requested?)
          (modal/force-close-active!)))
      (do
        ;; Show library load warnings if any
        (let [lib-warnings @lib-core/load-warnings]
          (if (seq lib-warnings)
            (show-error (str/join "\n" lib-warnings))
            (hide-error)))
        ;; Show print output in REPL history if any
        (when-let [print-output (:print-output result)]
          (add-script-output print-output))
        (let [render-data (repl/extract-render-data result)]
          (when render-data
            ;; Store lines, stamps, and definition meshes
            (registry/set-lines! (:lines render-data))
            (registry/set-stamps! (or (:stamps render-data) []))
            (registry/set-definition-meshes! (:meshes render-data)))
          ;; Refresh viewport, optionally resetting camera
          (registry/refresh-viewport! reset-camera?)
          ;; Announce success for screen readers (via aria-live on repl-history)
          (let [mesh-count (count (or (:meshes render-data) []))
                line-count (count (or (:lines render-data) []))
                summary (cond
                          (and (pos? mesh-count) (pos? line-count))
                          (str "Evaluation successful: " mesh-count " mesh"
                               (when (> mesh-count 1) "es")
                               ", " line-count " line"
                               (when (> line-count 1) "s"))
                          (pos? mesh-count)
                          (str "Evaluation successful: " mesh-count " mesh"
                               (when (> mesh-count 1) "es"))
                          (pos? line-count)
                          (str "Evaluation successful: " line-count " line"
                               (when (> line-count 1) "s"))
                          :else
                          "Evaluation successful")]
            (add-repl-entry "[Run]" summary false)))
        ;; Update turtle indicator and dropdown
        (update-turtle-indicator)
        (rebuild-turtle-dropdown!)
        ;; Sync AI state
        (sync-voice-state)
        ;; Audio feedback
        (audio/play-feedback! true)
        ;; Check if a deferred modal session (edit-attach, edit-bezier, …) was
        ;; requested during evaluation; enter it now that the eval is complete.
        (when (modal/requested?)
          (modal/enter!))))
    ;; Safety net for the editor's right-click "Tweak": if it auto-wrapped a
    ;; selection in (tweak …) but the wrapped script failed to open a session
    ;; (so the transient is still pending), roll the wrapper back to the original
    ;; selection and re-run the now-clean source. Otherwise the user is left
    ;; staring at a broken (tweak …) baked into the buffer with no way out.
    (when (modal/restore-failed-tweak-transient!)
      (hide-error)
      (add-repl-entry "[Tweak]" "Selection couldn't be tweaked (script error) — selection restored." false)
      (evaluate-definitions))))

(defn- evaluate-definitions
  "Evaluate only the definitions panel (for Cmd+Enter).
   Optional reset-camera? parameter controls whether to reset camera view (default false)."
  ([] (evaluate-definitions false))
  ([reset-camera?]
   ;; Clear registry, animations, and measurement overlays on re-run
   (registry/clear-all!)
   (anim/clear-all!)
   (viewport/clear-rulers!)
   ;; Show loading indicator for potentially long operations
   (viewport/show-loading!)
   ;; Use requestAnimationFrame to let the UI render the spinner before blocking
   (js/requestAnimationFrame
    (fn []
      (js/setTimeout
       (fn []
         (evaluate-definitions-sci reset-camera?))
       0)))))

(defn- evaluate-definitions-user!
  "User-initiated definitions run (Run button / Cmd+Enter). Cancels any active
   modal session first, then evaluates — so re-running while a tweak/edit-attach/
   edit-bezier session is open is clean and predictable instead of throwing a
   mutex error or leaving a half-open session. The programmatic run-definitions
   path (run-definitions-fn = evaluate-definitions, used by a session's own
   confirm/cancel) intentionally does NOT close, to avoid re-entering teardown."
  []
  (modal/force-close-active!)
  (evaluate-definitions))

(defn ^:export run-definitions!
  "Run the definitions panel (same as pressing the Run button).
   Exposed to SCI so screen-reader users can evaluate from the REPL."
  []
  (evaluate-definitions-user!)
  nil)

(defn- handle-ai-command
  "Handle /ai <prompt> — call LLM and append generated code to the script.
   When auto-run? is true, also evaluates definitions after inserting.
   For tier-2+, passes script context and handles clarification responses.
   loading-msg overrides the initial REPL entry (nil = 'Generating...').
   tier-override forces a specific tier (nil = use settings).
   Capture directives like [view: front] [slice: z=30] are parsed from the
   prompt, images are captured, and the generation switches to multimodal."
  ([input prompt auto-run?] (handle-ai-command input prompt auto-run? nil nil))
  ([input prompt auto-run? loading-msg] (handle-ai-command input prompt auto-run? loading-msg nil))
  ([input prompt auto-run? loading-msg tier-override]
   ;; Parse capture directives from the prompt
   (let [{:keys [clean-text images has-directives?]} (directives/process prompt)]
     ;; Show loading in REPL history
     (add-repl-entry input (or loading-msg
                               (if has-directives?
                                 (str "Generating... (" (count images) " captures)")
                                 "Generating..."))
                     false)
     (let [script-content (when @editor-view (cm/get-value @editor-view))]
       (-> (ai/generate clean-text (cond-> {:script-content script-content
                                            :images (when (seq images) images)}
                                     tier-override (assoc :tier-override tier-override)))
           (.then (fn [{:keys [type code question]}]
                    (case type
                      :code
                      (do
                        (when-let [view @editor-view]
                          ;; Promote existing AI block to a numbered history step
                          (cm/promote-ai-block-to-step! view)
                          ;; Insert or replace the AI block with new code
                          (if (cm/find-ai-block view)
                            (cm/replace-ai-block view code prompt)
                            (cm/insert-ai-block view code prompt))
                          (save-to-storage)
                          (send-script-debounced))
                        (ai/add-entry! prompt code)
                        (add-repl-entry input "Code added to script." false)
                        (when auto-run?
                          (evaluate-definitions)))

                      :clarification
                      (add-repl-entry input (str "\uD83E\uDD16 " question) false)

                      ;; Unknown type fallback
                      (add-repl-entry input "Unexpected AI response." true))))
           (.catch (fn [err]
                     (let [msg (or (.-message err) (str err))]
                       (add-repl-entry input msg true)
                       (show-error msg)))))))))

(defn- evaluate-repl-input
  "Evaluate the REPL input and show result in history."
  []
  (when-let [input-el @repl-input-el]
    (let [input (.-value input-el)]
      (when (seq (str/trim input))
        ;; Add to command history
        (swap! command-history conj input)
        (reset! history-index -1)
        ;; Clear input
        (set! (.-value input-el) "")
        ;; Check for special commands
        (let [trimmed (str/trim input)]
          (cond
          ;; /ai-describe [target] — start a describe session
            (or (= trimmed "/ai-describe")
                (str/starts-with? trimmed "/ai-describe "))
            (let [rest (str/trim (subs trimmed (count "/ai-describe")))
                  target (when (seq rest) (keyword rest))]
              (add-repl-entry input "Starting describe session..." false)
              (try
                (describe-session/describe target)
                (catch :default e
                  (add-repl-entry input (.-message e) true))))

          ;; /ai-ask question — follow-up in active describe session
            (str/starts-with? trimmed "/ai-ask ")
            (let [question (str/trim (subs trimmed 8))]
              (if (seq question)
                (do
                  (add-repl-entry input (str "Asking: " question) false)
                  (try
                    (describe-session/ai-ask question)
                    (catch :default e
                      (add-repl-entry input (.-message e) true))))
                (add-repl-entry input "Usage: /ai-ask your question here" true)))

          ;; /ai-end — close describe session
            (= trimmed "/ai-end")
            (add-repl-entry input (describe-session/end-describe) false)

          ;; /ai-clear — reset AI conversation history
            (= trimmed "/ai-clear")
            (do (ai/clear-history!)
                (when-let [view @editor-view]
                  (cm/clear-ai-history! view)
                  (save-to-storage)
                  (send-script-debounced))
                (add-repl-entry input "AI history cleared." false))

          ;; /ai-batch — load EDN test suite from file picker
            (= trimmed "/ai-batch")
            (batch/start-batch-from-file!
             (fn [msg] (add-repl-entry "/ai-batch" msg false)))

          ;; /ai-batch-inline prompt1 | prompt2 | prompt3
            (str/starts-with? trimmed "/ai-batch-inline ")
            (batch/start-batch-inline!
             (str/trim (subs trimmed 18))
             (fn [msg] (add-repl-entry "/ai-batch-inline" msg false)))

          ;; /ai-auto-continue [N] [feedback] — continue visual refinement with optional feedback
            (or (str/starts-with? trimmed "/ai-auto-continue")
                (= trimmed "/ai-auto-continue"))
            (let [rest (str/trim (subs trimmed (count "/ai-auto-continue")))
                  [max-rounds feedback]
                  (if-let [[_ n fb] (re-matches #"(\d+)\s+(.*)" rest)]
                    [(js/parseInt n 10) fb]
                    (if-let [[_ n] (re-matches #"(\d+)" rest)]
                      [(js/parseInt n 10) nil]
                      [3 (when (seq rest) rest)]))]
              (add-repl-entry input (str "Continuing [auto]..."
                                         (when feedback (str " " feedback))) false)
              (auto-session/continue! max-rounds feedback))

          ;; /ai-auto [N] prompt — iterative AI with visual feedback
            (str/starts-with? trimmed "/ai-auto ")
            (let [rest (str/trim (subs trimmed 9))
                  [max-rounds prompt] (if-let [[_ n p] (re-matches #"(\d+)\s+(.*)" rest)]
                                        [(js/parseInt n 10) p]
                                        [5 rest])
                  script-content (when @editor-view (cm/get-value @editor-view))]
              (when (seq prompt)
                (add-repl-entry input (str "Generating [auto, max " max-rounds "]...") false)
                (auto-session/start! prompt script-content max-rounds)))

          ;; /ai1, /ai1!, /ai2, /ai2!, /ai3, /ai3! — tier-specific AI generation
            (or (str/starts-with? trimmed "/ai1! ") (str/starts-with? trimmed "/ai1 ")
                (str/starts-with? trimmed "/ai2! ") (str/starts-with? trimmed "/ai2 ")
                (str/starts-with? trimmed "/ai3! ") (str/starts-with? trimmed "/ai3 "))
            (let [tier-num (subs trimmed 3 4)
                  tier-kw (case tier-num "1" :tier-1 "2" :tier-2 "3" :tier-3)
                  has-bang? (= "!" (subs trimmed 4 5))
                  prefix-len (if has-bang? 6 5)
                  prompt (str/trim (subs trimmed prefix-len))]
              (when (seq prompt)
                (handle-ai-command input prompt has-bang?
                                   (str "Generating [" (name tier-kw) "]...") tier-kw)))

          ;; /ai or /ai! — AI code generation
            (or (str/starts-with? trimmed "/ai! ")
                (str/starts-with? trimmed "/ai "))
            (let [auto-run? (str/starts-with? trimmed "/ai! ")
                  prefix-len (if auto-run? 5 4)
                  prompt (str/trim (subs trimmed prefix-len))]
              (when (seq prompt)
                (handle-ai-command input prompt auto-run?)))

          ;; Explicit negative feedback — rollback last AI code and retry
            (and (when-let [view @editor-view] (cm/find-ai-block view))
                 (let [lower (str/lower-case trimmed)]
                   (or (= lower "no")
                       (str/starts-with? lower "no,")
                       (str/starts-with? lower "no ")
                       (some #(str/includes? lower %)
                             ["sbagliato" "non così" "non va" "wrong" "rifai" "riprova"]))))
            (do
            ;; Record feedback on last history entry
              (ai/add-feedback! trimmed)
            ;; Rollback: delete the AI block from the editor
              (when-let [view @editor-view]
                (cm/delete-ai-block view)
                (save-to-storage)
                (send-script-debounced))
            ;; Auto-retry with the feedback text as the new prompt
              (handle-ai-command input trimmed true "Feedback recorded, regenerating..."))

          ;; Normal REPL evaluation
            :else
            (do
            ;; Cancel any active modal session before a fresh REPL eval
              (when (modal/active?)
                (modal/cancel-active!))
            ;; Send to connected clients if we're the host
              (when (= :host @sync-mode)
                (sync/send-repl-command input))
              (let [result (repl/evaluate-repl input)]
                (if-let [error (:error result)]
                  (do
                    (add-repl-entry input error true)
                    (show-error error)
                    (audio/play-feedback! false))
                  (do
                    (hide-error)
                    (add-repl-entry input (:implicit-result result) false (:print-output result))
                    (when-not (modal/active?)
                      (when-let [render-data (repl/extract-render-data result)]
                        (registry/add-lines! (:lines render-data))
                        (registry/add-stamps! (or (:stamps render-data) []))
                        (registry/set-definition-meshes! (:meshes render-data)))
                      (registry/refresh-viewport! false))
                    (update-turtle-indicator)
                    (rebuild-turtle-dropdown!)
                    (sync-voice-state)
                    (audio/play-feedback! true)))))))))))

(defn- navigate-history
  "Navigate command history. direction: -1 for older, +1 for newer."
  [direction]
  (when-let [input-el @repl-input-el]
    (let [history @command-history
          current-idx @history-index
          history-len (count history)
          new-idx (cond
                    ;; Going back in history
                    (= direction -1)
                    (if (= current-idx -1)
                      (dec history-len)  ; Start from most recent
                      (max 0 (dec current-idx)))
                    ;; Going forward in history
                    (= direction 1)
                    (if (>= current-idx (dec history-len))
                      -1  ; Past end, clear
                      (inc current-idx))
                    :else current-idx)]
      (reset! history-index new-idx)
      (if (= new-idx -1)
        (set! (.-value input-el) "")
        (set! (.-value input-el) (nth history new-idx))))))

;; ============================================================
;; Save/Load functionality
;; ============================================================

(defn- save-to-storage
  "Auto-save the editor content into the current workspace (session store).
   This is the single persistence point, also used by voice-undo and
   auto-session."
  []
  (when-let [content (cm/get-value @editor-view)]
    (workspace/set-current-content! content)
    ;; Keep the dirty indicator live for file-bound workspaces.
    (workspace-panel/on-content-changed!)))

(defn- refocus-editor!
  "Return keyboard focus to the editor after a native file dialog closes.
   File dialogs steal focus and don't reliably restore it; the small delay
   lets the dialog finish dismissing before we grab focus back."
  []
  (js/setTimeout #(cm/focus @editor-view) 50))

(defn- download-blob-fallback
  "Download a blob using the traditional createElement('a') method."
  [blob filename]
  (let [url (js/URL.createObjectURL blob)
        link (.createElement js/document "a")]
    (set! (.-href link) url)
    (set! (.-download link) filename)
    (.click link)
    (js/URL.revokeObjectURL url)))

(defn- save-blob-with-picker
  "Save a blob using the most appropriate Save As path for the runtime:
   - Desktop (Tauri, WKWebView): native dialog via geo-server /pick-save-path + /write-file.
   - Chrome/Edge: File System Access API (showSaveFilePicker).
   - Other browsers: traditional <a download> fallback.

   extensions is a JS array of dot-prefixed strings, e.g. #js [\".clj\"]."
  [blob filename description mime-type extensions]
  (cond
    (env/desktop?)
    (let [exts (vec (map #(if (.startsWith ^js % ".") (.substring % 1) %)
                         (array-seq extensions)))]
      (-> (stl/desktop-pick-save-path filename {:title "Save"
                                                :filters [{:name description
                                                           :extensions exts}]})
          (.then (fn [chosen-path]
                   (when chosen-path
                     (stl/desktop-write-file blob chosen-path))))
          (.catch (fn [err]
                    (js/console.warn "native save error:" err)
                    nil))))

    (exists? js/window.showSaveFilePicker)
    (let [accept (js-obj mime-type extensions)]
      (-> (js/window.showSaveFilePicker
           #js {:suggestedName filename
                :types #js [#js {:description description
                                 :accept accept}]})
          (.then (fn [handle] (.createWritable handle)))
          (.then (fn [writable]
                   (-> (.write writable blob)
                       (.then #(.close writable)))))
          (.catch (fn [_err]
                    ;; User cancelled — do nothing
                    nil))))

    :else
    (download-blob-fallback blob filename)))

;; ── Workspace-aware Save / Save As / Open ─────────────────────
;; Save/Open operate on the current workspace. On desktop a workspace can be
;; bound to a file (:file-path); Save writes back to it without a picker, while
;; Save As always prompts and (re)binds. On web there is no real path, so both
;; fall back to the browser's export picker.

(declare open-file-in-workspace!)

(defn- ws-basename [path]
  (when path (last (str/split path #"[\\/]"))))

(defn- suggest-filename
  "A sensible suggested filename for the current workspace."
  [w]
  (let [n (or (:name w) "workspace")]
    (if (re-find #"\.[A-Za-z0-9]+$" n) n (str n ".clj"))))

;; The file holds a header with the workspace's library list (serialize-file),
;; stripped on read (parse-file). `content` is always the bare code body.

(defn- desktop-save-to-path!
  "Write the serialized workspace (header + `content`) to an already-bound
   `path`, mark synced (code + libraries), refresh indicator."
  [content path]
  (let [libs (lib-storage/get-active-libraries)]
    (-> (stl/desktop-write-file (workspace/serialize-file libs content) path)
        (.then (fn [_]
                 (when-let [id (workspace/current-id)]
                   (workspace/mark-synced! id content libs))
                 (workspace-panel/render!)))
        (.catch (fn [err] (js/console.warn "save error:" err) nil)))))

(defn- desktop-save-as!
  "Prompt for a path, write the serialized workspace, bind the current workspace."
  [content]
  (let [libs (lib-storage/get-active-libraries)]
    (-> (stl/desktop-pick-save-path
         (suggest-filename (workspace/current))
         {:title "Save As" :filters [{:name "Clojure files" :extensions ["clj"]}]})
        (.then (fn [path]
                 (when path
                   (-> (stl/desktop-write-file (workspace/serialize-file libs content) path)
                       (.then (fn [_]
                                (when-let [id (workspace/current-id)]
                                  (workspace/bind-file! id path content libs (ws-basename path)))
                                (workspace-panel/render!)))))))
        (.catch (fn [err] (js/console.warn "save-as error:" err) nil))
        (.finally refocus-editor!))))

(defn- web-export!
  "Web Save/Save As: download the serialized workspace via the browser picker."
  [content w]
  (let [libs (lib-storage/get-active-libraries)
        blob (js/Blob. #js [(workspace/serialize-file libs content)]
                       #js {:type "text/plain"})]
    (some-> (save-blob-with-picker blob (suggest-filename w)
                                   "Clojure files" "text/plain" #js [".clj"])
            (.finally refocus-editor!))))

(defn- save-definitions
  "Save the current workspace. Desktop bound → write back; otherwise Save As."
  []
  (when-let [content (cm/get-value @editor-view)]
    (save-to-storage)
    (let [w (workspace/current)]
      (cond
        (and (env/desktop?) (:file-path w)) (desktop-save-to-path! content (:file-path w))
        (env/desktop?)                      (desktop-save-as! content)
        :else                               (web-export! content w)))))

(defn- save-definitions-as
  "Always prompt for a new path/name (Save As)."
  []
  (when-let [content (cm/get-value @editor-view)]
    (save-to-storage)
    (if (env/desktop?)
      (desktop-save-as! content)
      (web-export! content (workspace/current)))))

(defn- open-from-disk
  "Open a file into a NEW workspace. Desktop uses the native dialog and binds
   the workspace to the chosen path; web triggers the hidden file input. The
   library-list header is parsed out and the bare body goes into the editor."
  []
  (if (env/desktop?)
    (-> (stl/desktop-pick-open-path
         {:title "Open" :filters [{:name "Clojure files" :extensions ["clj" "cljs" "edn"]}]})
        (.then (fn [path]
                 (when path
                   (-> (stl/desktop-read-file path)
                       (.then (fn [text]
                                (let [{:keys [libraries body]} (workspace/parse-file text)]
                                  (open-file-in-workspace! body {:path path :libraries libraries}))))))))
        (.catch (fn [err] (js/console.warn "open error:" err) nil))
        (.finally refocus-editor!))
    (when-let [fi (.getElementById js/document "file-input")]
      (.click fi))))

(defn- load-definitions
  "Web file-input handler: read a File into a new (named) workspace, stripping
   the library-list header."
  [file]
  (let [reader (js/FileReader.)]
    (set! (.-onload reader)
          (fn [e]
            (let [{:keys [libraries body]} (workspace/parse-file (.. e -target -result))]
              (open-file-in-workspace! body {:name (.-name file) :libraries libraries}))
            (refocus-editor!)))
    (.readAsText reader file)))

;; ============================================================
;; Resizable panels
;; ============================================================

(defn- setup-vertical-resizer
  "Setup vertical resizer between definitions and REPL sections."
  []
  (let [divider (.querySelector js/document ".section-divider")
        explicit-section (.getElementById js/document "explicit-section")
        repl-section (.getElementById js/document "repl-section")
        editor-panel (.getElementById js/document "editor-panel")
        dragging (atom false)
        start-y (atom 0)
        start-explicit-height (atom 0)]
    (when (and divider explicit-section repl-section)
      (.addEventListener divider "mousedown"
                         (fn [e]
                           (.preventDefault e)
                           (reset! dragging true)
                           (reset! start-y (.-clientY e))
                           (reset! start-explicit-height (.-offsetHeight explicit-section))
                           (.add (.-classList js/document.body) "resizing-v")))
      (.addEventListener js/document "mousemove"
                         (fn [e]
                           (when @dragging
                             (let [delta (- (.-clientY e) @start-y)
                                   panel-height (.-offsetHeight editor-panel)
                                   new-height (+ @start-explicit-height delta)
                                   min-height 80
                                   max-height (- panel-height 120)] ; Leave room for REPL
                               (when (and (>= new-height min-height) (<= new-height max-height))
                                 (set! (.-flexGrow (.-style explicit-section)) "0")
                                 (set! (.-flexBasis (.-style explicit-section)) (str new-height "px"))
                                 (set! (.-flexGrow (.-style repl-section)) "1"))))))
      (.addEventListener js/document "mouseup"
                         (fn [_]
                           (when @dragging
                             (reset! dragging false)
                             (.remove (.-classList js/document.body) "resizing-v")))))))

(defn- setup-horizontal-resizer
  "Setup horizontal resizer between editor panel and viewport."
  []
  (let [editor-panel (.getElementById js/document "editor-panel")
        viewport-panel (.getElementById js/document "viewport-panel")
        app (.getElementById js/document "app")
        dragging (atom false)
        start-x (atom 0)
        start-width (atom 0)]
    (when (and editor-panel viewport-panel)
      ;; Create resizer element
      (let [resizer (.createElement js/document "div")]
        (set! (.-id resizer) "panel-resizer")
        (.insertBefore app resizer viewport-panel)
        (.addEventListener resizer "mousedown"
                           (fn [e]
                             (.preventDefault e)
                             (reset! dragging true)
                             (reset! start-x (.-clientX e))
                             (reset! start-width (.-offsetWidth editor-panel))
                             (.add (.-classList js/document.body) "resizing-h")))
        (.addEventListener js/document "mousemove"
                           (fn [e]
                             (when @dragging
                               (let [delta (- (.-clientX e) @start-x)
                                     app-width (.-offsetWidth app)
                                     new-width (+ @start-width delta)
                                     min-width 250
                                     max-width (- app-width 300)] ; Leave room for viewport
                                 (when (and (>= new-width min-width) (<= new-width max-width))
                                   (set! (.-width (.-style editor-panel)) (str new-width "px"))
                                   (set! (.-minWidth (.-style editor-panel)) (str new-width "px")))))))
                  ;; ResizeObserver handles viewport resize automatically
        (.addEventListener js/document "mouseup"
                           (fn [_]
                             (when @dragging
                               (reset! dragging false)
                               (.remove (.-classList js/document.body) "resizing-h"))))))))

;; ============================================================
;; Setup
;; ============================================================

(defn- setup-keybindings []
  ;; CodeMirror handles Cmd+Enter via keymap and on-change via update listener
  ;; REPL input: Enter to run, arrows for history
  (when-let [el @repl-input-el]
    (.addEventListener el "keydown"
                       (fn [e]
                         (case (.-key e)
                           "Enter"
                           (do
                             (.preventDefault e)
                             (evaluate-repl-input))
                           "ArrowUp"
                           (do
                             (.preventDefault e)
                             (navigate-history -1))
                           "ArrowDown"
                           (do
                             (.preventDefault e)
                             (navigate-history 1))
                           "Escape"
                           (when (auto-session/active?)
                             (.preventDefault e)
                             (auto-session/cancel!))
                           nil))))
  ;; Global Cmd+Enter — run script from anywhere (REPL, viewport, etc.)
  (.addEventListener js/window "keydown"
                     (fn [e]
                       (when (and (= "Enter" (.-key e))
                                  (or (.-metaKey e) (.-ctrlKey e)))
        ;; Only handle if focus is NOT in the CodeMirror editor
        ;; (CodeMirror has its own Cmd+Enter handler)
                         (let [active (.-activeElement js/document)
                               in-editor? (when active
                                            (.closest active ".cm-editor"))]
                           (when-not in-editor?
                             (.preventDefault e)
                             (evaluate-definitions)))))))

(defn- export-mesh [fmt]
  (let [meshes (viewport/get-current-meshes)]
    (if (seq meshes)
      (let [fname (or (first (registry/registered-names)) "model")
            ext (name fmt)]
        (stl/download-mesh meshes (str (name fname) "." ext) fmt))
      (js/alert "No meshes to export. Run some code first!"))))

(defn- setup-save-load []
  (let [run-btn (.getElementById js/document "btn-run")
        save-btn (.getElementById js/document "btn-save")
        save-as-btn (.getElementById js/document "btn-save-as")
        load-btn (.getElementById js/document "btn-load")
        export-btn (.getElementById js/document "btn-export")
        export-menu (.getElementById js/document "export-menu")
        export-stl-btn (.getElementById js/document "btn-export-stl")
        export-3mf-btn (.getElementById js/document "btn-export-3mf")
        toggle-grid-btn (.getElementById js/document "btn-toggle-grid")
        toggle-axes-btn (.getElementById js/document "btn-toggle-axes")
        turtle-select (.getElementById js/document "turtle-source-select")
        toggle-lines-btn (.getElementById js/document "btn-toggle-lines")
        toggle-normals-btn (.getElementById js/document "btn-toggle-normals")
        toggle-stamps-btn (.getElementById js/document "btn-toggle-stamps")
        reset-view-btn (.getElementById js/document "btn-reset-view")
        file-input (.getElementById js/document "file-input")]
    ;; Run button - evaluate definitions
    (.addEventListener run-btn "click"
                       (fn [_] (evaluate-definitions-user!)))
    ;; Save button
    (.addEventListener save-btn "click"
                       (fn [_] (save-definitions)))
    ;; Save As button
    (when save-as-btn
      (.addEventListener save-as-btn "click"
                         (fn [_] (save-definitions-as))))
    ;; Open button - load a file into a new workspace
    (.addEventListener load-btn "click"
                       (fn [_] (open-from-disk)))
    ;; Export dropdown menu
    (when export-btn
      (.addEventListener export-btn "click"
                         (fn [e]
                           (.stopPropagation e)
                           (.toggle (.-classList export-menu) "hidden")))
      (.addEventListener js/document "click"
                         (fn [_]
                           (.add (.-classList export-menu) "hidden"))))
    (when export-stl-btn
      (.addEventListener export-stl-btn "click"
                         (fn [_]
                           (.add (.-classList export-menu) "hidden")
                           (export-mesh :stl))))
    (when export-3mf-btn
      (.addEventListener export-3mf-btn "click"
                         (fn [_]
                           (.add (.-classList export-menu) "hidden")
                           (export-mesh :3mf))))
    ;; Toggle grid button
    (when toggle-grid-btn
      ;; Set initial active state (grid is visible by default)
      (.add (.-classList toggle-grid-btn) "active")
      (.addEventListener toggle-grid-btn "click"
                         (fn [_]
                           (let [visible (viewport/toggle-grid)]
                             (if visible
                               (.add (.-classList toggle-grid-btn) "active")
                               (.remove (.-classList toggle-grid-btn) "active"))))))
    ;; Toggle axes button
    (when toggle-axes-btn
      ;; Set initial active state (axes visible by default)
      (.add (.-classList toggle-axes-btn) "active")
      (.addEventListener toggle-axes-btn "click"
                         (fn [_]
                           (let [visible (viewport/toggle-axes)]
                             (if visible
                               (.add (.-classList toggle-axes-btn) "active")
                               (.remove (.-classList toggle-axes-btn) "active"))))))
    ;; Turtle source selector
    (when turtle-select
      (.addEventListener turtle-select "change"
                         (fn [_]
                           (let [val (.-value turtle-select)]
                             (cond
                               (= val "off")
                               (do (viewport/set-turtle-visible false)
                                   (.setAttribute turtle-select "data-off" ""))

                               (= val "global")
                               (do (viewport/set-turtle-source! :global)
                                   (viewport/set-turtle-visible true)
                                   (.removeAttribute turtle-select "data-off")
                                   (update-turtle-indicator))

                               (= val "custom")
                               nil ;; already set, just keep it

                               :else ;; mesh name
                               (do (viewport/set-turtle-source! {:mesh (keyword val)})
                                   (viewport/set-turtle-visible true)
                                   (.removeAttribute turtle-select "data-off")
                                   (update-turtle-indicator)))))))
    ;; Toggle construction lines button
    (when toggle-lines-btn
      ;; Set initial active state (lines visible by default)
      (.add (.-classList toggle-lines-btn) "active")
      (.addEventListener toggle-lines-btn "click"
                         (fn [_]
                           (let [visible (viewport/toggle-lines)]
                             (if visible
                               (.add (.-classList toggle-lines-btn) "active")
                               (.remove (.-classList toggle-lines-btn) "active"))))))
    ;; Toggle face normals button
    (when toggle-normals-btn
      ;; Normals off by default (no active class initially)
      (.addEventListener toggle-normals-btn "click"
                         (fn [_]
                           (let [visible (viewport/toggle-normals)]
                             (if visible
                               (.add (.-classList toggle-normals-btn) "active")
                               (.remove (.-classList toggle-normals-btn) "active"))))))
    ;; Toggle stamp outlines button
    (when toggle-stamps-btn
      ;; Stamps visible by default
      (.add (.-classList toggle-stamps-btn) "active")
      (.addEventListener toggle-stamps-btn "click"
                         (fn [_]
                           (let [visible (viewport/toggle-stamps)]
                             (if visible
                               (.add (.-classList toggle-stamps-btn) "active")
                               (.remove (.-classList toggle-stamps-btn) "active"))))))
    ;; Reset view button
    (when reset-view-btn
      (.addEventListener reset-view-btn "click"
                         (fn [_] (viewport/reset-camera))))
    ;; File input change (for local file loading)
    (.addEventListener file-input "change"
                       (fn [e]
                         (when-let [file (aget (.-files (.-target e)) 0)]
                           (load-definitions file))
        ;; Reset input so same file can be loaded again
                         (set! (.-value file-input) "")))))

;; ============================================================
;; Animation transport UI
;; ============================================================

(defonce ^:private transport-raf (atom nil))
(defonce ^:private transport-hide-timer (atom nil))

(defn- format-time
  "Format seconds as M:SS."
  [secs]
  (let [m (int (/ secs 60))
        s (int (mod secs 60))]
    (str m ":" (when (< s 10) "0") s)))

(defn- update-transport-ui!
  "Update slider and time display from current animation state."
  []
  (let [slider (.getElementById js/document "anim-slider")
        time-el (.getElementById js/document "anim-time")
        select-el (.getElementById js/document "anim-select")
        selected (when select-el (.-value select-el))
        reg @anim/anim-registry]
    (when (and slider time-el (seq reg))
      (let [;; Pick the selected animation or the first one
            anim-name (if (and (seq selected) (not= selected ""))
                        (keyword selected)
                        (first (keys reg)))
            anim-data (get reg anim-name)]
        (when anim-data
          (let [duration (:duration anim-data 0)
                current (:current-time anim-data 0)
                loop-mode (:loop anim-data)
                ;; For bounce, current-time spans [0, 2*duration] — map to visual fraction
                effective (if (= :bounce loop-mode)
                            (if (< current duration)
                              current
                              (- (* 2.0 duration) current))
                            (if (= :reverse loop-mode)
                              (- duration current)
                              current))
                frac (if (pos? duration) (/ effective duration) 0)]
            (set! (.-value slider) (str (int (* frac 1000))))
            (set! (.-textContent time-el)
                  (str (format-time current) " / " (format-time duration)))))))))

(defn- transport-tick!
  "RAF loop for updating transport UI during playback."
  []
  (update-transport-ui!)
  (when (anim/any-playing?)
    (reset! transport-raf
            (js/requestAnimationFrame (fn [_] (transport-tick!))))))

(defn- start-transport-tick!
  "Start the transport UI update loop if not already running."
  []
  (when-not @transport-raf
    (transport-tick!)))

(defn- stop-transport-tick!
  "Stop the transport UI update loop."
  []
  (when-let [raf @transport-raf]
    (js/cancelAnimationFrame raf)
    (reset! transport-raf nil)))

(defn- refresh-anim-select!
  "Populate the animation select dropdown from registry."
  []
  (when-let [select-el (.getElementById js/document "anim-select")]
    (let [reg @anim/anim-registry
          current-val (.-value select-el)]
      (set! (.-innerHTML select-el) "")
      ;; "All" option
      (let [opt (.createElement js/document "option")]
        (set! (.-value opt) "")
        (set! (.-textContent opt) "All")
        (.appendChild select-el opt))
      ;; One option per animation
      (doseq [[anim-name _] reg]
        (let [opt (.createElement js/document "option")]
          (set! (.-value opt) (name anim-name))
          (set! (.-textContent opt) (name anim-name))
          (.appendChild select-el opt)))
      ;; Restore selection if still valid
      (set! (.-value select-el) current-val))))

(defn- setup-transport []
  (let [transport-el (.getElementById js/document "anim-transport")
        play-btn (.getElementById js/document "btn-anim-play")
        pause-btn (.getElementById js/document "btn-anim-pause")
        stop-btn (.getElementById js/document "btn-anim-stop")
        slider (.getElementById js/document "anim-slider")
        select-el (.getElementById js/document "anim-select")]
    (when (and transport-el play-btn pause-btn stop-btn slider select-el)
      ;; Play button
      (.addEventListener play-btn "click"
                         (fn [_]
                           (let [selected (.-value select-el)]
                             (if (and (seq selected) (not= selected ""))
                               (anim/play! (keyword selected))
                               (anim/play!)))
                           (start-transport-tick!)))
      ;; Pause button
      (.addEventListener pause-btn "click"
                         (fn [_]
                           (let [selected (.-value select-el)]
                             (if (and (seq selected) (not= selected ""))
                               (anim/pause! (keyword selected))
                               (anim/pause!)))
                           (stop-transport-tick!)
                           (update-transport-ui!)))
      ;; Stop button
      (.addEventListener stop-btn "click"
                         (fn [_]
                           (let [selected (.-value select-el)]
                             (if (and (seq selected) (not= selected ""))
                               (anim/stop! (keyword selected))
                               (anim/stop!)))
                           (stop-transport-tick!)
                           (update-transport-ui!)))
      ;; Slider scrub (seek + visually apply frame)
      (.addEventListener slider "input"
                         (fn [_]
                           (let [frac (/ (js/parseInt (.-value slider)) 1000.0)
                                 selected (.-value select-el)
                                 reg @anim/anim-registry]
                             (if (and (seq selected) (not= selected ""))
                               (anim-playback/seek-and-apply! (keyword selected) frac)
              ;; Seek all
                               (doseq [[anim-name _] reg]
                                 (anim-playback/seek-and-apply! anim-name frac))))))
      ;; Watch registry for show/hide transport and refresh select
      (add-watch anim/anim-registry ::transport-visibility
                 (fn [_ _ old-val new-val]
                   (let [has-anims (pos? (count new-val))]
            ;; Show/hide transport bar (debounce hide to avoid flicker on re-eval)
                     (if has-anims
                       (do
                ;; Cancel pending hide
                         (when-let [t @transport-hide-timer]
                           (js/clearTimeout t)
                           (reset! transport-hide-timer nil))
                         (.remove (.-classList transport-el) "hidden"))
              ;; Delay hide — evaluate-definitions clears then re-registers
                       (reset! transport-hide-timer
                               (js/setTimeout
                                (fn []
                                  (reset! transport-hide-timer nil)
                                  (when (zero? (count @anim/anim-registry))
                                    (.add (.-classList transport-el) "hidden")))
                                100)))
            ;; Refresh dropdown when animations change
                     (when (not= (set (keys old-val)) (set (keys new-val)))
                       (refresh-anim-select!))
            ;; Start/stop tick based on playing state
                     (let [any-playing (some #(= :playing (:state (val %))) new-val)]
                       (if any-playing
                         (start-transport-tick!)
                         (do (stop-transport-tick!)
                             (update-transport-ui!))))))))))

;; ============================================================
;; Sync (Desktop <-> Headset)
;; ============================================================

(declare join-session)

(defn- send-script-debounced
  "Send script to connected peer with debounce.
   Only sends if we're the host (client never sends, only receives)."
  []
  (when (and (sync/connected?)
             (= :host @sync-mode))
    (when-let [timer @sync-debounce-timer]
      (js/clearTimeout timer))
    (reset! sync-debounce-timer
            (js/setTimeout
             (fn []
               (when-let [content (cm/get-value @editor-view)]
                 (sync/send-script content)))
             500))))

(defn- on-script-received
  "Called when we receive a script from peer (client only receives, never sends back)."
  [definitions]
  (when @editor-view
    (cm/set-value @editor-view definitions)
    (save-to-storage)
    (evaluate-definitions)))

(defn- on-repl-received
  "Called when we receive a REPL command from host. Execute it and show in history."
  [command]
  (when (seq (str/trim command))
    (let [result (repl/evaluate-repl command)]
      (if-let [error (:error result)]
        (do
          (add-repl-entry command error true)
          (show-error error))
        (do
          (hide-error)
          (add-repl-entry command (:implicit-result result) false (:print-output result))
          (when-let [render-data (repl/extract-render-data result)]
            (registry/add-lines! (:lines render-data))
            (registry/add-stamps! (or (:stamps render-data) []))
            (registry/set-definition-meshes! (:meshes render-data)))
          (registry/refresh-viewport! false))))))

(defn- update-sync-status-text
  "Update the sync status text in toolbar."
  []
  (when-let [status-el (.getElementById js/document "sync-status")]
    (let [status (sync/get-status)]
      (set! (.-textContent status-el)
            (case status
              :connected (if (= :host @sync-mode)
                           (let [peer-id (sync/get-peer-id)
                                 short-code (sync/get-short-code peer-id)
                                 client-count (sync/get-client-count)]
                             (str client-count " client" (when (not= client-count 1) "s")
                                  " · " short-code))
                           ;; Client: show host's code
                           (let [short-code (sync/get-short-code @connected-host-id)]
                             (str "Connected to " short-code)))
              :waiting (let [peer-id (sync/get-peer-id)
                             short-code (sync/get-short-code peer-id)]
                         (str "Waiting · " short-code))
              "")))))

(defn- on-clients-change
  "Called when number of connected clients changes."
  [_count]
  (update-sync-status-text))

(defn- update-sync-status-ui
  "Update the sync buttons UI based on status."
  [status]
  (let [share-btn (.getElementById js/document "btn-share")
        link-btn (.getElementById js/document "btn-link")]
    (case status
      :disconnected (do
                      (when share-btn
                        (set! (.-textContent share-btn) "Share")
                        (.remove (.-classList share-btn) "active" "waiting"))
                      (when link-btn
                        (set! (.-textContent link-btn) "Link")
                        (.remove (.-classList link-btn) "active" "waiting"))
                      (update-sync-status-text))
      :waiting      (do
                      (when share-btn
                        (set! (.-textContent share-btn) "Waiting...")
                        (.add (.-classList share-btn) "waiting"))
                      (update-sync-status-text))
      :connecting   (when link-btn
                      (set! (.-textContent link-btn) "Connecting...")
                      (.add (.-classList link-btn) "waiting"))
      :connected    (do
                      ;; Close share modal if open
                      (when-let [modal @share-modal-el]
                        (reset! share-modal-el nil)
                        (.remove modal))
                      (when share-btn
                        (set! (.-textContent share-btn) "Synced")
                        (.add (.-classList share-btn) "active")
                        (.remove (.-classList share-btn) "waiting"))
                      (when link-btn
                        (set! (.-textContent link-btn) "Synced")
                        (.add (.-classList link-btn) "active")
                        (.remove (.-classList link-btn) "waiting"))
                      (update-sync-status-text))
      :error        (do
                      (when share-btn
                        (set! (.-textContent share-btn) "Error")
                        (.remove (.-classList share-btn) "active" "waiting"))
                      (when link-btn
                        (set! (.-textContent link-btn) "Error")
                        (.remove (.-classList link-btn) "active" "waiting")))
      nil)))

(defn- show-share-modal
  "Show modal with session code for sharing."
  [peer-id]
  (let [short-code (sync/get-short-code peer-id)
        modal (.createElement js/document "div")
        overlay (.createElement js/document "div")
        close-modal (fn []
                      (reset! share-modal-el nil)
                      (.remove overlay)
                      (.remove modal))]
    ;; Store reference so we can close it on connect
    (reset! share-modal-el modal)
    ;; Setup overlay (sibling of modal, not child)
    (set! (.-className overlay) "sync-modal-overlay")
    (.addEventListener overlay "click" close-modal)
    ;; Setup modal
    (set! (.-className modal) "sync-modal")
    (set! (.-innerHTML modal)
          (str "<div class='sync-modal-content'>"
               "<h3>Share Session</h3>"
               "<p>Enter this code on the other device:</p>"
               "<div class='sync-code'>" short-code "</div>"
               "<p class='sync-status'>Waiting for connection...</p>"
               "<button class='sync-close-btn'>Close</button>"
               "</div>"))
    (.appendChild js/document.body overlay)
    (.appendChild js/document.body modal)
    ;; Close button
    (when-let [close-btn (.querySelector modal ".sync-close-btn")]
      (.addEventListener close-btn "click" close-modal))))

(defn- show-link-modal
  "Show modal with input field to enter session code."
  []
  (let [modal (.createElement js/document "div")
        overlay (.createElement js/document "div")
        close-modal (fn [] (.remove overlay) (.remove modal))]
    ;; Setup overlay (sibling of modal, not child)
    (set! (.-className overlay) "sync-modal-overlay")
    (.addEventListener overlay "click" close-modal)
    ;; Setup modal
    (set! (.-className modal) "sync-modal")
    (set! (.-innerHTML modal)
          (str "<div class='sync-modal-content'>"
               "<h3>Join Session</h3>"
               "<p>Enter the code shown on the other device:</p>"
               "<input type='text' class='sync-code-input' placeholder='ABC123' maxlength='6' autocapitalize='characters'>"
               "<p class='sync-error' style='display:none; color:#e74c3c;'></p>"
               "<div class='sync-buttons'>"
               "<button class='sync-connect-btn'>Connect</button>"
               "<button class='sync-close-btn'>Cancel</button>"
               "</div>"
               "</div>"))
    (.appendChild js/document.body overlay)
    (.appendChild js/document.body modal)
    ;; Focus input
    (when-let [input (.querySelector modal ".sync-code-input")]
      (.focus input)
      ;; Connect on Enter
      (.addEventListener input "keydown"
                         (fn [e]
                           (when (= "Enter" (.-key e))
                             (.click (.querySelector modal ".sync-connect-btn"))))))
    ;; Connect button
    (when-let [connect-btn (.querySelector modal ".sync-connect-btn")]
      (.addEventListener connect-btn "click"
                         (fn []
                           (let [input (.querySelector modal ".sync-code-input")
                                 code (.-value input)]
                             (if (>= (count code) 4)
                               (let [peer-id (sync/peer-id-from-code code)]
                                 (close-modal)
                                 (join-session peer-id))
                               (when-let [error (.querySelector modal ".sync-error")]
                                 (set! (.-textContent error) "Code must be at least 4 characters")
                                 (set! (.-style.display error) "block")))))))
    ;; Close button
    (when-let [close-btn (.querySelector modal ".sync-close-btn")]
      (.addEventListener close-btn "click" close-modal))))

(defn- on-client-connected
  "Called when a new client connects - send them the current script."
  [conn]
  (when-let [content (cm/get-value @editor-view)]
    (sync/send-script-to-connection conn content)))

(defn- start-hosting
  "Start hosting a sync session."
  []
  (let [peer-id (sync/host-session
                 :on-script-received on-script-received
                 :on-status-change update-sync-status-ui
                 :on-clients-change on-clients-change
                 :on-client-connected on-client-connected)]
    (reset! sync-mode :host)
    (show-share-modal peer-id)))

(defn- join-session
  "Join an existing sync session."
  [peer-id]
  (reset! sync-mode :client)
  (reset! connected-host-id peer-id)  ; Save host's peer-id for status display
  (sync/join-session peer-id
                     :on-script-received on-script-received
                     :on-repl-received on-repl-received
                     :on-status-change update-sync-status-ui))

(defn- setup-sync
  "Setup sync buttons and auto-join if URL has peer parameter."
  []
  ;; Share button - start hosting
  (when-let [share-btn (.getElementById js/document "btn-share")]
    (.addEventListener share-btn "click"
                       (fn [_]
                         (if (or (sync/hosting?) (sync/connected?))
                           (do
                             (sync/stop-hosting)
                             (reset! sync-mode nil)
                             (update-sync-status-ui :disconnected))
                           (start-hosting)))))
  ;; Link button - join session
  (when-let [link-btn (.getElementById js/document "btn-link")]
    (.addEventListener link-btn "click"
                       (fn [_]
                         (if (sync/connected?)
                           (do
                             (sync/leave-session)
                             (reset! sync-mode nil)
                             (update-sync-status-ui :disconnected))
                           (show-link-modal)))))
  ;; Auto-join if URL has peer parameter
  (when-let [peer-id (sync/get-peer-from-url)]
    (js/console.log "Auto-joining sync session:" peer-id)
    (sync/clear-peer-from-url)
    (join-session peer-id)))

;; ============================================================
;; LLM Settings Modal
;; ============================================================

(defonce ^:private show-api-key? (atom false))

(defn- render-settings-content
  "Render the settings modal content based on current settings state.
   Returns an HTML string."
  []
  (let [ai (:ai @settings/settings)
        enabled (:enabled ai)
        provider (:provider ai)]
    (str
     "<h3>⚙ Settings</h3>"

     "<h3 class='settings-section-header'>AI Assistant</h3>"
     ;; Enable checkbox
     "<div class='settings-field'>"
     "<label class='settings-checkbox-row'>"
     "<input type='checkbox' id='settings-ai-enabled' "
     (when enabled "checked") ">"
     "Enable AI"
     "</label>"
     "</div>"

     ;; Provider-specific fields (only when enabled)
     (when enabled
       (str
        ;; Provider dropdown
        "<div class='settings-field'>"
        "<label class='settings-label'>Provider</label>"
        "<select id='settings-provider' class='settings-select'>"
        "<option value='anthropic'" (when (= provider :anthropic) " selected") ">Anthropic</option>"
        "<option value='openai'" (when (= provider :openai) " selected") ">OpenAI</option>"
        "<option value='groq'" (when (= provider :groq) " selected") ">Groq</option>"
        "<option value='ollama'" (when (= provider :ollama) " selected") ">Ollama</option>"
        "<option value='google'" (when (= provider :google) " selected") ">Google Gemini</option>"
        "</select>"
        "</div>"

        ;; Anthropic
        (when (= provider :anthropic)
          (str
           "<div class='settings-field'>"
           "<label class='settings-label'>API Key</label>"
           "<div class='settings-api-key-row'>"
           "<input type='" (if @show-api-key? "text" "password") "' "
           "id='settings-api-key' class='settings-input' "
           "placeholder='sk-ant-...' "
           "value='" (or (:anthropic-key ai) "") "'>"
           "<button class='settings-toggle-btn' id='settings-toggle-key'>"
           (if @show-api-key? "Hide" "Show")
           "</button>"
           "</div>"
           "</div>"
           "<div class='settings-field'>"
           "<label class='settings-label'>Model</label>"
           "<select id='settings-model' class='settings-select'>"
           "<option value='claude-sonnet-4-20250514'" (when (= (:model ai) "claude-sonnet-4-20250514") " selected") ">Claude Sonnet 4</option>"
           "<option value='claude-opus-4-20250514'" (when (= (:model ai) "claude-opus-4-20250514") " selected") ">Claude Opus 4</option>"
           "<option value='claude-3-5-haiku-latest'" (when (= (:model ai) "claude-3-5-haiku-latest") " selected") ">Claude 3.5 Haiku</option>"
           "</select>"
           "</div>"))

        ;; OpenAI
        (when (= provider :openai)
          (str
           "<div class='settings-field'>"
           "<label class='settings-label'>API Key</label>"
           "<div class='settings-api-key-row'>"
           "<input type='" (if @show-api-key? "text" "password") "' "
           "id='settings-api-key' class='settings-input' "
           "placeholder='sk-...' "
           "value='" (or (:openai-key ai) "") "'>"
           "<button class='settings-toggle-btn' id='settings-toggle-key'>"
           (if @show-api-key? "Hide" "Show")
           "</button>"
           "</div>"
           "</div>"
           "<div class='settings-field'>"
           "<label class='settings-label'>Model</label>"
           "<select id='settings-model' class='settings-select'>"
           "<option value='gpt-4o'" (when (= (:model ai) "gpt-4o") " selected") ">GPT-4o</option>"
           "<option value='gpt-4o-mini'" (when (= (:model ai) "gpt-4o-mini") " selected") ">GPT-4o Mini</option>"
           "<option value='gpt-4-turbo'" (when (= (:model ai) "gpt-4-turbo") " selected") ">GPT-4 Turbo</option>"
           "</select>"
           "</div>"))

        ;; Groq
        (when (= provider :groq)
          (str
           "<div class='settings-field'>"
           "<label class='settings-label'>API Key</label>"
           "<div class='settings-api-key-row'>"
           "<input type='" (if @show-api-key? "text" "password") "' "
           "id='settings-api-key' class='settings-input' "
           "placeholder='gsk_...' "
           "value='" (or (:groq-key ai) "") "'>"
           "<button class='settings-toggle-btn' id='settings-toggle-key'>"
           (if @show-api-key? "Hide" "Show")
           "</button>"
           "</div>"
           "</div>"
           "<div class='settings-field'>"
           "<label class='settings-label'>Model</label>"
           "<input type='text' id='settings-groq-model' class='settings-input' "
           "value='" (or (:groq-model ai) "") "' "
           "placeholder='llama-3.3-70b-versatile'>"
           "<div class='settings-hint'>e.g. llama-3.3-70b-versatile, llama-3.1-8b-instant</div>"
           "</div>"))

        ;; Ollama
        (when (= provider :ollama)
          (let [status @settings/ollama-status]
            (str
             "<div class='settings-field'>"
             "<label class='settings-label'>Ollama URL</label>"
             "<div class='settings-api-key-row'>"
             "<input type='text' id='settings-ollama-url' class='settings-input' "
             "value='" (or (:ollama-url ai) "http://localhost:11434") "'>"
             "<button class='settings-check-btn' id='settings-ollama-check'>"
             (if (:checking status) "Checking..." "Check")
             "</button>"
             "</div>"
             "</div>"
             ;; Connection status
             (when (some? (:connected status))
               (str "<div class='settings-ollama-status'>"
                    (if (:connected status)
                      (str "<span class='connected'>✓ Connected</span>"
                           " — " (count (:models status)) " models found")
                      "<span class='disconnected'>✗ Not connected</span>")
                    "</div>"))
             ;; Model
             "<div class='settings-field'>"
             "<label class='settings-label'>Model</label>"
             (if (and (:connected status) (seq (:models status)))
               ;; Dropdown with discovered models
               (str "<select id='settings-ollama-model' class='settings-select'>"
                    (apply str (map (fn [m]
                                      (str "<option value='" m "'"
                                           (when (= m (:ollama-model ai)) " selected")
                                           ">" m "</option>"))
                                    (:models status)))
                    "</select>")
               ;; Text input
               (str "<input type='text' id='settings-ollama-model' class='settings-input' "
                    "value='" (or (:ollama-model ai) "") "' "
                    "placeholder='llama3'>"))
             "</div>")))

        ;; Google Gemini
        (when (= provider :google)
          (let [valid-models #{"gemini-2.5-flash" "gemini-2.5-flash-lite" "gemini-2.5-pro"}
                gm (or (:google-model ai) "gemini-2.5-flash")]
            (when-not (contains? valid-models gm)
              (settings/set-ai-setting! :google-model "gemini-2.5-flash")))
          (str
           "<div class='settings-field'>"
           "<label class='settings-label'>API Key</label>"
           "<div class='settings-api-key-row'>"
           "<input type='" (if @show-api-key? "text" "password") "' "
           "id='settings-api-key' class='settings-input' "
           "placeholder='AIza...' "
           "value='" (or (:google-key ai) "") "'>"
           "<button class='settings-toggle-btn' id='settings-toggle-key'>"
           (if @show-api-key? "Hide" "Show")
           "</button>"
           "</div>"
           "</div>"
           "<div class='settings-field'>"
           "<label class='settings-label'>Model</label>"
           "<select id='settings-google-model' class='settings-select'>"
           "<option value='gemini-2.5-flash'" (when (= (:google-model ai) "gemini-2.5-flash") " selected") ">Gemini 2.5 Flash</option>"
           "<option value='gemini-2.5-flash-lite'" (when (= (:google-model ai) "gemini-2.5-flash-lite") " selected") ">Gemini 2.5 Flash Lite</option>"
           "<option value='gemini-2.5-pro'" (when (= (:google-model ai) "gemini-2.5-pro") " selected") ">Gemini 2.5 Pro</option>"
           "</select>"
           "</div>"))

        ;; Tier dropdown (shown for all providers)
        (let [current-tier (or (:tier ai) :auto)
              detected (settings/get-detected-tier)
              effective (settings/get-effective-tier)
              tier-name (fn [t] (case t :tier-1 "Tier 1" :tier-2 "Tier 2" :tier-3 "Tier 3" "?"))
              mismatch? (and (not= current-tier :auto)
                             (not= current-tier detected)
                             ;; Warn if forcing higher tier on weaker model
                             (> (.indexOf [:tier-1 :tier-2 :tier-3] current-tier)
                                (.indexOf [:tier-1 :tier-2 :tier-3] detected)))]
          (str
           "<div class='settings-field'>"
           "<label class='settings-label'>Tier</label>"
           "<select id='settings-tier' class='settings-select'>"
           "<option value='auto'" (when (= current-tier :auto) " selected") ">Auto</option>"
           "<option value='tier-1'" (when (= current-tier :tier-1) " selected") ">Tier 1 (code only)</option>"
           "<option value='tier-2'" (when (= current-tier :tier-2) " selected") ">Tier 2 (guided)</option>"
           "<option value='tier-3'" (when (= current-tier :tier-3) " selected") ">Tier 3 (full)</option>"
           "</select>"
           "<div class='settings-hint'>"
           "Detected: " (tier-name detected)
           " | Using: " (tier-name effective)
           (when mismatch? " ⚠ may produce poor results")
           "</div>"
           "</div>"))

        ;; Test Connection button (shown for all providers when configured)
        (let [conn @settings/connection-status]
          (str
           "<div class='settings-field settings-test-row'>"
           "<button class='settings-test-btn' id='settings-test-connection'"
           (when (or (:testing conn) (not (settings/ai-configured?))) " disabled") ">"
           (if (:testing conn) "Testing..." "Test Connection")
           "</button>"
           (case (:result conn)
             :ok "<span class='settings-test-ok'>Connected</span>"
             :error (str "<span class='settings-test-error'>" (or (:error conn) "Failed") "</span>")
             "")
           "<button class='settings-test-btn' id='settings-edit-prompts'>Edit Prompts</button>"
           "</div>"))))

     ;; Display section — curve resolution (affects all curve operations globally)
     (let [stored (settings/get-curve-resolution)
           mode (when stored (:mode stored))
           current-n (cond
                       (and stored (= :n mode)) (:value stored)
                       :else 64)
           custom-mode? (and stored (not= :n mode))]
       (str
        "<h3 class='settings-section-header'>Display</h3>"
        "<div class='settings-field'>"
        "<label class='settings-label'>Curve resolution</label>"
        "<div class='settings-api-key-row'>"
        "<input type='number' id='settings-curve-resolution' class='settings-input' "
        "min='4' max='256' step='1' "
        "value='" current-n "'>"
        "<button class='settings-toggle-btn' id='settings-curve-resolution-reset'"
        (when (nil? stored) " disabled") ">"
        "Default"
        "</button>"
        "</div>"
        "<div class='settings-hint'>"
        "Number of segments per full circle. Affects spheres, cylinders, "
        "lofts, fillets, and most curve operations. Higher = smoother but "
        "more triangles. Built-in default: 64."
        (when custom-mode?
          (str " <strong>Custom <code>:" (name mode) "</code> mode active</strong> "
               "(set via script). Saving here will switch to <code>:n</code> mode."))
        "</div>"
        "</div>"))

     ;; Viewport section — capture the current camera angle as the reset/framing view
     (let [custom? (some? (settings/get-reset-view-dir))
           lights (or (settings/get-viewport-lights) (viewport/default-light-config))
           ;; Azimuth i·45° around the vertical (Z) axis, world direction.
           light-labels ["+X" "+X +Y" "+Y" "−X +Y" "−X" "−X −Y" "−Y" "+X −Y"]
           light-checks (apply str
                               (for [i (range 8)]
                                 (str "<label class='settings-light-toggle'>"
                                      "<input type='checkbox' id='settings-light-" i "'"
                                      (when (nth lights i false) " checked") ">"
                                      " <span>" (nth light-labels i) "</span>"
                                      "</label>")))]
       (str
        "<h3 class='settings-section-header'>Viewport</h3>"
        "<div class='settings-field'>"
        "<label class='settings-label'>Reset view angle</label>"
        "<div class='settings-api-key-row'>"
        "<button class='settings-toggle-btn' id='settings-capture-reset-view'>"
        "Use current view"
        "</button>"
        "<button class='settings-toggle-btn' id='settings-reset-view-default'"
        (when-not custom? " disabled") ">"
        "Default"
        "</button>"
        "</div>"
        "<div class='settings-hint'>"
        "Orient the viewport the way you like, then click <strong>Use current "
        "view</strong> to make that angle the one used when fitting new results "
        "and when you press Reset. Only the viewing direction is stored (up stays "
        "vertical); distance keeps auto-fitting to the model."
        "</div>"
        "</div>"
        "<div class='settings-field'>"
        "<label class='settings-label'>Lights</label>"
        "<div class='settings-lights-grid'>" light-checks "</div>"
        "<div class='settings-hint'>"
        "Directional lights arranged radially around the vertical axis (45° "
        "apart, raised 30°), each aimed at the centre — toggle which are on. A "
        "headlight always follows the camera, so the side you face is lit. The "
        "default view looks from −X −Y, so +X +Y is behind the model."
        "</div>"
        "</div>"))

     ;; Fonts section — registry of ids usable from code via :font :id
     (let [entries (text/list-registered-fonts)
           desktop? (fonts-storage/supported?)]
       (str
        "<h3 class='settings-section-header'>Fonts</h3>"
        "<div class='settings-field'>"
        "<div class='settings-hint' style='margin-bottom:8px'>"
        "Registered font ids — pass to code as <code>:font :id</code> "
        "(e.g. <code>(extrude-text \"Hi\" :font :roboto-mono)</code>)."
        "</div>"
        "<table class='settings-fonts-table' "
        "style='width:100%;border-collapse:collapse;font-size:13px'>"
        (apply str
               (for [{:keys [id label builtin? filename]} entries]
                 (str "<tr style='border-bottom:1px solid #2a2a2a'>"
                      "<td style='padding:4px 8px 4px 0;font-family:monospace'>"
                      ":" (name id) "</td>"
                      "<td style='padding:4px 8px;color:#999'>" (or label "") "</td>"
                      "<td style='padding:4px 8px;color:#666;font-size:11px'>"
                      (if builtin? "built-in" (or filename "")) "</td>"
                      "<td style='padding:4px 0;text-align:right'>"
                      (if builtin?
                        ""
                        (str "<button class='settings-toggle-btn' "
                             "data-font-id='" (name id) "' "
                             "data-action='delete-font'>Remove</button>"))
                      "</td>"
                      "</tr>")))
        "</table>"
        (if desktop?
          (str "<div style='margin-top:12px;padding-top:8px;"
               "border-top:1px solid #2a2a2a'>"
               "<div class='settings-hint' style='margin-bottom:6px'>"
               "Add a custom font (.ttf / .otf):</div>"
               "<div style='display:flex;gap:6px;align-items:center;flex-wrap:wrap'>"
               "<input type='text' id='settings-font-id' class='settings-input' "
               "placeholder='id (e.g. inter)' style='flex:0 0 140px'>"
               "<input type='text' id='settings-font-label' class='settings-input' "
               "placeholder='label (e.g. Inter Bold)' style='flex:1 1 160px'>"
               "<input type='file' id='settings-font-file' "
               "accept='.ttf,.otf,.woff,.woff2' style='flex:1 1 200px'>"
               "<button id='settings-font-add' class='settings-toggle-btn'>Add</button>"
               "</div>"
               "<div id='settings-font-status' class='settings-hint' "
               "style='margin-top:6px;min-height:1em'></div>"
               "</div>")
          (str "<div class='settings-hint' style='margin-top:8px'>"
               "Custom fonts require the desktop app. "
               "Built-in <code>:roboto</code> and <code>:roboto-mono</code> "
               "are available on the web."
               "</div>"))
        "</div>"))

     ;; Accessibility section (always visible, not dependent on AI)
     "<h3 class='settings-section-header'>Accessibility</h3>"
     "<div class='settings-field'>"
     "<label class='settings-checkbox-row'>"
     "<input type='checkbox' id='settings-audio-feedback' "
     (when (settings/audio-feedback?) "checked") ">"
     "Audio feedback (sound on eval)"
     "</label>"
     "</div>")))

(defn- attach-settings-listeners
  "Attach event listeners to the settings modal content.
   Calls re-render on changes that affect the form structure."
  [modal re-render]
  ;; Enable checkbox
  (when-let [el (.querySelector modal "#settings-ai-enabled")]
    (.addEventListener el "change"
                       (fn [e]
                         (settings/set-ai-setting! :enabled (.. e -target -checked))
                         (re-render))))
  ;; Provider dropdown
  (when-let [el (.querySelector modal "#settings-provider")]
    (.addEventListener el "change"
                       (fn [e]
                         (settings/set-ai-setting! :provider (keyword (.. e -target -value)))
                         (re-render))))
  ;; API key input (Anthropic/OpenAI/Groq)
  (when-let [el (.querySelector modal "#settings-api-key")]
    (.addEventListener el "input"
                       (fn [e]
                         (let [provider (settings/get-ai-setting :provider)
                               p (if (keyword? provider) (name provider) (str provider))
                               key-field (cond
                                           (= p "anthropic") :anthropic-key
                                           (= p "openai") :openai-key
                                           (= p "groq") :groq-key
                                           (= p "google") :google-key
                                           :else nil)]
                           (when key-field
                             (settings/set-ai-setting! key-field (.. e -target -value)))))))
  ;; Toggle API key visibility
  (when-let [el (.querySelector modal "#settings-toggle-key")]
    (.addEventListener el "click"
                       (fn [_]
                         (swap! show-api-key? not)
                         (re-render))))
  ;; Model dropdown (Anthropic/OpenAI)
  (when-let [el (.querySelector modal "#settings-model")]
    (.addEventListener el "change"
                       (fn [e]
                         (settings/set-ai-setting! :model (.. e -target -value)))))
  ;; Google Gemini model dropdown
  (when-let [el (.querySelector modal "#settings-google-model")]
    (.addEventListener el "change"
                       (fn [e]
                         (settings/set-ai-setting! :google-model (.. e -target -value)))))
  ;; Groq model text input
  (when-let [el (.querySelector modal "#settings-groq-model")]
    (.addEventListener el "input"
                       (fn [e]
                         (settings/set-ai-setting! :groq-model (.. e -target -value)))))
  ;; Ollama URL
  (when-let [el (.querySelector modal "#settings-ollama-url")]
    (.addEventListener el "input"
                       (fn [e]
                         (settings/set-ai-setting! :ollama-url (.. e -target -value)))))
  ;; Ollama check connection
  (when-let [el (.querySelector modal "#settings-ollama-check")]
    (.addEventListener el "click"
                       (fn [_]
                         (-> (settings/check-ollama-connection!)
                             (.then (fn [_] (re-render)))
                             (.catch (fn [_] (re-render)))))))
  ;; Ollama model (select or input)
  (when-let [el (.querySelector modal "#settings-ollama-model")]
    (.addEventListener el (if (= "SELECT" (.-tagName el)) "change" "input")
                       (fn [e]
                         (settings/set-ai-setting! :ollama-model (.. e -target -value)))))
  ;; Tier dropdown
  (when-let [el (.querySelector modal "#settings-tier")]
    (.addEventListener el "change"
                       (fn [e]
                         (settings/set-ai-setting! :tier (keyword (.. e -target -value)))
                         (re-render))))
  ;; Test Connection button
  (when-let [el (.querySelector modal "#settings-test-connection")]
    (.addEventListener el "click"
                       (fn [_]
                         (re-render)
                         (settings/validate-connection!)
                         ;; Poll for result and re-render when done
                         (let [poll-id (atom nil)]
                           (reset! poll-id
                                   (js/setInterval
                                    (fn []
                                      (when-not (:testing @settings/connection-status)
                                        (js/clearInterval @poll-id)
                                        (re-render)))
                                    200))))))
  ;; Edit Prompts button — opens the prompt editor, closes settings
  (when-let [el (.querySelector modal "#settings-edit-prompts")]
    (.addEventListener el "click"
                       (fn [_]
                         ;; Close the settings modal
                         (when-let [ov (.querySelector js/document ".settings-modal-overlay")]
                           (.remove ov))
                         (when-let [m (.querySelector js/document ".settings-modal")]
                           (.remove m))
                         (prompt-panel/open!))))
  ;; Curve resolution input
  (when-let [el (.querySelector modal "#settings-curve-resolution")]
    (.addEventListener el "change"
                       (fn [e]
                         (let [raw (.. e -target -value)
                               n (js/parseInt raw 10)]
                           (when (and (not (js/isNaN n)) (>= n 4) (<= n 256))
                             (settings/set-curve-resolution! {:mode :n :value n})
                             (re-render))))))
  ;; Curve resolution reset (revert to built-in default)
  (when-let [el (.querySelector modal "#settings-curve-resolution-reset")]
    (.addEventListener el "click"
                       (fn [_]
                         (settings/set-curve-resolution! nil)
                         (re-render))))
  ;; Viewport: capture current camera angle as reset/framing direction
  (when-let [el (.querySelector modal "#settings-capture-reset-view")]
    (.addEventListener el "click"
                       (fn [_]
                         (when-let [dir (viewport/capture-reset-view!)]
                           (settings/set-reset-view-dir! (vec dir)))
                         (re-render))))
  ;; Viewport: revert reset view angle to built-in default
  (when-let [el (.querySelector modal "#settings-reset-view-default")]
    (.addEventListener el "click"
                       (fn [_]
                         (settings/set-reset-view-dir! nil)
                         (viewport/set-reset-view-dir! nil)
                         (re-render))))
  ;; Viewport: ring-light on/off toggles. Each change reads all 8 boxes, then
  ;; persists and applies the resulting vector.
  (doseq [i (range 8)]
    (when-let [el (.querySelector modal (str "#settings-light-" i))]
      (.addEventListener el "change"
                         (fn [_]
                           (let [v (vec (for [j (range 8)]
                                          (boolean (when-let [c (.querySelector modal (str "#settings-light-" j))]
                                                     (.-checked c)))))]
                             (settings/set-viewport-lights! v)
                             (viewport/apply-light-config! v))))))
  ;; Audio feedback checkbox (accessibility)
  (when-let [el (.querySelector modal "#settings-audio-feedback")]
    (.addEventListener el "change"
                       (fn [e]
                         (settings/set-audio-feedback! (.. e -target -checked)))))
  ;; Font: Add button — read selected file bytes, parse, register, re-render
  (when-let [add-btn (.querySelector modal "#settings-font-add")]
    (.addEventListener
     add-btn "click"
     (fn [_]
       (let [id-el (.querySelector modal "#settings-font-id")
             label-el (.querySelector modal "#settings-font-label")
             file-el (.querySelector modal "#settings-font-file")
             status-el (.querySelector modal "#settings-font-status")
             id-str (when id-el (.-value id-el))
             label (when label-el (.-value label-el))
             files (when file-el (.-files file-el))
             file (when (and files (pos? (.-length files))) (aget files 0))
             set-status! (fn [msg]
                           (when status-el (set! (.-textContent status-el) msg)))]
         (cond
           (or (str/blank? id-str) (nil? file))
           (set-status! "Provide an id and select a font file.")

           (text/registered? (fonts/id->keyword id-str))
           (set-status! (str "Id :" id-str " is already registered."))

           :else
           (do
             (set-status! "Loading...")
             (-> (.arrayBuffer file)
                 (.then (fn [bytes]
                          (try
                            (fonts/register-custom-font!
                             (fonts/id->keyword id-str)
                             (if (str/blank? label) id-str label)
                             (.-name file)
                             bytes)
                            (re-render)
                            (catch :default e
                              (set-status! (str "Failed: " (.-message e)))))))
                 (.catch (fn [e]
                           (set-status! (str "Read failed: " (.-message e))))))))))))
  ;; Font: per-row Remove buttons
  (doseq [btn (array-seq (.querySelectorAll modal "button[data-action='delete-font']"))]
    (.addEventListener btn "click"
                       (fn [_]
                         (when-let [id-str (.getAttribute btn "data-font-id")]
                           (fonts/unregister-custom-font! (keyword id-str))
                           (re-render))))))

(defn- show-settings-modal
  "Show the Settings modal."
  []
  (let [modal (.createElement js/document "div")
        overlay (.createElement js/document "div")
        close-modal (fn []
                      (.remove overlay)
                      (.remove modal))
        render (fn render []
                 (let [content-el (.querySelector modal ".settings-modal-content")]
                   (when content-el
                     (set! (.-innerHTML content-el)
                           (str (render-settings-content)
                                "<button class='settings-close-btn' id='settings-close'>Close</button>"))
                     ;; Attach listeners
                     (attach-settings-listeners modal render)
                     ;; Close button
                     (when-let [btn (.querySelector modal "#settings-close")]
                       (.addEventListener btn "click" close-modal)))))]
    ;; Setup overlay
    (set! (.-className overlay) "settings-modal-overlay")
    (.addEventListener overlay "click" close-modal)
    ;; Setup modal
    (set! (.-className modal) "settings-modal")
    (set! (.-innerHTML modal) "<div class='settings-modal-content'></div>")
    (.appendChild js/document.body overlay)
    (.appendChild js/document.body modal)
    ;; Initial render
    (render)))

(defn- setup-settings
  "Setup settings button click handler."
  []
  (when-let [btn (.getElementById js/document "btn-settings")]
    (.addEventListener btn "click" (fn [_] (show-settings-modal)))))

;; ============================================================
;; Manual
;; ============================================================

(defn- update-manual-visibility
  "Show or hide the manual panel based on manual state."
  []
  (let [editor-section (.getElementById js/document "explicit-section")
        repl-section (.getElementById js/document "repl-section")
        section-divider (.querySelector js/document ".section-divider")
        workspace-panel (.getElementById js/document "workspace-panel")
        library-panel (.getElementById js/document "library-panel")
        manual-container (.getElementById js/document "manual-container")]
    (if (manual/open?)
      ;; Show manual, hide editor
      (do
        (when editor-section (set! (.-style.display editor-section) "none"))
        (when workspace-panel (set! (.-style.display workspace-panel) "none"))
        (when library-panel (set! (.-style.display library-panel) "none"))
        (when repl-section (set! (.-style.display repl-section) "none"))
        (when section-divider (set! (.-style.display section-divider) "none"))
        (when manual-container
          (set! (.-style.display manual-container) "flex")
          ;; Render manual content
          (when-let [panel @manual-panel]
            ((:render panel)))))
      ;; Hide manual, show editor
      (do
        (when editor-section (set! (.-style.display editor-section) "flex"))
        (when workspace-panel (set! (.-style.display workspace-panel) "flex"))
        (when library-panel (set! (.-style.display library-panel) "flex"))
        (when repl-section (set! (.-style.display repl-section) "flex"))
        (when section-divider (set! (.-style.display section-divider) "block"))
        (when manual-container (set! (.-style.display manual-container) "none"))))))

(defn- run-manual-code
  "Execute code from the manual and show result in viewport."
  [code]
  ;; Clear previous geometry, animations, and rulers, evaluate fresh
  (registry/clear-all!)
  (anim/clear-all!)
  (viewport/clear-rulers!)
  (let [result (repl/evaluate-definitions code)]
    (if-let [error (:error result)]
      (show-error error)
      (do
        (hide-error)
        ;; Show print output in REPL history if any
        (when-let [print-output (:print-output result)]
          (add-script-output print-output))
        (when-let [render-data (repl/extract-render-data result)]
          (registry/set-lines! (:lines render-data))
          (registry/set-stamps! (or (:stamps render-data) []))
          (registry/set-definition-meshes! (:meshes render-data)))
        (registry/refresh-viewport! true)  ; Reset camera to show result
        (update-turtle-indicator)
        (rebuild-turtle-dropdown!)))))

(defn- copy-manual-code
  "Open a manual example in a fresh workspace and close the manual. The user's
   current work stays in its own workspace — it is never overwritten, so no
   confirmation is needed (replaces the old overwrite-confirm modal)."
  [code]
  (when @editor-view
    (open-code-in-new-workspace! code)
    (manual/close-manual!)))

(defn- setup-manual
  "Setup the manual panel and button."
  []
  ;; Create manual container in editor panel
  (let [editor-panel (.getElementById js/document "editor-panel")
        container (.createElement js/document "div")]
    (set! (.-id container) "manual-container")
    (set! (.-className container) "manual-container")
    (set! (.-style.display container) "none")
    (.appendChild editor-panel container)
    ;; Create manual panel
    (let [panel (manual-ui/create-manual-panel)]
      (reset! manual-panel panel)
      (.appendChild container (:element panel))))
  ;; Set callbacks for Run/Copy buttons
  (manual-ui/set-callbacks!
   {:on-run run-manual-code
    :on-copy copy-manual-code})
  ;; T-009: open a symbol's Reference card from the editor tooltip and from
  ;; reference links (ref:NAME / *.md) in guide and card prose.
  (cm/set-reference-handler! ref-browser/open-card!)
  (manual-draft/set-link-handler! ref-browser/open-card!)
  ;; Watch manual state for changes
  (manual/add-state-watcher! :ui-update
                             (fn [_ _] (update-manual-visibility)))
  ;; Setup Manual button
  (when-let [manual-btn (.getElementById js/document "btn-manual")]
    (.addEventListener manual-btn "click"
                       (fn [_] (manual/toggle-manual!))))
  ;; Setup line numbers toggle button
  (when-let [ln-btn (.getElementById js/document "btn-line-numbers")]
    (.addEventListener ln-btn "click"
                       (fn [_]
                         (let [on? (cm/toggle-line-numbers!)]
                           (if on?
                             (.add (.-classList ln-btn) "active")
                             (.remove (.-classList ln-btn) "active")))))))

;; ============================================================
;; Workspaces (first-class editor documents)
;; ============================================================

;; Library set ↔ workspace bridge. The GLOBAL active-library list (read on every
;; run by load-active-libraries) is treated as a projection of the current
;; workspace. We project workspace→global when a workspace becomes current, and
;; capture global→workspace when the user toggles libraries in the panel.

(defn- apply-workspace-libraries!
  "Project the current workspace's library set onto the global active list. A
   legacy workspace (no :libraries yet) instead ADOPTS the current global list
   (transparent migration). Resets the SCI context when the set actually changed
   so the REPL and next run see the right libraries (no automatic re-run)."
  []
  (let [libs (workspace/current-libraries)]
    (if (nil? libs)
      (workspace/set-current-libraries! (lib-storage/get-active-libraries))
      (when (not= (vec libs) (vec (lib-storage/get-active-libraries)))
        (lib-storage/set-active-libraries! (vec libs))
        (repl/reset-ctx!)))
    (lib-panel/render!)))

(defn- capture-active-libraries!
  "Record the global active-library list onto the current workspace (after the
   user toggles libraries in the panel), and refresh the dirty indicator."
  []
  (workspace/set-current-libraries! (lib-storage/get-active-libraries))
  (workspace-panel/on-content-changed!))

(defn- switch-workspace!
  "Persist the current editor content, switch the current pointer to `id`,
   load that workspace's content into the editor, and project the target
   workspace's library set."
  [id]
  (when (and @editor-view (not= id (workspace/current-id)))
    (modal/force-close-active!)             ;; end any modal session before swapping the buffer
    (save-to-storage)                       ;; persist the doc we're leaving
    (workspace/set-current! id)
    (when-let [w (workspace/get-workspace id)]
      (cm/set-value @editor-view (:content w)))
    (workspace-panel/render!)
    (apply-workspace-libraries!)))

(defn- open-code-in-new-workspace!
  "Park the current work (it already lives in its workspace), create a fresh
   ephemeral workspace holding `code`, and switch to it. Used by the manual
   'Edit example' flow so the user's work is never overwritten. The new
   workspace inherits the current library set."
  [code]
  (when @editor-view
    (modal/force-close-active!)
    (save-to-storage)
    (let [id (workspace/new-workspace! code)]
      (workspace/set-current! id)
      (cm/set-value @editor-view code)
      (workspace-panel/render!)
      (apply-workspace-libraries!))))

(defn- close-workspace!
  "Close workspace `id`. If it is the current one, move to a neighbour (creating
   a fresh empty workspace if none remain) WITHOUT persisting the closed content."
  [id]
  (if (= id (workspace/current-id))
    (let [remaining (remove #(= (:id %) id) (workspace/list-workspaces))
          target-id (or (:id (first remaining)) (workspace/new-workspace! ""))]
      (modal/force-close-active!)           ;; closing the current doc replaces the buffer
      (workspace/remove-workspace! id)
      (workspace/set-current! target-id)
      (when (and @editor-view (workspace/get-workspace target-id))
        (cm/set-value @editor-view (:content (workspace/get-workspace target-id))))
      (workspace-panel/render!)
      (apply-workspace-libraries!))
    (do (workspace/remove-workspace! id)
        (workspace-panel/render!))))

(defn- open-file-in-workspace!
  "Create a new workspace from `content` (the bare code body), switch to it, and
   (on desktop) bind it to `:path`, or name it `:name` (web). `:libraries` is the
   set parsed from the file header (nil for an external file → inherit current)."
  [content {:keys [path name libraries]}]
  (when @editor-view
    (modal/force-close-active!)
    (save-to-storage)
    (let [id (workspace/new-workspace! content libraries)
          ws-libs (:libraries (workspace/get-workspace id))]
      (cond
        path (workspace/bind-file! id path content ws-libs (ws-basename path))
        name (workspace/rename! id name))
      (workspace/set-current! id)
      (cm/set-value @editor-view content)
      (workspace-panel/render!)
      (apply-workspace-libraries!))))

(defn- setup-workspace-panel
  "Setup the Workspaces panel — switching/creating/renaming/closing documents."
  []
  (workspace-panel/setup!
   {:switch-to switch-workspace!
    :close close-workspace!}))

;; ============================================================
;; Library Panel
;; ============================================================

(defn- setup-library-panel
  "Setup the library panel with callbacks for editor integration."
  []
  (lib-panel/setup!
   {:get-editor-content (fn [] (when @editor-view (cm/get-value @editor-view)))
    :set-editor-content (fn [content]
                          (when @editor-view
                            (cm/set-value @editor-view content)))
    :on-edit (fn [lib-name]
               (lib-panel/enter-edit-mode! lib-name))
    :on-change (fn []
                 ;; Record the new active set onto the current workspace, then
                 ;; reset the SCI context and re-evaluate definitions.
                 (capture-active-libraries!)
                 (repl/reset-ctx!)
                 (evaluate-definitions))}))

;; ============================================================
;; Voice Input Integration
;; ============================================================

(defn- ai-insert-code
  "Insert code into editor or REPL based on target and position."
  [{:keys [target code position]}]
  (case target
    :script
    (when-let [view @editor-view]
      ;; If an AI block already exists, replace it regardless of position
      (if (cm/find-ai-block view)
        (cm/replace-ai-block view code)
        ;; No existing block — insert at requested position with markers
        (let [block (str ";; >>> AI\n" code "\n;; <<< AI")
              insert-pos (case position
                           :cursor (.. view -state -selection -main -head)
                           :end (.. view -state -doc -length)
                           :after-current-form
                           (if-let [form (cm/get-form-at-cursor view)]
                             (:to form)
                             (.. view -state -selection -main -head))
                           :before-current-form
                           (if-let [form (cm/get-form-at-cursor view)]
                             (:from form)
                             (.. view -state -selection -main -head))
                           :append-child
                           (if-let [form (cm/get-form-at-cursor view)]
                             (dec (:to form))  ; before closing bracket
                             (.. view -state -selection -main -head))
                           ;; Default: at cursor
                           (.. view -state -selection -main -head))
              actual-code (case position
                            :after-current-form (str "\n" block)
                            :before-current-form (str block "\n")
                            :append-child (str " " block)
                            block)]
          ;; Insert the block
          (case position
            :cursor (cm/insert-at-cursor view actual-code)
            :end (cm/insert-at-end view actual-code)
            (:after-current-form :before-current-form :append-child)
            (do
              (cm/set-cursor-position view {:pos insert-pos})
              (cm/insert-at-cursor view actual-code))
            ;; Default
            (cm/insert-at-cursor view actual-code))))
      ;; Update AI focus to the inserted form (only when voice active)
      (maybe-update-ai-focus! view)
      ;; Keep focus on editor
      (cm/focus view)
      ;; Auto-save
      (save-to-storage)
      (send-script-debounced))

    :repl
    (when-let [input-el @repl-input-el]
      (case position
        :cursor (let [start (.-selectionStart input-el)
                      value (.-value input-el)]
                  (set! (.-value input-el)
                        (str (subs value 0 start) code (subs value start))))
        :end (set! (.-value input-el)
                   (str (.-value input-el) code))
        ;; Default: replace all
        (set! (.-value input-el) code)))

    (js/console.warn "AI insert: unknown target" target)))

(defn- ai-edit-code
  "Edit code based on operation and target."
  [{:keys [operation target value element transform-type from to]}]
  (when-let [view @editor-view]
    ;; Direct range replacement (used by F1 autocomplete)
    (if (= operation :replace-range)
      (when (and from to value)
        (cm/replace-range view from to value))

      (do (let [;; Check if target references "it/lo/last" - meaning previous form
                ref (:ref target)
                use-previous? (and (= (:type target) "form")
                                   (contains? #{"it" "lo" "last" "questo" "this"} ref))
              ;; Get the appropriate form based on target type
                the-form (if use-previous?
                           (cm/get-previous-form view)
                           (case (:type target)
                             "form" (cm/get-element-at-cursor view)
                             "word" (cm/get-word-at-cursor view)
                             "selection" (cm/get-selection view)
                             nil))]

            (when the-form
              (case operation
                :replace
                (cm/replace-range view (:from the-form) (:to the-form) value)

                :delete
                (let [;; Find parent form BEFORE deleting so we can position cursor there
                      parent-pos (let [from (:from the-form)]
                                   (when (pos? from)
                               ;; Temporarily move cursor before the form to find parent
                                     (cm/set-cursor-position view {:pos (max 0 (dec from))})
                                     (when-let [parent (cm/get-form-at-cursor view)]
                                       (inc (:from parent)))))]
                  (cm/delete-range view (:from the-form) (:to the-form))
            ;; Position cursor in parent form
                  (when parent-pos
                    (cm/set-cursor-position view {:pos (min parent-pos
                                                            (.. view -state -doc -length))})))

                :wrap
                (when value
                  (let [wrapped (str/replace value "$" (:text the-form))]
                    (cm/replace-range view (:from the-form) (:to the-form) wrapped)))

                :unwrap
                (let [text (:text the-form)
                      first-ch (when (seq text) (.charAt text 0))
                      last-ch (when (seq text) (.charAt text (dec (count text))))]
                  (when (and first-ch last-ch
                             (#{\( \[ \{} first-ch)
                             (#{\) \] \}} last-ch))
                    (let [inner (subs text 1 (dec (count text)))
                    ;; For () forms, strip the head (fn name); for [] and {}, keep all content
                          content (if (= first-ch \()
                                    (str/trim (str/replace-first inner #"^\S+\s*" ""))
                                    (str/trim inner))]
                      (cm/replace-range view (:from the-form) (:to the-form) content))))

                :replace-structured
                (when (and element value)
                  (when-let [new-form (cm/replace-form-element (:text the-form) element value)]
                    (cm/replace-range view (:from the-form) (:to the-form) new-form)))

                :insert-structured
                (when (and element value)
                  (when-let [new-form (cm/insert-form-element (:text the-form) element value)]
                    (cm/replace-range view (:from the-form) (:to the-form) new-form)))

                :delete-structured
                (when element
                  (when-let [new-form (cm/delete-form-element (:text the-form) element)]
                    (cm/replace-range view (:from the-form) (:to the-form) new-form)))

                :barf
                (cm/barf-form view)

                :slurp
                (cm/slurp-form view)

                :raise
                (let [form-text (:text the-form)
                      form-from (:from the-form)]
            ;; Find the parent form
                  (cm/set-cursor-position view {:pos (max 0 (dec form-from))})
                  (when-let [parent (cm/get-form-at-cursor view)]
                    (cm/replace-range view (:from parent) (:to parent) form-text)
              ;; Position cursor at the raised form
                    (cm/set-cursor-position view {:pos (:from parent)})))

                :join
          ;; Merge current atom with next sibling (e.g. "do" + "times" → "dotimes")
                (when-let [next-form (cm/get-next-form view)]
                  (let [joined (str (:text the-form) (:text next-form))]
                    (cm/replace-range view (:from the-form) (:to next-form) joined)
                    (cm/set-cursor-position view {:pos (:from the-form)})))

                :transform
                (let [text (:text the-form)
                      lang (voice-state/get-language)
                      new-text
                      (case transform-type
                        :keyword   (str ":" text)
                        :symbol    (when (str/starts-with? text ":")
                                     (subs text 1))
                        :hash      (str "#" text)
                        :deref     (str "@" text)
                        :capitalize (when (seq text)
                                      (str (str/upper-case (subs text 0 1)) (subs text 1)))
                        :uppercase (str/upper-case text)
                        :number    nil  ; handled specially below
                        nil)]
                  (if (= transform-type :number)
              ;; Number transform: word→digit, or "minus"+"N"→"-N"
                    (let [neg-words (get voice-i18n/negative-words lang #{})
                          nums (get voice-i18n/numbers lang {})
                          lower (str/lower-case text)]
                      (if (contains? neg-words lower)
                  ;; Current atom is "minus"/"meno" — negate next sibling
                        (when-let [next-form (cm/get-next-form view)]
                          (let [next-text (:text next-form)
                                n (or (get nums (str/lower-case next-text))
                                      (when (re-matches #"\d+" next-text)
                                        (js/parseInt next-text 10)))]
                            (when n
                              (cm/replace-range view (:from the-form) (:to next-form) (str "-" n))
                              (cm/set-cursor-position view {:pos (:from the-form)}))))
                  ;; Try word→digit conversion
                        (when-let [n (get nums lower)]
                          (cm/replace-range view (:from the-form) (:to the-form) (str n))
                          (cm/set-cursor-position view {:pos (:from the-form)}))))
              ;; All other transforms: simple text replacement
                    (when new-text
                      (cm/replace-range view (:from the-form) (:to the-form) new-text)
                      (cm/set-cursor-position view {:pos (:from the-form)}))))

                (js/console.warn "AI edit: unknown operation" operation)))

            (when-not the-form
              (js/console.warn "AI edit: no form found" (if use-previous? "(looking for previous)" ""))))))

    ;; Update AI focus after edit (only when voice active)
    (maybe-update-ai-focus! view)

    ;; Auto-save after edit
    (save-to-storage)
    (send-script-debounced)))

(defn- voice-active-or-continuous?
  "Check if voice is listening or in continuous mode."
  []
  (or (voice/voice-active?) (voice/continuous-active?)))

(defn- maybe-update-ai-focus!
  "Update AI focus highlight only when voice is active (listening or continuous mode).
   Clears focus when voice is inactive, to avoid distraction during manual editing."
  ([] (if (voice-active-or-continuous?)
        (cm/update-ai-focus!)
        (cm/clear-ai-focus!)))
  ([view] (if (voice-active-or-continuous?)
            (cm/update-ai-focus! view)
            (cm/clear-ai-focus! view))))

(defn- ai-navigate
  "Navigate cursor based on direction and mode."
  [{:keys [direction mode count]}]
  (when-let [view @editor-view]
    (let [n (or count 1)]
      (dotimes [_ n]
        (case mode
          :structure
          (case direction
            :next (let [current (cm/get-element-at-cursor view)
                        next-form (cm/get-next-form view)]
                    (js/console.log "nav :next — current:" (pr-str (:text current))
                                    "next:" (pr-str (:text next-form)))
                    (when next-form
                      (cm/set-cursor-position view {:pos (:from next-form)})))
            :prev (when-let [prev-form (cm/get-previous-form view)]
                    (cm/set-cursor-position view {:pos (:from prev-form)}))
            :parent (when-let [{:keys [from]} (cm/get-form-at-cursor view)]
                      (let [current-pos (.. view -state -selection -main -head)]
                        (if (= current-pos from)
                          ;; Already at opening bracket — go to parent
                          (when (pos? from)
                            (cm/set-cursor-position view {:pos (dec from)})
                            (when-let [parent (cm/get-form-at-cursor view)]
                              (cm/set-cursor-position view {:pos (:from parent)})))
                          ;; Inside the form — go to its opening bracket
                          (cm/set-cursor-position view {:pos from}))))
            :child (when-let [child (cm/get-first-child-form view)]
                     (let [target-pos (:from child)
                           current-pos (.. view -state -selection -main -head)]
                       (if (= target-pos current-pos)
                         ;; Already at first child — jump to next sibling
                         (when-let [nxt (cm/get-next-form view)]
                           (cm/set-cursor-position view {:pos (:from nxt)}))
                         (cm/set-cursor-position view {:pos target-pos}))))
            :start (cm/move-cursor view :start)
            :end (cm/move-cursor view :end)
            nil)

          ;; Default: text mode
          (cm/move-cursor view direction))))
    ;; Update AI focus to reflect new position (only when voice active)
    (maybe-update-ai-focus! view)
    (sync-voice-state)))

(defn- sync-voice-state
  "Sync current editor state to voice state for context."
  []
  (when (voice-state/enabled?)
    (when-let [view @editor-view]
      ;; Update buffer
      (voice-state/update-buffer! :script (cm/get-value view))
      (when-let [repl-el @repl-input-el]
        (voice-state/update-buffer! :repl (.-value repl-el)))

      ;; Update cursor and form info
      (let [cursor (cm/get-cursor-position view)
            form (cm/get-element-at-cursor view)
            selection (cm/get-selection view)]
        (voice-state/update-cursor! {:line (:line cursor)
                                     :col (:col cursor)
                                     :current-form (:text form)
                                     :selection (when (not= (:from selection) (:to selection))
                                                  (:text selection))}))

      ;; Update scene info
      (voice-state/update-scene! {:meshes (vec (registry/registered-names))
                                  :visible (vec (registry/visible-names))
                                  :shapes (vec (registry/shape-names))
                                  :paths (vec (registry/path-names))}))))

(defn- setup-voice-ui
  "Setup voice button with push-to-talk behavior."
  []
  (when-let [toolbar (.getElementById js/document "viewport-toolbar")]
    (let [mic-btn (.createElement js/document "button")]
      (set! (.-id mic-btn) "btn-voice")
      (set! (.-className mic-btn) "toolbar-button")
      (set! (.-textContent mic-btn) "MIC")
      (set! (.-title mic-btn) "Push to talk — hold or click")

      ;; Track if we're doing push-to-talk (hold) or toggle (click)
      (let [press-start (atom nil)
            hold-threshold 200]

        (.addEventListener mic-btn "mousedown"
                           (fn [e]
                             (.preventDefault e)
                             (.stopPropagation e)
            ;; Ignore left-click mousedown when in continuous mode
            ;; (continuous mode is toggled by right-click only)
                             (when-not (voice/continuous-active?)
                               (reset! press-start (js/Date.now))
                               (voice/start-listening!)
                               (.add (.-classList mic-btn) "active")
                               (js/setTimeout #(when @editor-view (cm/focus @editor-view)) 50))))

        (.addEventListener mic-btn "mouseup"
                           (fn [_]
                             (when-let [start @press-start]
                               (reset! press-start nil)
                               (let [held (- (js/Date.now) start)]
                                 (when (> held hold-threshold)
                                   (voice/stop-listening!)
                                   (.remove (.-classList mic-btn) "active")))
                               (when @editor-view (cm/focus @editor-view)))))

        (.addEventListener mic-btn "mouseleave"
                           (fn [_]
                             (when @press-start
                               (reset! press-start nil)
                               (when (voice/voice-active?)
                                 (voice/stop-listening!))
                               (.remove (.-classList mic-btn) "active")))))

      ;; Right-click = toggle continuous listening mode
      (.addEventListener mic-btn "contextmenu"
                         (fn [e]
                           (.preventDefault e)
                           (.stopPropagation e)
                           (if (voice/continuous-active?)
                             (voice/stop-continuous-listening!)
                             (voice/start-continuous-listening!))
                           (when @editor-view (cm/focus @editor-view))))

      ;; Watch state to update button appearance
      (add-watch voice-state/voice-state :voice-button-update
                 (fn [_ _ old-state new-state]
                   (let [was-listening (get-in old-state [:voice :listening?])
                         is-listening (get-in new-state [:voice :listening?])
                         was-continuous (get-in old-state [:voice :continuous?])
                         is-continuous (get-in new-state [:voice :continuous?])
                         pending (get-in new-state [:voice :pending-speech])
                         mode (:mode new-state)
                         was-active (or was-listening was-continuous)
                         is-active (or is-listening is-continuous)]
                     (when (not= was-listening is-listening)
                       (if is-listening
                         (.add (.-classList mic-btn) "active")
                         (do
                           (.remove (.-classList mic-btn) "active")
                           (.remove (.-classList mic-btn) "continuous"))))
                     (if is-continuous
                       (.add (.-classList mic-btn) "continuous")
                       (.remove (.-classList mic-btn) "continuous"))
            ;; Show/hide AI focus when voice activation changes
                     (when (and (not was-active) is-active)
                       (when-let [view @editor-view]
                         (cm/update-ai-focus! view)))
                     (when (and was-active (not is-active))
                       (cm/clear-ai-focus!))
            ;; Show mode + status as tooltip
                     (when pending
                       (set! (.-title mic-btn) pending))
                     (when (and (not pending) (not is-listening))
                       (set! (.-title mic-btn) (str "Mode: " (name mode) " — push to talk, right-click for continuous"))))))

      (.appendChild toolbar mic-btn)))

  ;; Create voice status panel
  (let [panel-el (.createElement js/document "div")]
    (set! (.-id panel-el) "voice-status-panel")
    (set! (.-className panel-el) "voice-panel")
    ;; Inject panel CSS
    (let [style-el (.createElement js/document "style")]
      (set! (.-textContent style-el) (voice/get-panel-css))
      (.appendChild js/document.head style-el))
    (.appendChild js/document.body panel-el)

    ;; Update panel content when state changes
    (add-watch voice-state/voice-state :voice-panel-update
               (fn [_ _ _ _new-state]
                 (if (voice-state/enabled?)
                   (do
                     (set! (.-style.display panel-el) "block")
                     (set! (.-innerHTML panel-el) (voice/render-panel-html)))
                   (set! (.-style.display panel-el) "none"))))

    ;; Help interactivity: click delegation on panel
    (.addEventListener panel-el "click"
                       (fn [e]
                         (when (= (voice-state/get-mode) :help)
                           (loop [el (.-target e)]
                             (when (and el (not= el panel-el))
                               (if (.hasAttribute el "data-action")
                                 (let [action (.getAttribute el "data-action")]
                                   (case action
                                     "select-item"
                                     (when-let [idx-str (.getAttribute el "data-index")]
                                       (voice/dispatch-action! :help-select
                                                               {:index (js/parseInt idx-str 10)}))
                                     "browse-tier"
                                     (when-let [ti-str (.getAttribute el "data-tier-index")]
                                       (let [sorted-tiers (sort-by (comp :order val) help-db/tiers)
                                             tier-key (key (nth sorted-tiers (js/parseInt ti-str 10) nil))]
                                         (when tier-key
                                           (voice/dispatch-action! :help-browse {:tier tier-key}))))
                                     "help-prev"
                                     (voice/dispatch-action! :help-prev {})
                                     "help-next"
                                     (voice/dispatch-action! :help-next {})
                                     "help-back"
                                     (voice/dispatch-action! :help-back {})
                                     nil))
                                 (recur (.-parentElement el))))))))

    ;; Help interactivity: mouse wheel pagination on panel
    (.addEventListener panel-el "wheel"
                       (fn [e]
                         (when (= (voice-state/get-mode) :help)
                           (.preventDefault e)
                           (if (pos? (.-deltaY e))
                             (voice/dispatch-action! :help-next {})
                             (voice/dispatch-action! :help-prev {}))))
                       #js {:passive false})

    ;; Help interactivity: keyboard shortcuts (window-level)
    (.addEventListener js/window "keydown"
                       (fn [e]
                         (let [k (.-key e)]
          ;; F1 — open help with word at cursor (works in any mode)
                           (when (= k "F1")
                             (.preventDefault e)
                             (let [word-info (when @editor-view
                                               (cm/get-word-at-cursor @editor-view))
                                   query (when (and word-info (seq (:text word-info))) (:text word-info))]
                               (voice-state/enable!)
                               (if query
                                 (do
                                   (voice/dispatch-action! :mode-switch {:mode :help :rest-tokens [query]})
                  ;; Store word boundaries for autocomplete replacement
                                   (voice-state/update-help! {:replace-word {:from (:from word-info)
                                                                             :to (:to word-info)}}))
                                 (voice/dispatch-action! :mode-switch {:mode :help}))))

          ;; Help mode navigation keys
                           (when (= (voice-state/get-mode) :help)
                             (let [{:keys [highlight]} (voice-state/get-help)
                                   highlight (or highlight -1)
                                   page-count (let [{:keys [results page]} (voice-state/get-help)
                                                    start (* page 7)]
                                                (min 7 (- (count results) start)))]
                               (cond
                ;; Number keys 1-7 → select item
                                 (and (>= (.charCodeAt k 0) 49) (<= (.charCodeAt k 0) 55) (= 1 (count k)))
                                 (do (.preventDefault e)
                                     (voice/dispatch-action! :help-select {:index (dec (js/parseInt k 10))}))

                ;; Arrow up → move highlight up
                                 (= k "ArrowUp")
                                 (do (.preventDefault e)
                                     (let [new-hl (if (neg? highlight) (dec page-count) (max 0 (dec highlight)))]
                                       (voice-state/update-help! {:highlight new-hl})))

                ;; Arrow down → move highlight down
                                 (= k "ArrowDown")
                                 (do (.preventDefault e)
                                     (let [new-hl (if (neg? highlight) 0 (min (dec page-count) (inc highlight)))]
                                       (voice-state/update-help! {:highlight new-hl})))

                ;; Enter → select highlighted item
                                 (= k "Enter")
                                 (do (.preventDefault e)
                                     (when (>= highlight 0)
                                       (voice/dispatch-action! :help-select {:index highlight})))

                ;; Arrow left / PageUp → previous page
                                 (or (= k "ArrowLeft") (= k "PageUp"))
                                 (do (.preventDefault e)
                                     (voice/dispatch-action! :help-prev {}))

                ;; Arrow right / PageDown → next page
                                 (or (= k "ArrowRight") (= k "PageDown"))
                                 (do (.preventDefault e)
                                     (voice/dispatch-action! :help-next {}))

                ;; Backspace → go back to categories
                                 (= k "Backspace")
                                 (do (.preventDefault e)
                                     (voice/dispatch-action! :help-back {}))

                ;; Escape → back to categories, or exit help if already there
                                 (= k "Escape")
                                 (do (.preventDefault e)
                                     (voice/dispatch-action! :help-exit {}))))))))))

;; ============================================================
;; Picking status bar
;; ============================================================

(defn- render-source-history
  "Build innerHTML for source-history entries.
   Entries from :definitions with :line become clickable links;
   :repl entries show the command text as tooltip."
  [entries]
  (let [;; Filter out :register ops (shown in the name badge already)
        display (filterv #(not= :register (:op %)) entries)
        ;; Show most recent first (right-to-left is confusing, keep chronological)
        ;; Truncate: if >5, show first 2 + ... + last 2
        truncated (if (> (count display) 5)
                    (concat (take 2 display) [{:op :ellipsis}] (take-last 2 display))
                    display)]
    (str/join
     "<span class=\"history-arrow\"> &larr; </span>"
     (map (fn [entry]
            (if (= :ellipsis (:op entry))
              "<span class=\"history-arrow\">...</span>"
              (let [op-name (name (:op entry))
                    source (:source entry)
                    line (:line entry)]
                (cond
                   ;; Definitions with line → clickable link
                  (and (= :definitions source) line)
                  (str "<a class=\"source-link\" data-line=\"" line "\">"
                       op-name " L:" line "</a>")
                   ;; REPL → show tooltip with code
                  (= :repl source)
                  (str "<span class=\"source-repl\" title=\"REPL\">"
                       op-name "</span>")
                   ;; Unknown source
                  :else
                  (str "<span class=\"source-repl\">" op-name
                       (when line (str " L:" line))
                       "</span>")))))
          truncated))))

(defn- update-status-bar!
  "Update the picking status bar with the selected mesh/face info.
   pick-info: nil, or {:name kw :level :object/:face :face-id :face-normal
                        :face-center :tolerance-deg :tri-count}"
  [pick-info]
  (when-let [bar (.getElementById js/document "picking-status-bar")]
    (if pick-info
      (let [{:keys [name level face-id face-normal tolerance-deg tri-count]} pick-info
            mesh (registry/get-mesh name)
            history (when mesh (:source-history mesh))
            ;; Face info span (only at face drill-down level)
            face-html
            (when (= :face level)
              (let [face-label (cond
                                 (keyword? face-id) (str ":" (clojure.core/name face-id))
                                 (vector? face-id) (str "face (" (count face-id) " tris)")
                                 :else (str "face (" tri-count " tris)"))
                    normal-str (when face-normal
                                 (let [[nx ny nz] face-normal]
                                   (str "[" (.toFixed nx 2) " "
                                        (.toFixed ny 2) " "
                                        (.toFixed nz 2) "]")))
                    tol-str (when (and tolerance-deg (not= tolerance-deg 2.5))
                              (str " &plusmn;" (.toFixed tolerance-deg 1) "&deg;"))]
                (str "<span class=\"picking-face\">"
                     face-label
                     (when normal-str (str " &mdash; normal " normal-str))
                     tol-str
                     "</span>")))]
        (set! (.-innerHTML bar)
              (str "<span class=\"picking-name\">" (clojure.core/name name) "</span>"
                   (when face-html
                     (str "<span class=\"picking-separator\">&mdash;</span>" face-html))
                   (when (seq history)
                     (str "<span class=\"picking-separator\">&mdash;</span>"
                          (render-source-history history)))))
        (.add (.-classList bar) "visible"))
      (do
        (.remove (.-classList bar) "visible")
        (set! (.-innerHTML bar) "")))))

(defn- setup-picking-status-bar
  "Wire viewport picking callback and status bar click handler."
  []
  ;; Selection callback: viewport -> status bar
  (viewport/set-on-pick-callback! update-status-bar!)
  ;; Click delegation: source-link clicks -> scroll to line
  (when-let [bar (.getElementById js/document "picking-status-bar")]
    (.addEventListener bar "click"
                       (fn [e]
                         (when-let [^js link (.closest (.-target e) ".source-link")]
                           (let [line (js/parseInt (.getAttribute link "data-line"))]
                             (when-not (js/isNaN line)
                               (cm/scroll-to-line! line)
                               (cm/flash-line! line 1500))))))))

;; ============================================================
;; Initialization
;; ============================================================

(def ^:private default-code "; Run with Cmd+Enter, then use the REPL below.
; Shapes appear in the viewport as you register them.
(register tube
  (extrude (circle 10) (f 30) (th 45) (f 20)))")

;; Table, not a blind "edit-" prefix: the head-rename grammar isn't uniform
;; (a future bezier-to → edit-bezier candidate already breaks the pattern), so
;; the table is the extension point — a new editor enters with one row.
(def ^:private edit-menu-table
  {"path"        "edit-path"
   "path-2d"     "edit-path-2d"
   "image-board" "edit-image-board"
   "attach"      "edit-attach"
   "mesh-split"  "edit-mesh-split"})

(defn- edit-menu-candidate
  "At the cursor, the {:from :head :new-head} needed to rewrite the
   containing form's head symbol into its edit-* counterpart, or nil if the
   cursor isn't inside a form whose head is a table key (an already-edit-*
   form isn't a key, so it's correctly excluded)."
  []
  (when-let [{:keys [from text]} (cm/get-form-at-cursor)]
    (when-let [head (first (cm/parse-form-elements text))]
      (when-let [new-head (get edit-menu-table head)]
        {:from (inc from) :head head :new-head new-head}))))

(defn- edit-selection!
  "Rewrite the form at the cursor's head symbol to its edit-* counterpart
   (only the head characters, not the whole form) and re-run definitions —
   the existing two-phase modal flow opens the session on that run, same as
   if the user had typed the edit- prefix by hand."
  []
  (when-let [{:keys [from head new-head]} (edit-menu-candidate)]
    (cm/replace-range from (+ from (count head)) new-head)
    (evaluate-definitions-user!)))

(defn- editor-context-menu-items
  "The right-click menu model, recomputed each time the menu opens so that
   selection-dependent items reflect the current state. Each item is either
   {:separator true} or {:label … :run fn :enabled? bool}."
  []
  (let [sel? (cm/has-selection?)]
    [{:label "Cut"        :enabled? sel? :run #(cm/cut-selection!)}
     {:label "Copy"       :enabled? sel? :run #(cm/copy-selection!)}
     {:label "Paste"      :enabled? true :run #(cm/paste-clipboard!
                                                (cm/get-editor)
                                                (fn [] (js/console.warn "Paste: clipboard unavailable")))}
     {:label "Select All" :enabled? true :run #(cm/select-all!)}
     {:separator true}
     {:label "Search…"    :enabled? true :run #(cm/open-search!)}
     {:separator true}
     {:label "Slurp →"            :enabled? true :run #(cm/slurp-form)}
     {:label "← Barf"             :enabled? true :run #(cm/barf-form)}
     {:label "Go to matching )"   :enabled? true :run #(cm/goto-matching-bracket!)}
     {:separator true}
     {:label "Tweak"      :enabled? sel? :run #(tweak-mode/tweak-selection!)}
     {:label "Edit"       :enabled? (and (some? (edit-menu-candidate)) (not (modal/active?)))
      :run #(edit-selection!)}]))

(defn- install-editor-context-menu!
  "Attach a right-click menu to the editor with standard editing commands
   (cut/copy/paste/select-all), search, a few paredit operations, and the
   Tweak command. Replaces the browser's native menu (which preventDefault
   suppresses) because the native menu is absent/incomplete under WKWebView."
  [container]
  (when container
    (.addEventListener
     container "contextmenu"
     (fn [e]
       (.preventDefault e)
       ;; Remove any stale menu
       (when-let [old (.getElementById js/document "tweak-context-menu")]
         (.remove old))
       (let [menu (.createElement js/document "div")]
         (set! (.-id menu) "tweak-context-menu")
         (set! (.-className menu) "tweak-context-menu")
         (set! (.. menu -style -left) (str (.-clientX e) "px"))
         (set! (.. menu -style -top) (str (.-clientY e) "px"))
         (doseq [item (editor-context-menu-items)]
           (if (:separator item)
             (let [sep (.createElement js/document "div")]
               (set! (.-className sep) "tcm-sep")
               (.appendChild menu sep))
             (let [{:keys [label run enabled?]} item
                   el (.createElement js/document "div")]
               (set! (.-className el) (if enabled? "tcm-item" "tcm-item tcm-disabled"))
               (set! (.-textContent el) label)
               (when enabled?
                 (.addEventListener el "click"
                                    (fn [_] (.remove menu) (run))))
               (.appendChild menu el))))
         (.appendChild (.-body js/document) menu)
         ;; Keep the menu on-screen if it would overflow the viewport bottom/right.
         (let [r (.getBoundingClientRect menu)
               vw (.-innerWidth js/window)
               vh (.-innerHeight js/window)]
           (when (> (.-right r) vw)
             (set! (.. menu -style -left) (str (max 0 (- (.-clientX e) (.-width r))) "px")))
           (when (> (.-bottom r) vh)
             (set! (.. menu -style -top) (str (max 0 (- (.-clientY e) (.-height r))) "px"))))
         ;; Dismiss on the next outside click
         (let [dismiss (fn dismiss [ev]
                         (when-not (.contains menu (.-target ev))
                           (.remove menu)
                           (.removeEventListener js/document "mousedown" dismiss true)))]
           (js/setTimeout #(.addEventListener js/document "mousedown" dismiss true) 0)))))))

(defn init []
  (let [canvas (.getElementById js/document "viewport")
        editor-container (.getElementById js/document "editor-explicit")
        repl-input (.getElementById js/document "repl-input")
        repl-history (.getElementById js/document "repl-history")
        error-panel (.getElementById js/document "error-panel")
        ;; Seed/migrate the workspace session store, then open the current doc.
        initial-content (or (:content (workspace/ensure-initialized! default-code))
                            default-code)]
    ;; Create CodeMirror editor
    (reset! editor-view
            (cm/create-editor
             {:parent editor-container
              :initial-value initial-content
              :on-change (fn []
                           (save-to-storage)
                           (send-script-debounced)
                           (sync-voice-state))
              :on-run evaluate-definitions-user!
              :on-tweak (fn [] (tweak-mode/tweak-selection!))
              :on-selection-change (fn []
                                     (maybe-update-ai-focus!)
                                     (sync-voice-state))}))
    ;; Right-click in the editor → editing/search/paredit/tweak context menu
    (install-editor-context-menu! editor-container)
    ;; Wire editor content getter for tweak_mode
    (reset! editor-state/get-editor-content
            (fn [] (when @editor-view (cm/get-value @editor-view))))
    (reset! repl-input-el repl-input)
    (reset! repl-history-el repl-history)
    (reset! error-el error-panel)
    (viewport/init canvas)
    ;; Wire animation callbacks (registry <-> playback, avoids circular dep)
    (anim-playback/set-mesh-callbacks! registry/get-mesh registry/register-mesh!)
    (anim-playback/set-update-geometry-callback! viewport/update-mesh-geometry!)
    (anim-playback/set-rigid-transform-callbacks! viewport/set-mesh-rigid-transform! viewport/reset-mesh-rigid-transform!)
    (anim-playback/set-refresh-callback! #(registry/refresh-viewport! false))
    (anim-playback/set-async-output-callback! add-script-output)
    ;; Register XR panel callbacks (avoid circular dependency)
    (xr/register-action-callback! :toggle-all-obj
                                  (fn [show-all?]
                                    (if show-all?
                                      (do (registry/show-all!)
                                          (registry/refresh-viewport! false))
                                      (do (registry/show-only-registered!)
                                          (registry/refresh-viewport! false)))))
    ;; Expose run-definitions to SCI (breaks circular dep via atom)
    (reset! editor-state/run-definitions-fn evaluate-definitions)
    (setup-keybindings)
    (setup-save-load)
    (setup-transport)
    (setup-vertical-resizer)
    (setup-horizontal-resizer)
    ;; Setup VR and AR buttons in toolbar
    (let [toolbar (.getElementById js/document "viewport-toolbar")
          renderer (viewport/get-renderer)
          vr-btn (xr/create-vr-button renderer #(xr/toggle-vr renderer))
          ar-btn (xr/create-ar-button renderer #(xr/toggle-ar renderer))]
      (when toolbar
        (when ar-btn
          (.insertBefore toolbar ar-btn (.-firstChild toolbar)))
        (when vr-btn
          (.insertBefore toolbar vr-btn (.-firstChild toolbar)))))
    ;; Initialize Manifold WASM (async)
    (-> (manifold/init!)
        (.then #(js/console.log "Manifold WASM initialized"))
        (.catch #(js/console.warn "Manifold WASM failed to initialize:" %)))
    ;; Initialize font registry: built-ins + any persisted custom fonts (async)
    (-> (fonts/init!)
        (.then #(js/console.log "Fonts initialized"))
        (.catch #(js/console.warn "Font init failed:" %)))
    ;; Load LLM settings and setup button
    (settings/load-settings!)
    ;; Restore the user's preferred reset/framing view angle (if any)
    (when-let [d (settings/get-reset-view-dir)]
      (viewport/set-reset-view-dir! d))
    ;; Restore the user's ring-light toggles (if any; nil keeps the default)
    (when-let [v (settings/get-viewport-lights)]
      (viewport/apply-light-config! v))
    (setup-settings)
    ;; Setup sync (desktop <-> headset)
    (setup-sync)
    ;; Setup manual panel
    (setup-manual)
    ;; Install bundled builtin libraries (overwrites by name)
    (lib-builtin/install-builtins!)
    ;; Setup library panel
    (setup-library-panel)
    ;; Setup workspaces panel (multi-document editor)
    (setup-workspace-panel)
    ;; Project the current workspace's library set onto the global active list
    ;; (adopts the existing global list for legacy workspaces).
    (apply-workspace-libraries!)
    ;; Setup picking status bar (Alt+Click mesh selection)
    (setup-picking-status-bar)
    ;; Initialize voice input system
    (voice/init! {:insert ai-insert-code
                  :edit ai-edit-code
                  :navigate ai-navigate
                  :execute (fn [{:keys [target]}]
                             (case target
                               :script (evaluate-definitions)
                               :repl (evaluate-repl-input)
                               (js/console.warn "Voice execute: unknown target" target)))
                  :undo (fn [{:keys [operation count]}]
                          (when-let [view @editor-view]
                            (dotimes [_ (or count 1)]
                              (case operation
                                :undo (cm/editor-undo! view)
                                :redo (cm/editor-redo! view)
                                nil))
                            (maybe-update-ai-focus! view)
                            (save-to-storage)
                            (send-script-debounced)))
                  :get-script (fn [] (when @editor-view (cm/get-value @editor-view)))})
    (setup-voice-ui)
    ;; Initialize auto-session callbacks (avoids circular dep)
    (auto-session/init! {:editor-view-atom editor-view
                         :save-fn save-to-storage})
    ;; Display version in toolbar
    (when-let [vtag (.getElementById js/document "version-tag")]
      (set! (.-textContent vtag) version/VERSION))
    ;; Focus REPL input
    (when repl-input
      (.focus repl-input))
    (js/console.log "Ridley initialized. Cmd+Enter for definitions, Enter in REPL.")))

(defn reload []
  ;; Re-wire animation callbacks (defonce atoms persist, but new ones start nil)
  (anim-playback/set-mesh-callbacks! registry/get-mesh registry/register-mesh!)
  (anim-playback/set-update-geometry-callback! viewport/update-mesh-geometry!)
  (anim-playback/set-rigid-transform-callbacks! viewport/set-mesh-rigid-transform! viewport/reset-mesh-rigid-transform!)
  (anim-playback/set-refresh-callback! #(registry/refresh-viewport! false))
  (anim-playback/set-async-output-callback! add-script-output)
  ;; Hot reload callback - re-evaluate definitions
  (evaluate-definitions))
