(ns ridley.ai.history
  "Explicit AI history — step blocks live in the source code as comments.
   Pure text functions for parsing, building, and extracting history steps."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private step-header-prefix ";; ── AI step ")
(def ^:private step-separator ";; ────────────────────────────────────────────────────────")

;; =============================================================================
;; Commenting / uncommenting
;; =============================================================================

(defn- comment-code
  "Turn code lines into commented lines (prefix each with ;; )."
  [code]
  (->> (str/split-lines code)
       (map #(if (str/blank? %) ";;" (str ";; " %)))
       (str/join "\n")))

(defn- uncomment-code
  "Remove ;; prefix from commented lines."
  [commented]
  (->> (str/split-lines commented)
       (map #(cond
               (str/starts-with? % ";; ") (subs % 3)
               (= % ";;") ""
               :else %))
       (str/join "\n")))

;; =============================================================================
;; Building step blocks
;; =============================================================================

(defn step-block
  "Build a commented history step block from code and step number."
  [code step-number]
  (str step-header-prefix step-number " ──────────────────────────────────────────\n"
       (comment-code code) "\n"
       step-separator))

;; =============================================================================
;; Parsing step blocks from source
;; =============================================================================

(def ^:private step-pattern
  "Regex to match a complete AI step block.
   Group 1: step number, Group 2: commented body."
  #"(?m);; ── AI step (\d+) ─+\n((?:;;[^\n]*\n)*);; ─+")

(defn parse-steps
  "Extract all history step blocks from source text.
   Returns a sorted vector of {:step N :code \"...\"}."
  [source-text]
  (when source-text
    (let [matches (re-seq step-pattern source-text)]
      (when (seq matches)
        (->> matches
             (mapv (fn [[_ n body]]
                     {:step (js/parseInt n 10)
                      :code (uncomment-code (str/trimr body))}))
             (sort-by :step))))))

(defn next-step-number
  "Return the next step number based on existing steps in the source."
  [source-text]
  (if-let [steps (parse-steps source-text)]
    (inc (apply max (map :step steps)))
    1))

;; =============================================================================
;; Extracting history for prompt
;; =============================================================================

(def ^:private ai-block-re
  "Regex to extract code from the >>> AI / <<< AI block."
  #"(?s);; >>> AI[^\n]*\n(.*?)\n;; <<< AI")

(defn- extract-current-code
  "Extract the current AI block code from source text."
  [source-text]
  (when-let [[_ code] (re-find ai-block-re source-text)]
    (str/trim code)))

(defn history-for-prompt
  "Build a <history> block from the source text for inclusion in the AI prompt.
   Includes previous steps (de-commented) and optionally the current AI block.
   Returns nil if no history exists."
  [source-text]
  (let [steps (parse-steps source-text)
        current (extract-current-code source-text)]
    (when (or (seq steps) current)
      (let [parts (cond-> []
                    (seq steps)
                    (into (map (fn [{:keys [step code]}]
                                 (str "[step " step "]\n" code))
                               steps))
                    current
                    (conj (str "Current code:\n" current)))]
        (str "<history>\n"
             (str/join "\n\n" parts)
             "\n</history>")))))

;; =============================================================================
;; Finding step block ranges in source (for editor manipulation)
;; =============================================================================

(defn find-all-step-ranges
  "Find the character ranges of all step blocks in source text.
   Returns a vector of {:from :to :step} sorted by position."
  [source-text]
  (when source-text
    (let [result (atom [])]
      ;; Use exec loop for position tracking
      (let [re (js/RegExp. ";; ── AI step (\\d+) ─+\\n(?:;;[^\\n]*\\n)*;; ─+" "gm")]
        (loop []
          (when-let [m (.exec re source-text)]
            (swap! result conj {:from (.-index m)
                                :to (+ (.-index m) (count (aget m 0)))
                                :step (js/parseInt (aget m 1) 10)})
            (recur))))
      (when (seq @result)
        (sort-by :from @result)))))
