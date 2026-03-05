(ns ridley.ai.describe
  "Multimodal LLM integration for the (describe) feature.
   Provides vision API calls, prompt construction, response parsing,
   and metadata collection for geometry description."
  (:require [clojure.string :as str]
            [ridley.settings :as settings]
            [ridley.scene.registry :as registry]
            [ridley.measure.core :as measure]
            [ridley.manifold.core :as manifold]
            [ridley.geometry.faces :as faces]
            [ridley.editor.codemirror :as codemirror]))

;; =============================================================================
;; AI status
;; =============================================================================

(defn ^:export ai-status
  "Check AI provider configuration. Returns a status map."
  []
  (let [configured? (settings/ai-configured?)
        provider (settings/get-ai-setting :provider)
        model (settings/get-ai-model)]
    {:provider (when provider (keyword provider))
     :model model
     :ready? (boolean configured?)
     :enabled? (settings/ai-enabled?)}))

;; =============================================================================
;; Metadata collection
;; =============================================================================

(defn ^:export collect-metadata
  "Gather structural metadata for the target object(s).
   Returns a map suitable for JSON serialization in the prompt."
  [target]
  (let [meshes (if target
                 (when-let [m (registry/get-mesh target)]
                   [{:name target :mesh m}])
                 (map (fn [name]
                        {:name name :mesh (registry/get-mesh name)})
                      (registry/visible-names)))
        single? (and target (= 1 (count meshes)))]
    (when (seq meshes)
      (let [primary (first meshes)
            mesh (:mesh primary)
            b (measure/bounds mesh)
            status (try (manifold/get-mesh-status mesh) (catch :default _ nil))
            face-groups (try (faces/face-ids mesh) (catch :default _ nil))]
        (cond-> {:target (if target (name target) "all visible")
                 :bounds {:min (:min b) :max (:max b) :size (:size b)}
                 :center (:center b)
                 :vertices (count (:vertices mesh))
                 :faces (count (:faces mesh))}
          (:manifold? status)
          (assoc :manifold true
                 :volume (:volume status)
                 :surface-area (:surface-area status))
          (seq face-groups)
          (assoc :face-groups (mapv name face-groups))
          (not single?)
          (assoc :registered-objects (mapv (comp name :name) meshes)))))))

;; =============================================================================
;; Source code retrieval
;; =============================================================================

(defn ^:export get-source-code
  "Get the current definitions panel source code."
  []
  (codemirror/get-value))

;; =============================================================================
;; Prompt construction
;; =============================================================================

(def ^:private system-prompt
  "You are analyzing a 3D model created with Ridley, a turtle-graphics-based
parametric CAD tool. Your description will be read by a screen reader for
a blind user, so be precise, spatial, and concrete.

Use structural and spatial references (\"the cylindrical protrusion on the
top face\"), never visual-only references (\"the blue part\").

When describing geometry:
- Use everyday analogies (\"shaped like a donut\", \"resembles a gear\")
- Give approximate proportions and absolute sizes
- Describe spatial relationships (above, beside, through, concentric)
- Note symmetry, patterns, and repetitions
- Mention printability concerns (overhangs, thin walls, disconnected parts)

Ridley uses a Z-up coordinate system. The source code uses turtle graphics:
f (forward), th (turn horizontal), tv (turn vertical), tr (turn roll).
Shapes are extruded along the turtle path. Boolean operations use
mesh-union, mesh-difference, mesh-intersection.")

(defn initial-prompt
  "Build the initial describe prompt with source code and metadata."
  [source-code metadata]
  (str "## Source Code\n<source>\n"
       (or source-code "(no source available)")
       "\n</source>\n\n"
       "## Structural Data\n```json\n"
       (js/JSON.stringify (clj->js metadata) nil 2)
       "\n```\n\n"
       "## Task — Initial Description\n\n"
       "Provide a comprehensive description of this 3D object covering:\n"
       "1. **Overall shape**: What does this look like? Use everyday analogies.\n"
       "2. **Dimensions**: Approximate proportions and absolute sizes.\n"
       "3. **Key features**: Holes, protrusions, cavities, symmetry, patterns.\n"
       "4. **Spatial layout**: How features relate to each other.\n"
       "5. **Printability notes**: Any obvious overhangs, thin walls, or issues.\n\n"
       "If you need additional views or cross-sections to give an accurate "
       "description, respond with a JSON request in a fenced code block:\n\n"
       "```json\n"
       "{\"need_more\": true, \"requests\": [\n"
       "  {\"type\": \"slice\", \"axis\": \"z\", \"position\": 25.0},\n"
       "  {\"type\": \"view\", \"from\": [1, 1, 0.5], \"label\": \"low-angle front-right\"}\n"
       "]}\n```\n\n"
       "You may include explanatory text before or after the JSON block."))

(defn follow-up-prompt
  "Build a follow-up prompt with additional images the AI requested."
  [requests]
  (str "Here are the additional views/slices you requested:\n\n"
       (str/join "\n"
         (map (fn [{:keys [type axis position from label]}]
                (case type
                  "slice" (str "- Slice at " axis "=" position)
                  "view" (str "- View from " (pr-str from)
                              (when label (str " (" label ")")))
                  (str "- " (pr-str type))))
              requests))
       "\n\nNow provide your complete description based on all available views. "
       "If you still need more data, you may request again."))

(defn additional-images-prompt
  "Build the text for a follow-up round with auto-generated images."
  []
  (str "Here are the additional views and cross-sections you requested. "
       "Please provide your complete description based on all available data."))

;; =============================================================================
;; Provider-specific multimodal message building
;; =============================================================================

(defn- data-url-to-base64
  "Strip the data:image/png;base64, prefix from a data URL."
  [data-url]
  (second (.split data-url ",")))

(defn- anthropic-image-block [data-url]
  {:type "image"
   :source {:type "base64"
            :media_type "image/png"
            :data (data-url-to-base64 data-url)}})

(defn- openai-image-block [data-url]
  {:type "image_url"
   :image_url {:url data-url :detail "low"}})

(defn- images->anthropic-content
  "Convert images to Anthropic content blocks interleaved with labels."
  [images]
  (mapcat (fn [[label data-url]]
            [{:type "text" :text (str "View: " (name label))}
             (anthropic-image-block data-url)])
          images))

(defn- images->openai-content
  "Convert images to OpenAI content blocks interleaved with labels."
  [images]
  (mapcat (fn [[label data-url]]
            [{:type "text" :text (str "View: " (name label))}
             (openai-image-block data-url)])
          images))

;; =============================================================================
;; HTTP helpers
;; =============================================================================

(defn- throw-api-error
  "Throw a user-friendly error based on HTTP status code."
  [status body provider]
  (throw
    (js/Error.
      (case status
        401 "AI authentication failed. Check your API key."
        429 "AI rate limit reached. Wait a moment and try again."
        413 "Scene too complex for AI context. Try (describe :single-object)."
        (str provider " API error (" status "): "
             (subs body 0 (min 200 (count body))))))))

(defn- handle-response
  "Handle a fetch Response — return .json() on success, throw on error."
  [^js resp provider]
  (if (.-ok resp)
    (.json resp)
    (let [status (.-status resp)]
      (-> (.text resp)
          (.then (fn [body] (throw-api-error status body provider)))))))

(defn- fetch-json
  "Fetch with JSON body and optional AbortController signal."
  [url headers body-map signal]
  (js/fetch url
    (clj->js
      (cond-> {:method "POST"
               :headers headers
               :body (js/JSON.stringify (clj->js body-map))}
        signal (assoc :signal signal)))))

;; =============================================================================
;; Provider API calls
;; =============================================================================

(defn- call-anthropic
  "Call Anthropic Messages API with multimodal content. Returns Promise<string>."
  [api-key model messages signal]
  (-> (fetch-json "https://api.anthropic.com/v1/messages"
                  {"Content-Type" "application/json"
                   "x-api-key" api-key
                   "anthropic-version" "2023-06-01"
                   "anthropic-dangerous-direct-browser-access" "true"}
                  {:model model
                   :max_tokens 4096
                   :system system-prompt
                   :messages messages}
                  signal)
      (.then #(handle-response % "Anthropic"))
      (.then (fn [^js data]
               (.-text (aget (.-content data) 0))))))

(defn- call-openai
  "Call OpenAI-compatible API with multimodal content. Returns Promise<string>."
  [url api-key model messages signal]
  (-> (fetch-json url
                  (cond-> {"Content-Type" "application/json"}
                    api-key (assoc "Authorization" (str "Bearer " api-key)))
                  {:model model
                   :max_tokens 4096
                   :messages (into [{:role "system" :content system-prompt}]
                                   messages)}
                  signal)
      (.then #(handle-response % "OpenAI"))
      (.then (fn [^js data]
               (.. data -choices (at 0) -message -content)))))

(defn- call-ollama
  "Call Ollama chat API with multimodal content. Returns Promise<string>."
  [url model messages signal]
  (-> (fetch-json (str url "/api/chat")
                  {"Content-Type" "application/json"}
                  {:model model
                   :stream false
                   :messages (into [{:role "system" :content system-prompt}]
                                   messages)}
                  signal)
      (.then #(handle-response % "Ollama"))
      (.then (fn [^js data]
               (.. data -message -content)))))

;; =============================================================================
;; Message building (per-provider format)
;; =============================================================================

(defn- build-user-message
  "Build a user message with text and optional images, formatted per provider."
  [provider-name text images]
  (case provider-name
    "anthropic"
    {:role "user"
     :content (if (seq images)
                (into [{:type "text" :text text}]
                      (images->anthropic-content images))
                text)}

    ("openai" "groq")
    {:role "user"
     :content (if (seq images)
                (into [{:type "text" :text text}]
                      (images->openai-content images))
                text)}

    "ollama"
    (if (seq images)
      {:role "user"
       :content text
       :images (mapv (fn [[_ du]] (data-url-to-base64 du)) images)}
      {:role "user" :content text})))

;; =============================================================================
;; Unified vision API
;; =============================================================================

(defn- resolve-provider
  "Get provider config. Throws if not configured."
  []
  (when-not (settings/ai-configured?)
    (throw (js/Error. "No AI provider configured. Use (ai-status) to check.")))
  (let [provider (settings/get-ai-setting :provider)]
    {:provider-name (if (keyword? provider) (name provider) (str provider))
     :api-key (settings/get-ai-api-key)
     :model (settings/get-ai-model)}))

(defn- dispatch-call
  "Dispatch a messages array to the configured provider."
  [provider-name api-key model messages signal]
  (case provider-name
    "anthropic" (call-anthropic api-key model messages signal)
    "openai" (call-openai "https://api.openai.com/v1/chat/completions"
                          api-key model messages signal)
    "groq" (call-openai "https://api.groq.com/openai/v1/chat/completions"
                        api-key model messages signal)
    "ollama" (call-ollama (settings/get-ai-setting :ollama-url)
                          model messages signal)
    (js/Promise.reject (js/Error. (str "Unknown provider: " provider-name)))))

(defn call-vision
  "Send a multimodal (text + images) request to the configured LLM.
   text-prompt — string prompt
   images      — seq of [label data-url] pairs
   opts        — {:signal AbortController.signal}
   Returns a Promise<string>."
  [text-prompt images opts]
  (let [{:keys [provider-name api-key model]} (resolve-provider)
        user-msg (build-user-message provider-name text-prompt images)
        messages [user-msg]]
    (dispatch-call provider-name api-key model messages (:signal opts))))

(defn call-vision-with-history
  "Send a multimodal request with conversation history.
   history    — vector of prior messages [{:role :content}]
   new-text   — new user question
   new-images — optional new images
   opts       — {:signal AbortController.signal}
   Returns a Promise<string>."
  [history new-text new-images opts]
  (let [{:keys [provider-name api-key model]} (resolve-provider)
        user-msg (build-user-message provider-name new-text new-images)
        messages (conj (vec history) user-msg)]
    (dispatch-call provider-name api-key model messages (:signal opts))))

;; =============================================================================
;; Response parsing
;; =============================================================================

(defn parse-response
  "Parse an AI response for describe. Checks for structured view/slice requests.
   Returns:
     {:type :description :text string}                 — final description
     {:type :need-more   :text string :requests [...]} — AI wants more data"
  [text]
  (let [json-blocks (re-seq #"```json\s*\n([\s\S]*?)```" text)]
    (if-let [parsed (some->> json-blocks
                             (map second)
                             (keep #(try (js->clj (js/JSON.parse %) :keywordize-keys true)
                                        (catch :default _ nil)))
                             (filter :need_more)
                             first)]
      {:type :need-more
       :text (str/trim (str/replace text #"```json[\s\S]*?```" ""))
       :requests (:requests parsed)}
      {:type :description
       :text text})))
