(ns ridley.voice.help.core
  "Help system: fuzzy search engine and voice command parser for help mode."
  (:require [clojure.string :as str]
            [ridley.voice.help.db :as db]
            [ridley.voice.i18n :as i18n]))

;; ============================================================
;; Search engine
;; ============================================================

(defn- score-match
  "Score how well query matches a symbol name or alias.
   Returns numeric score or nil."
  [query text]
  (let [q (str/lower-case query)
        t (str/lower-case text)]
    (cond
      (= q t)                  100  ; exact
      (str/starts-with? t q)    80  ; prefix
      (str/includes? t q)       50  ; contains
      :else                    nil)))

(defn- score-alias-match
  "Score query against all aliases for a language. Returns best score or nil."
  [query aliases lang]
  (let [alias-list (get aliases lang [])]
    (some (fn [alias-text]
            (when-let [s (score-match query alias-text)]
              ;; Alias matches score 5 less than direct matches
              (- s 5)))
          alias-list)))

(defn- score-doc-match
  "Score query against doc string. Returns 30 or nil."
  [query doc lang]
  (let [q (str/lower-case query)
        d (str/lower-case (get doc lang ""))]
    (when (str/includes? d q)
      30)))

(defn- score-entry
  "Score a single entry against query. Returns best score or nil."
  [query sym-name entry lang]
  (let [name-str (name sym-name)]
    (or (score-match query name-str)
        (score-alias-match query (:aliases entry) lang)
        (score-doc-match query (:doc entry) lang))))

(defn search-help
  "Search help entries by query string. Returns vector of {:symbol :score ...entry}
   sorted by score descending, max 70 results."
  [query lang]
  (let [q (str/trim (str/lower-case query))]
    (when (seq q)
      (->> db/help-entries
           (keep (fn [[sym entry]]
                   (when-let [s (score-entry q sym entry lang)]
                     (assoc entry :symbol sym :score s))))
           (sort-by :score >)
           (take 70)
           vec))))

(defn get-tier-entries
  "Get all entries for a given tier, sorted by category then symbol name."
  [tier]
  (->> db/help-entries
       (filter (fn [[_ entry]] (= (:tier entry) tier)))
       (map (fn [[sym entry]] (assoc entry :symbol sym)))
       (sort-by (juxt :category (comp name :symbol)))
       vec))

;; ============================================================
;; Template insertion helper
;; ============================================================

(defn insert-template
  "Extract text and cursor offset from entry template.
   Returns {:text \"...\" :cursor-offset n} where cursor-offset
   is position of | marker (or nil if no marker)."
  [entry]
  (let [tmpl (:template entry)
        idx (str/index-of tmpl "|")]
    (if idx
      {:text (str (subs tmpl 0 idx) (subs tmpl (inc idx)))
       :cursor-offset idx}
      {:text tmpl
       :cursor-offset nil})))

;; ============================================================
;; Voice command parser for help mode
;; ============================================================

(defn- parse-number [token lang]
  (or (get-in i18n/numbers [lang token])
      (when (re-matches #"\d+" token)
        (js/parseInt token 10))))

(defn- match-cmd
  "Check if tokens start with any phrase from a phrases map.
   Returns remaining tokens or nil."
  [tokens phrases-map lang]
  (let [phrases (get phrases-map lang [])
        text (str/join " " tokens)]
    (some (fn [phrase]
            (when (and (str/starts-with? text phrase)
                       (let [plen (count phrase)]
                         (or (= plen (count text))
                             (= " " (subs text plen (inc plen))))))
              (let [rest-text (str/trim (subs text (count phrase)))]
                (if (seq rest-text)
                  (str/split rest-text #"\s+")
                  []))))
          (sort-by count > phrases))))

(defn- try-select
  "Match number words for selecting a result: 'uno' → index 0.
   Only accepts 1-7 (page-relative selection)."
  [tokens lang]
  (when (= 1 (count tokens))
    (when-let [n (or (get-in i18n/ordinals [lang (first tokens)])
                     (parse-number (first tokens) lang))]
      (when (and (pos? n) (<= n 7))
        {:action :help-select :params {:index (dec n)}}))))

(defn- try-navigate
  "Match navigation: prossimo/precedente."
  [tokens lang]
  (let [nav-cmds {:next (get-in i18n/voice-commands [:navigation :next])
                  :prev (get-in i18n/voice-commands [:navigation :previous])}]
    (some (fn [[dir phrases-map]]
            (when (match-cmd tokens phrases-map lang)
              {:action (if (= dir :next) :help-next :help-prev)
               :params {}}))
          nav-cmds)))

(defn- try-exit
  "Match exit commands: esci/exit/indietro."
  [tokens lang]
  (let [exit-phrases {:it ["esci" "indietro" "chiudi"]
                      :en ["exit" "back" "close"]}]
    (when (match-cmd tokens exit-phrases lang)
      {:action :help-exit :params {}})))

(defn- try-browse
  "Match tier browse commands: ridley/clojure/esteso."
  [tokens lang]
  (let [browse-cmds (get-in i18n/voice-commands [:help-browse])]
    (when browse-cmds
      (some (fn [[tier-key phrases-map]]
              (when (match-cmd tokens phrases-map lang)
                {:action :help-browse :params {:tier tier-key}}))
            browse-cmds))))

(defn- try-search
  "Fallback: use all tokens as search query."
  [tokens _lang]
  (when (seq tokens)
    {:action :help-search :params {:query (str/join " " tokens)}}))

(defn parse
  "Parse tokens in help mode. Returns command map or nil."
  [tokens lang]
  (when (seq tokens)
    (or (try-select tokens lang)
        (try-navigate tokens lang)
        (try-exit tokens lang)
        (try-browse tokens lang)
        (try-search tokens lang))))
