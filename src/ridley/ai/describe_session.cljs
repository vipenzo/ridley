(ns ridley.ai.describe-session
  "Describe session orchestration — (describe), (ai-ask), (end-describe), (cancel-ai).
   Ties together view rendering, metadata collection, and multimodal LLM calls
   into a screen-reader-friendly interactive session."
  (:require [clojure.string :as str]
            [ridley.ai.describe :as ai]
            [ridley.ai.capture-directives :as directives]
            [ridley.viewport.capture :as capture]
            [ridley.anim.playback :as playback]
            [ridley.scene.registry :as registry]))

;; =============================================================================
;; Session State
;; =============================================================================

(defonce session
  (atom {:active? false
         :target nil               ; keyword or nil (all visible)
         :messages []              ; conversation history [{:role :content}]
         :abort-controller nil     ; js/AbortController for current API call
         :round 0}))              ; current refinement round

;; =============================================================================
;; Output helpers
;; =============================================================================

(defn- emit!
  "Push a message to the REPL UI asynchronously."
  [& parts]
  (playback/emit-async-output! (apply str parts)))

(defn- elapsed-str [start-ms]
  (str (js/Math.round (/ (- (js/Date.now) start-ms) 1000)) "s"))

(defn- start-timer!
  "Start a periodic timer that emits elapsed updates. Returns a cancel fn."
  [start-ms]
  (let [id (js/setInterval
             (fn [] (emit! "Still waiting... (" (elapsed-str start-ms) " elapsed)"))
             10000)]
    (fn [] (js/clearInterval id))))

;; =============================================================================
;; Rendering helpers
;; =============================================================================

(defn- render-standard-views
  "Render the 7 standard views. Returns seq of [label data-url] pairs."
  [target]
  (map (fn [[k v]] [k v])
       (capture/render-all-views :target target)))

(defn- render-extra-request
  "Render a single AI-requested view or slice. Returns [label data-url] or nil."
  [{:keys [type axis position from label]}]
  (try
    (case type
      "slice"
      (let [axis-kw (keyword axis)
            target (:target @session)]
        [(keyword (str "slice-" axis "-" position))
         (capture/render-slice target axis-kw position)])

      "view"
      (let [dir-vec (vec from)
            lbl (or label (str "custom-" (str/join "-" (map int from))))]
        [(keyword lbl)
         (capture/render-view dir-vec)])

      nil)
    (catch :default e
      (js/console.warn "Failed to render extra request:" e)
      nil)))

(defn- render-extra-requests
  "Render all AI-requested views/slices. Returns seq of [label data-url] pairs."
  [requests]
  (keep render-extra-request requests))

;; =============================================================================
;; Session history
;; =============================================================================

(defn- store-exchange!
  "Append user and assistant messages to session history."
  [user-text assistant-text]
  (swap! session update :messages
    (fn [msgs]
      (-> msgs
          (conj {:role "user" :content user-text})
          (conj {:role "assistant" :content assistant-text})))))

;; =============================================================================
;; Refinement loop — iterative promise chain (max 2 extra rounds)
;; =============================================================================

(defn- do-refinement-round
  "Execute one refinement round: render requested views, send to AI.
   Returns Promise<{:text string :type keyword :requests ...}>."
  [requests start-ms]
  (let [ac (js/AbortController.)]
    (swap! session assoc :abort-controller ac
                         :round (inc (:round @session)))
    (emit! "AI requested " (count requests) " additional view(s)... (generating)")
    (let [extra-images (render-extra-requests requests)]
      (emit! "Sending additional views... (" (elapsed-str start-ms) " elapsed)")
      (-> (ai/call-vision-with-history
            (:messages @session)
            (ai/follow-up-prompt requests)
            extra-images
            {:signal (.-signal ac)})
          (.then (fn [raw]
                   (store-exchange! (ai/additional-images-prompt) raw)
                   (ai/parse-response raw)))))))

(defn- refinement-chain
  "Handle up to max-rounds of refinement. Returns Promise<string>."
  [parsed start-ms max-rounds round]
  (if (and (= (:type parsed) :need-more) (< round max-rounds))
    (-> (do-refinement-round (:requests parsed) start-ms)
        (.then (fn [next-parsed]
                 (refinement-chain next-parsed start-ms max-rounds (inc round)))))
    (js/Promise.resolve (or (:text parsed) ""))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn ^:export describe
  "Start a describe session. Renders views, collects metadata, calls the AI.
   target — keyword name of a registered object, or nil for all visible.
   Runs asynchronously; output is emitted to the REPL via emit!.
   Returns nil (use (cancel-ai) to abort, (end-describe) to close)."
  [target]
  ;; Close any existing session
  (when (:active? @session)
    (emit! "Closing previous describe session.")
    (when-let [ac (:abort-controller @session)]
      (.abort ac)))

  ;; Validate scene
  (when (and target (nil? (registry/get-mesh target)))
    (throw (js/Error. (str "Object " (name target) " not found. Check (visible-names)."))))
  (when (and (nil? target) (empty? (registry/visible-names)))
    (throw (js/Error. "No geometry to describe. Create some shapes first.")))

  ;; Initialize session
  (let [ac (js/AbortController.)
        start-ms (js/Date.now)]
    (reset! session {:active? true
                     :target target
                     :messages []
                     :abort-controller ac
                     :round 0})

    (emit! "Analyzing geometry... (generating views)")

    ;; Render views (synchronous)
    (let [images (try
                   (render-standard-views target)
                   (catch :default e
                     (swap! session assoc :active? false)
                     (throw (js/Error. (str "Error rendering views: " (.-message e))))))
          cancel-timer (start-timer! start-ms)
          source (ai/get-source-code)
          metadata (ai/collect-metadata target)
          prompt-text (ai/initial-prompt source metadata)]

      (emit! "Sending to AI... (" (count images) " images + source code)")

      ;; Fire-and-forget: output goes through emit!, return nil to REPL
      (-> (ai/call-vision prompt-text images {:signal (.-signal ac)})
          (.then (fn [raw]
                   (store-exchange! prompt-text raw)
                   (let [parsed (ai/parse-response raw)]
                     (refinement-chain parsed start-ms 2 0))))
          (.then (fn [description]
                   (cancel-timer)
                   (emit! "\n=== Description"
                          (when target (str " of :" (name target)))
                          " ===\n"
                          description
                          "\n===\n"
                          "Type /ai-ask your question for follow-up, /ai-end to close.")))
          (.catch (fn [err]
                    (cancel-timer)
                    (let [msg (.-message err)]
                      (if (or (= "AbortError" (.-name err))
                              (str/includes? (str msg) "abort"))
                        (emit! "Describe cancelled.")
                        (do
                          (emit! "Error: " msg)
                          (swap! session assoc :active? false)))))))
      nil)))

(defn ^:export ai-ask
  "Ask a follow-up question in the active describe session.
   Runs asynchronously; answer is emitted to the REPL via emit!.
   Returns nil (use (cancel-ai) to abort)."
  [question]
  (when-not (:active? @session)
    (throw (js/Error. "No active describe session. Call (describe) first.")))
  (when-not (and (string? question) (seq (str/trim question)))
    (throw (js/Error. "Please provide a question string.")))

  (let [ac (js/AbortController.)
        start-ms (js/Date.now)
        cancel-timer (start-timer! start-ms)
        ;; Parse capture directives from the question
        {:keys [clean-text images has-directives?]}
        (directives/process question {:target (:target @session)})
        user-images (when has-directives? (seq images))
        prompt-text (str clean-text
                        "\n\nIf you need additional views or cross-sections to answer "
                        "accurately, respond with a JSON request in a fenced code block:\n"
                        "```json\n"
                        "{\"need_more\": true, \"requests\": [\n"
                        "  {\"type\": \"slice\", \"axis\": \"z\", \"position\": 25.0},\n"
                        "  {\"type\": \"view\", \"from\": [1, 1, 0.5], \"label\": \"low-angle\"}\n"
                        "]}\n```\n"
                        "Otherwise, answer the question directly.")]
    (swap! session assoc :abort-controller ac)
    (when has-directives?
      (emit! "Capturing " (count images) " user-requested view(s)..."))
    (emit! "Asking AI...")
    ;; Fire-and-forget: output goes through emit!, return nil to REPL
    (-> (ai/call-vision-with-history
          (:messages @session) prompt-text user-images {:signal (.-signal ac)})
        (.then (fn [raw]
                 (let [parsed (ai/parse-response raw)]
                   (if (= (:type parsed) :need-more)
                     ;; AI needs extra views to answer — one round only
                     (let [requests (:requests parsed)
                           extra-images (render-extra-requests requests)
                           ac2 (js/AbortController.)]
                       (store-exchange! question raw)
                       (swap! session assoc :abort-controller ac2)
                       (emit! "AI needs " (count requests) " more view(s)... (generating)")
                       (-> (ai/call-vision-with-history
                             (:messages @session)
                             (ai/additional-images-prompt)
                             extra-images
                             {:signal (.-signal ac2)})
                           (.then (fn [final-raw]
                                    (store-exchange! (ai/additional-images-prompt) final-raw)
                                    (:text (ai/parse-response final-raw))))))
                     ;; Direct answer
                     (do
                       (store-exchange! question raw)
                       (:text parsed))))))
        (.then (fn [answer]
                 (cancel-timer)
                 (emit! "\n" answer "\n")))
        (.catch (fn [err]
                  (cancel-timer)
                  (let [msg (.-message err)]
                    (if (or (= "AbortError" (.-name err))
                            (str/includes? (str msg) "abort"))
                      (emit! "Question cancelled.")
                      (emit! "Error: " msg))))))
    nil))

(defn ^:export end-describe
  "Close the active describe session."
  []
  (if (:active? @session)
    (do
      (when-let [ac (:abort-controller @session)]
        (.abort ac))
      (reset! session {:active? false :target nil :messages []
                       :abort-controller nil :round 0})
      "Describe session closed.")
    "No active describe session."))

(defn ^:export cancel-ai
  "Cancel the in-progress AI call without closing the session."
  []
  (when-let [ac (:abort-controller @session)]
    (.abort ac)
    (swap! session assoc :abort-controller nil)
    (emit! "Describe cancelled."))
  nil)
