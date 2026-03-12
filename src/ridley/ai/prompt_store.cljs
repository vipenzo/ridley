(ns ridley.ai.prompt-store
  "Prompt storage, resolution, and macro expansion.
   Custom prompts are stored in localStorage with provider/model cascade."
  (:require [clojure.string :as str]
            [ridley.settings :as settings]))

;; =============================================================================
;; Prompt registry — metadata about all editable prompts
;; =============================================================================

(def prompt-ids
  "All editable prompt IDs with metadata."
  [{:id "codegen/tier1" :name "Code Generation — Tier 1" :category "AI Assistant"
    :macros #{"source-code" "history" "query"}}
   {:id "codegen/tier2" :name "Code Generation — Tier 2" :category "AI Assistant"
    :macros #{"source-code" "history" "query"}}
   {:id "codegen/tier3" :name "Code Generation — Tier 3" :category "AI Assistant"
    :macros #{"source-code" "history" "query"}}
   {:id "describe/system" :name "Describe — System Prompt" :category "Accessibility"
    :macros #{"source-code" "metadata" "screenshots" "object-name" "slices"}}
   {:id "describe/user" :name "Describe — User Prompt" :category "Accessibility"
    :macros #{"source-code" "metadata" "screenshots" "object-name" "slices"}}])

(def prompt-ids-by-id
  "Quick lookup by ID."
  (into {} (map (juxt :id identity)) prompt-ids))

(defn categories
  "Return prompt IDs grouped by category, preserving order."
  []
  (let [seen (atom #{})
        order (atom [])]
    (doseq [{:keys [category]} prompt-ids]
      (when-not (@seen category)
        (swap! seen conj category)
        (swap! order conj category)))
    (mapv (fn [cat]
            {:category cat
             :prompts (filterv #(= (:category %) cat) prompt-ids)})
          @order)))

;; =============================================================================
;; localStorage CRUD
;; =============================================================================

(defn- storage-key
  "Build localStorage key for a prompt.
   Format: ridley:prompt:<id> or ridley:prompt:<id>:<provider> or ridley:prompt:<id>:<provider>:<model>"
  ([id] (str "ridley:prompt:" id))
  ([id provider] (str "ridley:prompt:" id ":" (name provider)))
  ([id provider model] (str "ridley:prompt:" id ":" (name provider) ":" model)))

(defn get-custom-prompt
  "Get a custom prompt from localStorage. Returns nil if not found."
  ([id] (.getItem js/localStorage (storage-key id)))
  ([id provider] (.getItem js/localStorage (storage-key id provider)))
  ([id provider model] (.getItem js/localStorage (storage-key id provider model))))

(defn save-prompt!
  "Save a custom prompt to localStorage."
  ([id text] (.setItem js/localStorage (storage-key id) text))
  ([id provider text] (.setItem js/localStorage (storage-key id provider) text))
  ([id provider model text] (.setItem js/localStorage (storage-key id provider model) text)))

(defn delete-prompt!
  "Delete a custom prompt from localStorage."
  ([id] (.removeItem js/localStorage (storage-key id)))
  ([id provider] (.removeItem js/localStorage (storage-key id provider)))
  ([id provider model] (.removeItem js/localStorage (storage-key id provider model))))

(defn is-modified?
  "Check if a prompt has been customized for the given variant."
  ([id] (some? (get-custom-prompt id)))
  ([id provider] (some? (get-custom-prompt id provider)))
  ([id provider model] (some? (get-custom-prompt id provider model))))

;; =============================================================================
;; Resolution — cascade from most specific to default
;; =============================================================================

(defn resolve-template
  "Resolve a prompt template using the cascade:
   id:provider:model → id:provider → id (custom) → default-text.
   Returns the template text (before macro expansion)."
  [id default-text]
  (let [provider (settings/get-ai-setting :provider)
        provider-name (when provider (if (keyword? provider) (name provider) (str provider)))
        model (settings/get-ai-model)]
    (or (when (and provider-name model)
          (get-custom-prompt id provider-name model))
        (when provider-name
          (get-custom-prompt id provider-name))
        (get-custom-prompt id)
        default-text)))

;; =============================================================================
;; Macro expansion
;; =============================================================================

(defn expand-macros
  "Expand {{macro}} placeholders in a template using the provided context map.
   Context keys are strings: {\"source-code\" \"...\", \"history\" \"...\", ...}.
   Unknown macros are left as-is and a warning is logged."
  [template context]
  (if (or (nil? template) (nil? context) (not (str/includes? template "{{")))
    template
    (str/replace template #"\{\{([^}]+)\}\}"
      (fn [[full-match macro-name]]
        (if-let [value (get context macro-name)]
          (str value)
          (do (js/console.warn (str "Unknown prompt macro: {{" macro-name "}}"))
              full-match))))))

;; =============================================================================
;; Export / Import
;; =============================================================================

(defn list-all-custom
  "List all custom prompt keys and their values from localStorage.
   Returns a map of storage-key → text."
  []
  (let [prefix "ridley:prompt:"
        n (.-length js/localStorage)
        result (atom {})]
    (dotimes [i n]
      (let [k (.key js/localStorage i)]
        (when (and k (str/starts-with? k prefix))
          (swap! result assoc k (.getItem js/localStorage k)))))
    @result))

(defn export-json
  "Export all custom prompts as a JSON string."
  []
  (let [customs (list-all-custom)]
    (js/JSON.stringify
      (clj->js {:ridley-prompts "1.0"
                :exported (.toISOString (js/Date.))
                :prompts customs})
      nil 2)))

(defn import-json!
  "Import prompts from a JSON string. Returns count of imported prompts."
  [json-str]
  (let [data (js->clj (js/JSON.parse json-str) :keywordize-keys true)
        prompts (:prompts data)]
    (doseq [[k v] prompts]
      (.setItem js/localStorage (name k) v))
    (count prompts)))
