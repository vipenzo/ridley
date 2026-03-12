(ns ridley.ai.rag
  "Keyword-based RAG for Tier-3 AI code generation.
   Retrieves relevant Spec.md chunks via keyword matching."
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [ridley.ai.rag-chunks :as chunks]))

;; =============================================================================
;; Keyword matching
;; =============================================================================

(defn- tokenize [text]
  (set (map str/lower-case
            (re-seq #"[a-zA-Zà-ú][\w-]*" text))))

(defn- keyword-score
  "Score a chunk against a query using keyword and content overlap."
  [query-tokens chunk]
  (let [chunk-kws (set (map str/lower-case (:keywords chunk)))
        title-tokens (tokenize (:title chunk))
        content-tokens (tokenize (subs (:content chunk) 0 (min 500 (count (:content chunk)))))
        kw-matches (count (set/intersection query-tokens chunk-kws))
        title-matches (count (set/intersection query-tokens title-tokens))
        content-matches (count (set/intersection query-tokens content-tokens))]
    (+ (* 3 kw-matches) (* 2 title-matches) content-matches)))

(defn- retrieve-by-keywords
  "Rank chunks by keyword overlap. Returns top-k chunk contents."
  [query top-k]
  (let [query-tokens (tokenize query)]
    (->> chunks/chunks
         (map #(assoc % :score (keyword-score query-tokens %)))
         (sort-by :score >)
         (take top-k)
         (filter #(pos? (:score %)))
         (mapv :content))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn retrieve-chunks
  "Retrieve the top-k most relevant Spec.md chunks for a query.
   Uses keyword matching (instant, no API calls).
   Optional script-content adds context for better follow-up matching.
   Returns a Promise<vector-of-strings> (chunk contents)."
  ([query] (retrieve-chunks query nil 3))
  ([query script-content] (retrieve-chunks query script-content 3))
  ([query script-content top-k]
   (let [combined (if (and script-content (seq script-content))
                    (str query " " script-content)
                    query)
         results (retrieve-by-keywords combined top-k)]
     (js/console.log "RAG: retrieved" (count results) "chunks for" (pr-str query))
     (js/Promise.resolve results))))
