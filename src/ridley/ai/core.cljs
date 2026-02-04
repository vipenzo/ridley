(ns ridley.ai.core
  "AI code generation — calls LLM providers and returns generated code."
  (:require [clojure.string :as str]
            [ridley.settings :as settings]
            [ridley.ai.prompts :as prompts]))

;; =============================================================================
;; Conversation History
;; =============================================================================

(def ai-history
  "Session history of AI exchanges. Each entry: {:input str :output str :feedback nil|str}"
  (atom []))

(defn clear-history!
  "Reset AI conversation history."
  []
  (reset! ai-history []))

(defn add-entry!
  "Record an AI exchange (user input → AI output)."
  [input output]
  (swap! ai-history conj {:input input :output output :feedback nil}))

(defn add-feedback!
  "Attach explicit feedback/correction to the last history entry."
  [text]
  (swap! ai-history
    (fn [h]
      (if (seq h)
        (assoc-in (vec h) [(dec (count h)) :feedback] text)
        h))))

(defn history-for-prompt
  "Format recent history entries as a <history> block for the prompt.
   Returns nil if history is empty."
  []
  (let [recent (take-last 5 @ai-history)]
    (when (seq recent)
      (str "<history>\n"
           (str/join "\n---\n"
             (map (fn [{:keys [input output feedback]}]
                    (str "USER: " input "\nAI: " output
                         (when feedback (str "\nCORRECTION: " feedback))))
                  recent))
           "\n</history>"))))

;; =============================================================================
;; Response Parsing
;; =============================================================================

(defn- extract-code
  "Extract code from LLM response, stripping markdown fences if present."
  [text]
  (let [trimmed (str/trim text)]
    (if-let [[_ code] (re-find #"(?s)```(?:clojure)?\n?(.*?)```" trimmed)]
      (str/trim code)
      trimmed)))

(defn- parse-tier2-response
  "Parse a Tier 2+ JSON response. Returns {:type :code :code ...} or {:type :clarification :question ...}.
   Falls back to treating the response as raw code if JSON parsing fails."
  [raw-text]
  (let [trimmed (str/trim raw-text)
        ;; Strip markdown fences if wrapping JSON
        cleaned (if-let [[_ inner] (re-find #"(?s)```(?:json)?\n?(.*?)```" trimmed)]
                  (str/trim inner)
                  trimmed)]
    (try
      (let [parsed (js/JSON.parse cleaned)]
        (case (.-type parsed)
          "code"          {:type :code :code (.-code parsed)}
          "clarification" {:type :clarification :question (.-question parsed)}
          ;; Unknown type — treat as code
          {:type :code :code (extract-code raw-text)}))
      (catch :default _
        ;; JSON parse failed — treat as raw code (graceful fallback)
        {:type :code :code (extract-code raw-text)}))))

;; =============================================================================
;; Few-shot examples — critical for open-source models (llama, etc.)
;; These reinforce the for+register pattern better than system prompt alone.
;; =============================================================================

(def ^:private few-shot-examples
  [{:role "user"    :content "un cubo di lato 20"}
   {:role "assistant" :content "(register cube (box 20))"}
   {:role "user"    :content "6 sfere di raggio 10 in cerchio con raggio 40"}
   {:role "assistant" :content "(register spheres
  (for [i (range 6)]
    (attach (sphere 10) (th (* i 60)) (f 40))))"}
   {:role "user"    :content "griglia 3x3 di cubi lato 8, spaziatura 20"}
   {:role "assistant" :content "(register cubes
  (for [row (range 3)
        col (range 3)]
    (attach (box 8) (f (* col 20)) (th 90) (f (* row 20)))))"}
   {:role "user"    :content "cubo lato 40 con 4 fori cilindrici raggio 3 in cerchio raggio 12"}
   {:role "assistant" :content "(def holes
  (mesh-union
    (for [i (range 4)]
      (attach (cyl 3 42) (th (* i 90)) (f 12)))))
(register drilled-cube (mesh-difference (box 40) holes))"}])

;; Tier-2 few-shot examples use JSON output format
(def ^:private few-shot-examples-tier2
  [{:role "user"    :content "un cubo di lato 20"}
   {:role "assistant" :content "{\"type\": \"code\", \"code\": \"(register cube (box 20))\"}"}
   {:role "user"    :content "6 sfere di raggio 10 in cerchio con raggio 40"}
   {:role "assistant" :content "{\"type\": \"code\", \"code\": \"(register spheres\\n  (for [i (range 6)]\\n    (attach (sphere 10) (th (* i 60)) (f 40))))\"}"}
   {:role "user"    :content "aggiungi delle cose"}
   {:role "assistant" :content "{\"type\": \"clarification\", \"question\": \"Cosa vuoi aggiungere? Sfere, cubi, cilindri?\"}"}])

;; =============================================================================
;; Message Building
;; =============================================================================

(defn- build-user-content
  "Build the user message content. For tier-2+, includes history and script context."
  [prompt tier script-content]
  (let [tier-2+? (#{:tier-2 :tier-3} tier)
        history (when tier-2+? (history-for-prompt))
        script  (when (and tier-2+? script-content)
                  (str "<script>\n" script-content "\n</script>"))
        parts   (filterv some? [history script prompt])]
    (str/join "\n\n" parts)))

(defn- get-few-shot [tier]
  (if (#{:tier-2 :tier-3} tier)
    few-shot-examples-tier2
    few-shot-examples))

(defn- build-messages
  "Build the messages array with system prompt, few-shot examples, and user prompt."
  [prompt tier script-content]
  (let [system-prompt (prompts/get-prompt tier)
        examples (get-few-shot tier)
        user-content (build-user-content prompt tier script-content)]
    (into [{:role "system" :content system-prompt}]
          (conj (vec examples)
                {:role "user" :content user-content}))))

(defn- build-messages-no-system
  "Build messages without system role (for Anthropic which uses separate system param)."
  [prompt tier script-content]
  (let [examples (get-few-shot tier)
        user-content (build-user-content prompt tier script-content)]
    (conj (vec examples)
          {:role "user" :content user-content})))

;; =============================================================================
;; Provider-specific API calls
;; =============================================================================

(defn- call-anthropic
  "Call Anthropic Messages API. Returns a Promise<string>."
  [api-key model prompt tier script-content]
  (-> (js/fetch "https://api.anthropic.com/v1/messages"
                (clj->js {:method "POST"
                          :headers {"Content-Type" "application/json"
                                    "x-api-key" api-key
                                    "anthropic-version" "2023-06-01"
                                    "anthropic-dangerous-direct-browser-access" "true"}
                          :body (js/JSON.stringify
                                 (clj->js {:model model
                                           :max_tokens 1024
                                           :system (prompts/get-prompt tier)
                                           :messages (build-messages-no-system prompt tier script-content)}))}))
      (.then (fn [^js resp]
               (if (.-ok resp)
                 (.json resp)
                 (-> (.text resp)
                     (.then (fn [body] (throw (js/Error. (str "Anthropic API error: " body)))))))))
      (.then (fn [^js data]
               (let [content (aget (.-content data) 0)]
                 (.-text content))))))

(defn- call-openai-compatible
  "Call an OpenAI-compatible Chat Completions API. Returns a Promise<string>."
  [url api-key model prompt tier script-content]
  (-> (js/fetch url
                (clj->js {:method "POST"
                          :headers (cond-> {"Content-Type" "application/json"}
                                     api-key (assoc "Authorization" (str "Bearer " api-key)))
                          :body (js/JSON.stringify
                                 (clj->js {:model model
                                           :max_tokens 1024
                                           :messages (build-messages prompt tier script-content)}))}))
      (.then (fn [^js resp]
               (if (.-ok resp)
                 (.json resp)
                 (-> (.text resp)
                     (.then (fn [body] (throw (js/Error. (str "API error: " body)))))))))
      (.then (fn [^js data]
               (.. data -choices (at 0) -message -content)))))

(defn- call-ollama
  "Call Ollama chat API. Returns a Promise<string>."
  [url model prompt tier script-content]
  (-> (js/fetch (str url "/api/chat")
                (clj->js {:method "POST"
                          :headers {"Content-Type" "application/json"}
                          :body (js/JSON.stringify
                                 (clj->js {:model model
                                           :stream false
                                           :messages (build-messages prompt tier script-content)}))}))
      (.then (fn [^js resp]
               (if (.-ok resp)
                 (.json resp)
                 (-> (.text resp)
                     (.then (fn [body] (throw (js/Error. (str "Ollama API error: " body)))))))))
      (.then (fn [^js data]
               (.. data -message -content)))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn generate
  "Generate code from a natural language prompt using the configured LLM.
   Options:
     :script-content - current script text (for tier-2+ context)
   Returns a Promise that resolves to:
     {:type :code :code string}           — generated code
     {:type :clarification :question string} — AI needs more info
   Or rejects with an error."
  ([prompt] (generate prompt nil))
  ([prompt {:keys [script-content]}]
   (when-not (settings/ai-configured?)
     (throw (js/Error. "AI not configured. Open Settings to set up a provider and API key.")))
   (let [provider (settings/get-ai-setting :provider)
         provider-name (if (keyword? provider) (name provider) (str provider))
         api-key (settings/get-ai-api-key)
         model (settings/get-ai-model)
         tier (settings/get-effective-tier)]
     (-> (case provider-name
           "anthropic" (call-anthropic api-key model prompt tier script-content)
           "openai"    (call-openai-compatible
                         "https://api.openai.com/v1/chat/completions" api-key model prompt tier script-content)
           "groq"      (call-openai-compatible
                         "https://api.groq.com/openai/v1/chat/completions" api-key model prompt tier script-content)
           "ollama"    (call-ollama (settings/get-ai-setting :ollama-url) model prompt tier script-content)
           (js/Promise.reject (js/Error. (str "Unknown provider: " provider-name))))
         (.then (fn [raw-text]
                  (if (= tier :tier-1)
                    ;; Tier 1: raw code output
                    {:type :code :code (extract-code raw-text)}
                    ;; Tier 2+: JSON with code or clarification
                    (parse-tier2-response raw-text))))))))
