(ns ridley.ai.auto-session
  "Iterative AI code generation with visual feedback.
   /ai-auto generates code, executes it, captures viewport renders,
   sends them back to the LLM for self-correction, and iterates."
  (:require [clojure.string :as str]
            [ridley.ai.core :as ai]
            [ridley.ai.describe :as vision]
            [ridley.ai.prompts :as prompts]
            [ridley.ai.capture-directives :as directives]
            [ridley.viewport.capture :as capture]
            [ridley.editor.codemirror :as cm]
            [ridley.editor.repl :as repl]
            [ridley.scene.registry :as registry]
            [ridley.manifold.core :as manifold]
            [ridley.measure.core :as measure]
            [ridley.anim.playback :as playback]
            [ridley.settings :as settings]))

;; =============================================================================
;; Session state
;; =============================================================================

(defonce session
  (atom {:active? false
         :prompt nil
         :round 0
         :max-rounds 5
         :abort-controller nil
         :history []
         :user-captures nil}))

;; Callbacks injected from core.cljs to avoid circular deps
(defonce ^:private callbacks (atom nil))

(defn init!
  "Initialize with callbacks from core.cljs. Call once during app setup."
  [cbs]
  (reset! callbacks cbs))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- emit! [& parts]
  (playback/emit-async-output! (apply str parts)))

(defn active? [] (:active? @session))

(defn- aborted? []
  (when-let [ac (:abort-controller @session)]
    (.-aborted (.-signal ac))))

(defn- wait-frames
  "Wait n animation frames. Returns a Promise."
  [n]
  (js/Promise.
    (fn [resolve]
      (loop [i n]
        (if (<= i 0)
          (resolve nil)
          (js/requestAnimationFrame
            (fn [] (if (<= i 1)
                     (resolve nil)
                     (js/requestAnimationFrame
                       (fn [] (if (<= i 2)
                                (resolve nil)
                                (js/requestAnimationFrame resolve))))))))))))

(defn- execute-and-render!
  "Execute editor content and refresh viewport. Returns a Promise that
   resolves after the scene has rendered (a few frames later)."
  []
  (js/Promise.
    (fn [resolve reject]
      (js/requestAnimationFrame
        (fn []
          (js/setTimeout
            (fn []
              (try
                (let [view (when-let [a (:editor-view-atom @callbacks)] @a)
                      code (when view (cm/get-value view))
                      result (repl/evaluate-definitions code)]
                  (if-let [error (:error result)]
                    (do (emit! "Error: " error)
                        (reject (js/Error. error)))
                    (do
                      (let [render-data (repl/extract-render-data result)]
                        (when render-data
                          (registry/set-lines! (:lines render-data))
                          (registry/set-stamps! (or (:stamps render-data) []))
                          (registry/set-definition-meshes! (:meshes render-data)))
                        (registry/refresh-viewport! false))
                      ;; Wait a few frames for the render to settle
                      (.then (wait-frames 3) resolve))))
                (catch :default e
                  (emit! "Execution error: " (.-message e))
                  (reject e))))
            0))))))

(defn- capture-default-views
  "Capture perspective + front + top views, plus a Z-slice at mid-height.
   Returns seq of [label data-url] pairs."
  []
  (try
    (let [views [[:perspective (capture/render-view :perspective :width 256 :height 256)]
                 [:front (capture/render-view :front :width 256 :height 256)]
                 [:top (capture/render-view :top :width 256 :height 256)]]
          ;; Try to add a Z-slice at mid-height of the first visible mesh
          first-name (first (registry/visible-names))
          slice (when first-name
                  (try
                    (let [mesh (registry/get-mesh first-name)
                          b (measure/bounds mesh)
                          mid-z (* 0.5 (+ (nth (:min b) 2) (nth (:max b) 2)))]
                      [:slice-z (capture/render-slice first-name :z mid-z
                                                      :width 256 :height 256)])
                    (catch :default _ nil)))]
      (cond-> views
        slice (conj slice)))
    (catch :default e
      (js/console.warn "Capture failed:" (.-message e))
      nil)))

(defn- capture-views
  "Capture views for the refinement loop.
   If user specified capture directives, execute those (+ defaults unless suppressed).
   Otherwise fall back to the standard 3+1 views."
  []
  (if-let [{:keys [parsed suppress-defaults?]} (:user-captures @session)]
    (let [user-images (directives/execute parsed {:width 256 :height 256})
          defaults (when-not suppress-defaults? (capture-default-views))]
      (or (seq (concat user-images defaults))
          (capture-default-views)))
    (capture-default-views)))

(defn- check-mesh-status
  "Check manifold status of visible meshes. Returns a status string for the prompt."
  []
  (try
    (let [names (registry/visible-names)]
      (when (seq names)
        (let [statuses (for [n names]
                         (let [mesh (registry/get-mesh n)
                               status (manifold/get-mesh-status mesh)]
                           (str (name n) ": "
                                (if (:manifold? status)
                                  (str "manifold, volume=" (.toFixed (:volume status) 1))
                                  (str "NOT manifold (" (name (:status status)) ")")))))]
          (str/join "; " statuses))))
    (catch :default e
      (js/console.warn "Mesh status check failed:" (.-message e))
      nil)))

(defn- insert-code!
  "Insert or replace AI block in the editor.
   When promote? is true, the existing AI block is first archived as a history step."
  ([code prompt] (insert-code! code prompt false))
  ([code prompt promote?]
   (when-let [view (when-let [a (:editor-view-atom @callbacks)] @a)]
     (when promote?
       (cm/promote-ai-block-to-step! view))
     (if (cm/find-ai-block view)
       (cm/replace-ai-block view code prompt)
       (cm/insert-ai-block view code prompt))
     (when-let [save-fn (:save-fn @callbacks)]
       (save-fn)))))

(defn- parse-refinement-response
  "Parse the vision LLM response. Returns {:type :done/:code :reason str :code str}."
  [raw-text]
  (let [trimmed (str/trim raw-text)
        cleaned (if-let [[_ inner] (re-find #"(?s)```(?:json)?\n?(.*?)```" trimmed)]
                  (str/trim inner)
                  trimmed)]
    (try
      (let [^js parsed (js/JSON.parse cleaned)
            reason (.-reason parsed)]
        (case (.-type parsed)
          "done" {:type :done :reason reason}
          "code" {:type :code :code (.-code parsed) :reason reason}
          {:type :done :reason reason}))
      (catch :default _
        ;; Try to extract code from truncated JSON
        (let [reason (second (re-find #"\"reason\"\s*:\s*\"((?:[^\"\\]|\\.)*)\"" cleaned))]
          (if-let [[_ raw-code] (re-find #"\"code\"\s*:\s*\"((?:[^\"\\]|\\.)*)\"?" cleaned)]
            {:type :code
             :reason reason
             :code (-> raw-code
                       (str/replace "\\n" "\n")
                       (str/replace "\\t" "\t")
                       (str/replace "\\\"" "\"")
                       (str/replace "\\\\" "\\"))}
            {:type :done :reason reason}))))))

;; =============================================================================
;; Refinement loop
;; =============================================================================

(defn- build-refinement-text
  "Build the text prompt for the vision refinement call.
   The system prompt already contains the DSL reference + refinement instructions."
  [original-prompt current-code mesh-status user-feedback history]
  (cond-> (str "## User's Request\n" original-prompt
               "\n\n## Current Code\n```clojure\n" current-code "\n```")
    mesh-status (str "\n\n## Mesh Validation\n" mesh-status)
    (seq history) (str "\n\n## Previous Iterations\n"
                       (str/join "\n" (map-indexed
                                        (fn [i {:keys [code reason]}]
                                          (str "Round " (inc i) ": " reason
                                               "\n```clojure\n" code "\n```"))
                                        history)))
    user-feedback (str "\n\n## User Feedback\n" user-feedback)))

(defn- call-refinement-api
  "Call the vision LLM with refinement prompt as system prompt.
   Uses JSON mode for Google. Returns Promise<string>."
  [provider-name api-key model messages signal]
  (let [sys-prompt prompts/auto-refinement-prompt
        fetch-opts (fn [headers body]
                     (clj->js (cond-> {:method "POST"
                                       :headers headers
                                       :body (js/JSON.stringify (clj->js body))}
                                signal (assoc :signal signal))))
        handle (fn [^js resp provider]
                 (if (.-ok resp)
                   (.json resp)
                   (-> (.text resp)
                       (.then (fn [body]
                                (throw (js/Error. (str provider " API error: "
                                                       (subs body 0 (min 200 (count body)))))))))))]
    (case provider-name
      "anthropic"
      (-> (js/fetch "https://api.anthropic.com/v1/messages"
                    (fetch-opts {"Content-Type" "application/json"
                                 "x-api-key" api-key
                                 "anthropic-version" "2023-06-01"
                                 "anthropic-dangerous-direct-browser-access" "true"}
                                {:model model :max_tokens 8192
                                 :system sys-prompt :messages messages}))
          (.then #(handle % "Anthropic"))
          (.then (fn [^js data] (.-text (aget (.-content data) 0)))))

      ("openai" "groq")
      (let [url (if (= provider-name "openai")
                  "https://api.openai.com/v1/chat/completions"
                  "https://api.groq.com/openai/v1/chat/completions")]
        (-> (js/fetch url
                      (fetch-opts (cond-> {"Content-Type" "application/json"}
                                    api-key (assoc "Authorization" (str "Bearer " api-key)))
                                  {:model model :max_tokens 8192
                                   :messages (into [{:role "system" :content sys-prompt}] messages)}))
            (.then #(handle % "OpenAI"))
            (.then (fn [^js data] (.. data -choices (at 0) -message -content)))))

      "google"
      (let [url (str "https://generativelanguage.googleapis.com/v1beta/models/"
                     model ":generateContent?key=" api-key)
            contents (mapv (fn [msg]
                             (let [role (if (= (:role msg) "assistant") "model" (:role msg))]
                               (if (:parts msg)
                                 (assoc msg :role role)
                                 {:role role :parts [{:text (str (:content msg))}]})))
                           messages)]
        (-> (js/fetch url
                      (fetch-opts {"Content-Type" "application/json"}
                                  {:system_instruction {:parts [{:text sys-prompt}]}
                                   :contents contents
                                   :generationConfig {:maxOutputTokens 8192
                                                      :responseMimeType "application/json"
                                                      :responseSchema
                                                      {:type "OBJECT"
                                                       :properties
                                                       {:type {:type "STRING" :enum ["code" "done"]}
                                                        :code {:type "STRING"}
                                                        :reason {:type "STRING"}}
                                                       :required ["type" "reason"]}}}))
            (.then #(handle % "Google"))
            (.then (fn [^js data]
                     (let [^js candidate (aget (.-candidates data) 0)]
                       (.-text (aget (.. candidate -content -parts) 0)))))))

      "ollama"
      (let [url (str (settings/get-ai-setting :ollama-url) "/api/chat")]
        (-> (js/fetch url
                      (fetch-opts {"Content-Type" "application/json"}
                                  {:model model :stream false
                                   :messages (into [{:role "system" :content sys-prompt}] messages)}))
            (.then #(handle % "Ollama"))
            (.then (fn [^js data] (.. data -message -content))))))))

(defn- do-refinement!
  "Run one vision-based refinement round.
   Returns Promise<string> (raw LLM text)."
  [original-prompt current-code images mesh-status user-feedback history]
  (let [{:keys [provider-name api-key model]} (vision/resolve-provider)
        text (build-refinement-text original-prompt current-code mesh-status user-feedback history)
        user-msg (vision/build-user-message provider-name text images)
        ac (:abort-controller @session)]
    (call-refinement-api provider-name api-key model [user-msg]
                         (when ac (.-signal ac)))))

(defn- refinement-loop!
  "Iterate: capture views → ask LLM → apply corrections.
   user-feedback is optional text from the user (only on first round of continue!).
   Returns Promise that resolves when done or max rounds reached."
  [original-prompt round max-rounds user-feedback]
  (if (or (>= round max-rounds) (aborted?))
    (do (when (>= round max-rounds)
          (emit! "Max iterations reached (" max-rounds ")"))
        (js/Promise.resolve nil))
    ;; Capture current state
    (let [images (capture-views)
          mesh-status (check-mesh-status)
          view (when-let [a (:editor-view-atom @callbacks)] @a)
          current-code (when view
                         (when-let [{:keys [from to]} (cm/find-ai-block view)]
                           (let [full (cm/get-value view)]
                             ;; Extract just the code between markers
                             (subs full from to))))]
      (if (or (nil? images) (nil? current-code))
        (do (emit! "Could not capture scene for review")
            (js/Promise.resolve nil))
        (do
          (when mesh-status (emit! "Mesh: " mesh-status))
          (emit! "[" (inc round) "/" max-rounds "] Reviewing result...")
          (let [history (:history @session)]
            (-> (do-refinement! original-prompt current-code images mesh-status user-feedback history)
                (.then (fn [raw-text]
                         (let [response (parse-refinement-response raw-text)]
                           (when (:reason response)
                             (emit! "AI: " (:reason response)))
                           (case (:type response)
                             :done
                             (do (emit! "Auto-generation complete (" (inc round) " iteration"
                                        (when (> (inc round) 1) "s") ")")
                                 (js/Promise.resolve nil))

                             :code
                             (do (emit! "[" (inc round) "/" max-rounds "] Refining code...")
                                 (swap! session (fn [s]
                                                  (-> s
                                                      (update :round inc)
                                                      (update :history conj
                                                              {:code current-code
                                                               :reason (or (:reason response) "no reason given")}))))
                                 (insert-code! (:code response) original-prompt)
                                 (-> (execute-and-render!)
                                     ;; user-feedback only applies to first round
                                     (.then #(refinement-loop! original-prompt (inc round) max-rounds nil)))))))))
              (.catch (fn [err]
                        (if (aborted?)
                          (emit! "Auto-generation cancelled.")
                          (emit! "Refinement error: " (.-message err)))
                        (js/Promise.resolve nil)))))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start!
  "Start an /ai-auto session. Generates code, executes, and iterates with visual feedback.
   prompt — the user's natural language request
   script-content — current editor content for context
   max-rounds — maximum refinement iterations (default 5)"
  ([prompt script-content] (start! prompt script-content 5))
  ([prompt script-content max-rounds]
  (when (:active? @session)
    (emit! "Auto session already running. Use Escape to cancel.")
    (throw (js/Error. "Auto session already active")))

  (let [ac (js/AbortController.)
        ;; Parse capture directives from the prompt
        {:keys [clean-text directives suppress-defaults? has-directives?]}
        (directives/parse prompt)
        ;; Check if vision is available for refinement
        has-vision? (try (vision/resolve-provider) true
                         (catch :default _ false))]

    (reset! session {:active? true
                     :prompt clean-text
                     :round 0
                     :max-rounds max-rounds
                     :abort-controller ac
                     :history []
                     :user-captures (when has-directives?
                                      {:parsed directives
                                       :suppress-defaults? suppress-defaults?})})

    (when has-directives?
      (emit! "Capture directives: " (count directives) " custom view(s)"))
    (emit! "Generating [auto]...")

    ;; Round 1: generate with tier-3
    (-> (ai/generate clean-text {:script-content script-content
                                 :tier-override :tier-3})
        (.then (fn [{:keys [type code question]}]
                 (when (aborted?) (throw (js/Error. "Cancelled")))
                 (case type
                   :clarification
                   (do (emit! "\uD83E\uDD16 " question)
                       (reset! session {:active? false})
                       nil)

                   :code
                   (do (insert-code! code prompt true) ;; promote existing block
                       (ai/add-entry! prompt code)
                       (emit! "[1/" max-rounds "] Code generated, rendering...")
                       (-> (execute-and-render!)
                           (.then (fn []
                                    (if has-vision?
                                      (refinement-loop! prompt 1 max-rounds nil)
                                      (do (emit! "No vision model available — skipping refinement")
                                          nil)))))))))
        (.catch (fn [err]
                  (when-not (aborted?)
                    (emit! "Error: " (.-message err)))))
        (.finally (fn []
                    (swap! session assoc :active? false)))))))

(defn cancel!
  "Cancel the current auto session."
  []
  (when-let [ac (:abort-controller @session)]
    (.abort ac))
  (swap! session assoc :active? false)
  (emit! "Auto-generation cancelled."))

(defn continue!
  "Continue refining the current AI block with more visual iterations.
   Uses the prompt from the last /ai-auto session.
   max-rounds — number of additional iterations (default 3)
   feedback — optional user feedback text describing what's wrong"
  ([] (continue! 3 nil))
  ([max-rounds] (continue! max-rounds nil))
  ([max-rounds feedback]
   (when (:active? @session)
     (throw (js/Error. "Auto session already active")))
   (let [view (when-let [a (:editor-view-atom @callbacks)] @a)
         has-block? (when view (cm/find-ai-block view))
         original-prompt (or (:prompt @session) "improve this geometry")]
     (when-not has-block?
       (throw (js/Error. "No AI block to continue refining")))
     (let [ac (js/AbortController.)
           has-vision? (try (vision/resolve-provider) true
                            (catch :default _ false))]
       (when-not has-vision?
         (throw (js/Error. "Continue requires a vision-capable model")))
       (swap! session merge {:active? true
                            :prompt original-prompt
                            :round 0
                            :max-rounds max-rounds
                            :abort-controller ac})
       (emit! (str "Continuing [auto, " max-rounds " more rounds]..."
                   (when feedback (str " Feedback: " feedback))))
       (-> (execute-and-render!)
           (.then #(refinement-loop! original-prompt 0 max-rounds feedback))
           (.catch (fn [err]
                     (when-not (aborted?)
                       (emit! "Error: " (.-message err)))))
           (.finally (fn []
                       (swap! session assoc :active? false))))))))
