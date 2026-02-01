(ns ridley.voice.modes.structure
  "Structure mode: paredit-style navigation and editing on Clojure forms."
  (:require [clojure.string :as str]
            [ridley.voice.i18n :as i18n]))

;; ============================================================
;; Helpers
;; ============================================================

(defn- match-cmd
  "Check if tokens start with any phrase from a command group entry.
   Ensures word boundary after phrase match (no partial word matches).
   Returns [remaining-tokens] or nil."
  [tokens cmd-phrases lang]
  (let [phrases (get cmd-phrases lang [])
        text (str/join " " tokens)]
    (some (fn [phrase]
            (when (and (str/starts-with? text phrase)
                       ;; Ensure word boundary: next char is space or end of string
                       (let [plen (count phrase)]
                         (or (= plen (count text))
                             (= " " (subs text plen (inc plen))))))
              (let [rest-text (str/trim (subs text (count phrase)))]
                (if (seq rest-text)
                  (str/split rest-text #"\s+")
                  []))))
          (sort-by count > phrases))))

(defn- parse-number [token lang]
  (or (get-in i18n/numbers [lang token])
      (when (re-matches #"\d+" token)
        (js/parseInt token 10))))

(defn- take-count
  "Extract optional count from tokens. Returns [count remaining-tokens]."
  [tokens lang]
  (if (seq tokens)
    (if-let [n (parse-number (first tokens) lang)]
      [n (rest tokens)]
      [1 tokens])
    [1 tokens]))

;; ============================================================
;; Navigation
;; ============================================================

(defn- try-navigation [tokens lang]
  (let [nav-cmds (:navigation i18n/voice-commands)]
    (some (fn [[nav-key phrases-map]]
            (when-let [rest-tokens (match-cmd tokens phrases-map lang)]
              (let [[n _] (take-count rest-tokens lang)]
                {:action :navigate
                 :params {:direction nav-key :count n}})))
          nav-cmds)))

(defn- try-counted-navigation
  "Handle 'N prossimo' pattern (count before direction)."
  [tokens lang]
  (when (>= (count tokens) 2)
    (when-let [n (parse-number (first tokens) lang)]
      (let [rest-tokens (rest tokens)
            nav-cmds (:navigation i18n/voice-commands)]
        (some (fn [[nav-key phrases-map]]
                (when (match-cmd (vec rest-tokens) phrases-map lang)
                  {:action :navigate
                   :params {:direction nav-key :count n}}))
              nav-cmds)))))

;; ============================================================
;; Editing
;; ============================================================

(defn- try-editing [tokens lang]
  (let [edit-cmds (:editing i18n/voice-commands)]
    (some (fn [[edit-key phrases-map]]
            (when-let [rest-tokens (match-cmd tokens phrases-map lang)]
              (case edit-key
                :delete {:action :delete :params {}}
                :copy   {:action :copy :params {}}
                :cut    {:action :cut :params {}}
                :paste  (let [before? (when (seq rest-tokens)
                                        (some #(when (match-cmd (vec rest-tokens)
                                                                (get-in i18n/voice-commands [:positions :before])
                                                                lang)
                                                 true)
                                              [true]))]
                          {:action :paste :params {:position (if before? :before :after)}})
                :change (when (seq rest-tokens)
                          ;; "cambia in X" — strip preposition, rest is the value
                          (let [prep-cmds (get-in i18n/voice-commands [:prepositions :to])
                                after-prep (or (match-cmd (vec rest-tokens) prep-cmds lang)
                                               rest-tokens)
                                ;; Convert number words to digits, with negative support
                                neg-words (get i18n/negative-words lang #{})
                                resolved (loop [toks (seq after-prep) acc []]
                                           (if-not toks
                                             acc
                                             (let [tok (first toks)
                                                   rest-toks (next toks)]
                                               (if (contains? neg-words (str/lower-case tok))
                                                 ;; Negative word: check next token for number
                                                 (if-let [n (and rest-toks (parse-number (first rest-toks) lang))]
                                                   (recur (next rest-toks) (conj acc (str "-" n)))
                                                   (recur rest-toks (conj acc tok)))
                                                 ;; Regular token: try number conversion
                                                 (if-let [n (parse-number tok lang)]
                                                   (recur rest-toks (conj acc (str n)))
                                                   (recur rest-toks (conj acc tok)))))))
                                value (str/join " " resolved)]
                            (when (seq value)
                              {:action :change :params {:value value}})))
                nil)))
          edit-cmds)))

;; ============================================================
;; Structural (slurp, barf, wrap, unwrap, raise)
;; ============================================================

(defn- try-structural [tokens lang]
  (let [struct-cmds (:structural i18n/voice-commands)]
    (some (fn [[struct-key phrases-map]]
            (when-let [rest-tokens (match-cmd tokens phrases-map lang)]
              (case struct-key
                ;; wrap with optional head: "avvolgi register cubo" → (register cubo $)
                :wrap
                (if (seq rest-tokens)
                  (let [head (str/join " " rest-tokens)]
                    {:action :wrap :params {:head head}})
                  {:action :wrap :params {}})
                ;; join: merge current atom with next sibling
                :join {:action :join :params {}}
                ;; All others: no params
                {:action struct-key :params {}})))
          struct-cmds)))

;; ============================================================
;; Insertion
;; ============================================================

(defn- try-insertion [tokens lang]
  (let [ins-cmds (:insertion i18n/voice-commands)]
    (some (fn [[ins-key phrases-map]]
            (when-let [rest-tokens (match-cmd tokens phrases-map lang)]
              (case ins-key
                :insert
                (let [;; Check for "before" position
                      pos-before (get-in i18n/voice-commands [:positions :before])
                      [position remaining] (if-let [after (match-cmd (vec rest-tokens) pos-before lang)]
                                             [:before after]
                                             [:after rest-tokens])
                      value (str/join " " remaining)]
                  (when (seq value)
                    {:action :insert :params {:text value :position position}}))

                :append
                (let [;; Check if remaining tokens form a sub-command (nuova lista, nuova forma, etc.)
                      sub-cmds (select-keys (:insertion i18n/voice-commands) [:new-form :new-list :new-map])
                      sub-match (some (fn [[sub-key sub-phrases]]
                                        (when-let [sub-rest (match-cmd rest-tokens sub-phrases lang)]
                                          (case sub-key
                                            :new-form (let [head (when (seq sub-rest) (str/join " " sub-rest))]
                                                        {:action :new-form :params {:head head :position :append-child}})
                                            :new-list (let [items (when (seq sub-rest) sub-rest)]
                                                        {:action :new-list :params {:items items :position :append-child}})
                                            :new-map  (let [head (when (seq sub-rest) (first sub-rest))]
                                                        {:action :new-map :params {:head head :position :append-child}})
                                            nil)))
                                      sub-cmds)]
                  (or sub-match
                      ;; Fallback: literal text append
                      (let [value (str/join " " rest-tokens)]
                        (when (seq value)
                          {:action :append :params {:text value}}))))

                :new-form
                (let [head (when (seq rest-tokens) (str/join " " rest-tokens))]
                  {:action :new-form :params {:head head :position :after}})

                :new-list
                (let [items (when (seq rest-tokens) rest-tokens)]
                  {:action :new-list :params {:items items :position :after}})

                :new-map
                (let [head (when (seq rest-tokens) (first rest-tokens))]
                  {:action :new-map :params {:head head :position :after}})

                nil)))
          ins-cmds)))

;; ============================================================
;; Selection
;; ============================================================

(defn- try-selection [tokens lang]
  (let [sel-cmds (:selection i18n/voice-commands)]
    (when-let [rest-tokens (match-cmd tokens (get sel-cmds :enter) lang)]
      (if (seq rest-tokens)
        ;; "seleziona parola/riga/tutto"
        (when-let [target (get-in i18n/select-targets [lang (first rest-tokens)])]
          {:action :select-immediate :params {:target target}})
        ;; Just "seleziona" — enter selection sub-mode
        {:action :selection-enter :params {}}))))

;; ============================================================
;; Transforms (keyword, symbol, hash, deref, capitalize, etc.)
;; ============================================================

(defn- try-transform [tokens lang]
  (let [transform-cmds (:transforms i18n/voice-commands)]
    (some (fn [[transform-key phrases-map]]
            (when (match-cmd tokens phrases-map lang)
              {:action :transform :params {:type transform-key}}))
          transform-cmds)))

;; ============================================================
;; Main parse
;; ============================================================

(defn parse
  "Parse tokens in structure mode. Returns command map or nil."
  [tokens lang]
  (when (seq tokens)
    (or (try-navigation tokens lang)
        (try-counted-navigation tokens lang)
        (try-selection tokens lang)
        (try-editing tokens lang)
        (try-structural tokens lang)
        (try-transform tokens lang)
        (try-insertion tokens lang))))
