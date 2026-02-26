(ns ridley.settings
  "AI/LLM settings management with localStorage persistence."
  (:require [clojure.edn :as edn]))

;; =============================================================================
;; Default Settings
;; =============================================================================

(def default-settings
  {:ai {:enabled false
        :provider :anthropic  ; :anthropic | :openai | :groq | :ollama
        :anthropic-key ""
        :openai-key ""
        :groq-key ""
        :ollama-url "http://localhost:11434"
        :ollama-model "llama3"
        :model "claude-sonnet-4-20250514"
        :groq-model "llama-3.3-70b-versatile"
        :tier :auto}          ; :auto | :tier-1 | :tier-2 | :tier-3
   :audio-feedback true})     ; Play sounds on eval success/error

;; =============================================================================
;; Settings State
;; =============================================================================

(defonce settings (atom default-settings))

;; Connection test status — defined early so set-ai-setting! can reset it
(defonce connection-status (atom {:testing false :result nil :error nil}))

;; Ollama-specific connection status
(defonce ollama-status (atom {:checking false :connected nil :models []}))

;; =============================================================================
;; LocalStorage Persistence
;; =============================================================================

(def ^:private storage-key "ridley-settings")

(defn save-settings!
  "Save current settings to localStorage."
  []
  (let [data (pr-str @settings)]
    (.setItem js/localStorage storage-key data)))

(defn load-settings!
  "Load settings from localStorage."
  []
  (when-let [data (.getItem js/localStorage storage-key)]
    (try
      (let [loaded (edn/read-string data)
            merged (-> (merge default-settings loaded)
                       (assoc :ai (merge (:ai default-settings) (:ai loaded))))]
        (reset! settings merged))
      (catch :default e
        (js/console.warn "Failed to load settings:" e)))))

;; =============================================================================
;; AI Settings Operations
;; =============================================================================

(defn get-ai-setting
  "Get a specific AI setting."
  [key]
  (get-in @settings [:ai key]))

(defn set-ai-setting!
  "Set a specific AI setting and persist."
  [key value]
  (swap! settings assoc-in [:ai key] value)
  (save-settings!)
  ;; Reset connection test status when provider changes
  (when (= key :provider)
    (reset! connection-status {:testing false :result nil :error nil})))

(defn ai-enabled?
  "Check if AI assistant is enabled."
  []
  (get-in @settings [:ai :enabled]))

(defn- match-provider
  "Match the current provider keyword. Uses name comparison
   to avoid ClojureScript case/condp keyword interning issues."
  [provider anthropic-val openai-val groq-val ollama-val default-val]
  (let [p (if (keyword? provider) (name provider) (str provider))]
    (cond
      (= p "anthropic") anthropic-val
      (= p "openai")    openai-val
      (= p "groq")      groq-val
      (= p "ollama")    ollama-val
      :else             default-val)))

(defn ai-configured?
  "Check if AI assistant is properly configured based on provider."
  []
  (let [ai (:ai @settings)
        provider (:provider ai)]
    (and (:enabled ai)
         (match-provider provider
           (seq (:anthropic-key ai))
           (seq (:openai-key ai))
           (seq (:groq-key ai))
           (seq (:ollama-url ai))
           false))))

(defn get-ai-api-key
  "Get the API key for the current provider."
  []
  (let [ai (:ai @settings)
        provider (:provider ai)]
    (match-provider provider
      (:anthropic-key ai)
      (:openai-key ai)
      (:groq-key ai)
      nil
      nil)))

(defn get-ai-model
  "Get the model for the current provider."
  []
  (let [ai (:ai @settings)
        provider (:provider ai)]
    (match-provider provider
      (:model ai)
      (:model ai)
      (:groq-model ai)
      (:ollama-model ai)
      nil)))

;; =============================================================================
;; Model → Tier Detection
;; =============================================================================

(def model-tier-map
  {;; Tier 1: small models
   "qwen2.5:3b" :tier-1
   "llama3.2:3b" :tier-1
   "phi3:mini" :tier-1
   "gemma2:2b" :tier-1

   ;; Tier 2: medium models
   "mistral" :tier-2
   "mistral:8b" :tier-2
   "llama3.2:8b" :tier-2
   "qwen2.5:7b" :tier-2
   "qwen2.5:14b" :tier-2
   "deepseek-coder:7b" :tier-2
   "llama-3.1-8b-instant" :tier-2  ;; Groq
   "gpt-4o-mini" :tier-2
   "claude-3-5-haiku-latest" :tier-2

   ;; Tier 3: large models
   "llama-3.3-70b-versatile" :tier-3  ;; Groq
   "llama3.3:70b" :tier-3
   "qwen2.5:32b" :tier-3
   "deepseek-coder:33b" :tier-3
   "claude-sonnet-4-20250514" :tier-3
   "claude-opus-4-20250514" :tier-3
   "gpt-4o" :tier-3
   "gpt-4-turbo" :tier-3})

(defn detect-tier
  "Detect tier from model name. Uses lookup table first, then pattern matching."
  [model-name]
  (or (get model-tier-map model-name)
      (cond
        (re-find #"70b|72b|65b" model-name) :tier-3
        (re-find #"33b|32b|30b|34b" model-name) :tier-3
        (re-find #"opus|sonnet|gpt-4o(?!-mini)" model-name) :tier-3
        (re-find #"13b|14b|15b|8b|7b" model-name) :tier-2
        (re-find #"haiku|mini" model-name) :tier-2
        :else :tier-1)))

(defn get-effective-tier
  "Get the effective tier: manual override or auto-detected from model."
  []
  (let [manual-tier (get-in @settings [:ai :tier])
        model (get-ai-model)]
    (if (or (nil? manual-tier) (= manual-tier :auto))
      (detect-tier (or model ""))
      manual-tier)))

(defn get-detected-tier
  "Get the auto-detected tier for the current model (ignoring manual override)."
  []
  (detect-tier (or (get-ai-model) "")))

;; =============================================================================
;; Ollama Connection Check
;; =============================================================================

(defn check-ollama-connection!
  "Check if Ollama is running and get available models."
  []
  (let [url (get-ai-setting :ollama-url)]
    (reset! ollama-status {:checking true :connected nil :models []})
    (-> (js/fetch (str url "/api/tags"))
        (.then (fn [^js response]
                 (if (.-ok response)
                   (.json response)
                   (throw (js/Error. "Not OK")))))
        (.then (fn [^js data]
                 (let [models (mapv #(.-name ^js %) (.-models data))]
                   (reset! ollama-status {:checking false :connected true :models models}))))
        (.catch (fn [_]
                  (reset! ollama-status {:checking false :connected false :models []}))))))

;; =============================================================================
;; LLM Connection Validation
;; =============================================================================

(defn- set-conn-ok! []
  (reset! connection-status {:testing false :result :ok :error nil}))

(defn- set-conn-error! [msg]
  (reset! connection-status {:testing false :result :error :error msg}))

(defn- handle-api-error
  "Parse error response body and update connection status."
  [body]
  (when (string? body)
    (cond
      (re-find #"429" body)
      ;; 429 = rate limited, but key is valid
      (set-conn-ok!)

      (re-find #"401" body)
      (set-conn-error! "Invalid API key")

      :else
      (set-conn-error! (str "Error: " (subs body 0 (min 100 (count body))))))))

(defn- test-with-get
  "Test an API connection using GET request with Bearer auth."
  [url api-key]
  (-> (js/fetch url
                (clj->js {:method "GET"
                          :headers {"Authorization" (str "Bearer " api-key)}}))
      (.then (fn [^js resp]
               (if (.-ok resp)
                 (set-conn-ok!)
                 (.text resp))))
      (.then (fn [body] (handle-api-error body)))
      (.catch (fn [e]
                (set-conn-error! (str "Network error: " (.-message e)))))))

(defn validate-connection!
  "Test the LLM connection with a minimal API call.
   Updates connection-status atom."
  []
  (let [ai (:ai @settings)
        provider (:provider ai)
        provider-name (if (keyword? provider) (name provider) (str provider))
        api-key (get-ai-api-key)
        model (get-ai-model)]
    (js/console.log "validate-connection! provider:" provider "name:" provider-name "key-len:" (count (str api-key)))
    (reset! connection-status {:testing true :result nil :error nil})
    (cond
      (= provider-name "anthropic")
      (-> (js/fetch "https://api.anthropic.com/v1/messages"
                    (clj->js {:method "POST"
                              :headers {"Content-Type" "application/json"
                                        "x-api-key" api-key
                                        "anthropic-version" "2023-06-01"
                                        "anthropic-dangerous-direct-browser-access" "true"}
                              :body (js/JSON.stringify
                                     (clj->js {:model model
                                               :max_tokens 1
                                               :messages [{:role "user" :content "hi"}]}))}))
          (.then (fn [^js resp]
                   (if (.-ok resp)
                     (set-conn-ok!)
                     (.text resp))))
          (.then (fn [body] (handle-api-error body)))
          (.catch (fn [e]
                    (set-conn-error! (str "Network error: " (.-message e))))))

      (= provider-name "openai")
      (test-with-get "https://api.openai.com/v1/models" api-key)

      (= provider-name "groq")
      (test-with-get "https://api.groq.com/openai/v1/models" api-key)

      (= provider-name "ollama")
      (-> (check-ollama-connection!)
          (.then (fn [_]
                   (if (:connected @ollama-status)
                     (set-conn-ok!)
                     (set-conn-error! "Cannot connect to Ollama"))))
          (.catch (fn [_]
                    (set-conn-error! "Cannot connect to Ollama"))))

      :else
      (set-conn-error! (str "Unknown provider: " provider-name)))))

;; =============================================================================
;; Audio Feedback
;; =============================================================================

(defn audio-feedback?
  "Check if audio feedback on eval is enabled."
  []
  (get @settings :audio-feedback true))

(defn set-audio-feedback!
  "Enable or disable audio feedback on eval and persist."
  [enabled?]
  (swap! settings assoc :audio-feedback (boolean enabled?))
  (save-settings!))
