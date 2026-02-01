(ns ridley.voice.modes.text
  "Text mode: character/word/line navigation and editing."
  (:require [clojure.string :as str]
            [ridley.voice.i18n :as i18n]))

;; ============================================================
;; Helpers
;; ============================================================

(defn- match-cmd [tokens cmd-phrases lang]
  (let [phrases (get cmd-phrases lang [])
        text (str/join " " tokens)]
    (some (fn [phrase]
            (when (str/starts-with? text phrase)
              (let [rest-text (str/trim (subs text (count phrase)))]
                (if (seq rest-text)
                  (str/split rest-text #"\s+")
                  []))))
          (sort-by count > phrases))))

(defn- parse-number [token lang]
  (or (get-in i18n/numbers [lang token])
      (when (re-matches #"\d+" token)
        (js/parseInt token 10))))

(defn- parse-unit [token lang]
  (get-in i18n/text-units [lang token]))

(defn- take-count-and-unit
  "Extract optional count and unit from tokens.
   Returns [count unit remaining-tokens].
   Default count=1, default unit=:char."
  [tokens lang]
  (loop [toks tokens count-val nil unit-val nil]
    (if (empty? toks)
      [(or count-val 1) (or unit-val :char) []]
      (let [t (first toks)]
        (cond
          ;; Try as number
          (and (nil? count-val) (parse-number t lang))
          (recur (rest toks) (parse-number t lang) unit-val)
          ;; Try as unit
          (and (nil? unit-val) (parse-unit t lang))
          (recur (rest toks) count-val (parse-unit t lang))
          ;; Unknown token — stop
          :else
          [(or count-val 1) (or unit-val :char) (vec toks)])))))

;; ============================================================
;; Navigation
;; ============================================================

(defn- try-navigation [tokens lang]
  (let [nav-cmds (:navigation i18n/voice-commands)]
    (some (fn [[nav-key phrases-map]]
            (when-let [rest-tokens (match-cmd tokens phrases-map lang)]
              (case nav-key
                ;; next/previous support count and unit
                (:next :previous)
                (let [[n unit _] (take-count-and-unit rest-tokens lang)]
                  {:action :navigate
                   :params {:direction nav-key :count n :unit unit}})

                ;; first/last = home/end of line
                (:first :last)
                {:action :navigate :params {:direction nav-key}}

                ;; top/bottom
                :top
                {:action :navigate :params {:direction :top}}

                ;; into/out don't apply in text mode
                nil)))
          nav-cmds)))

(defn- try-counted-navigation
  "Handle 'N prossimo [unita]' pattern."
  [tokens lang]
  (when (>= (count tokens) 2)
    (when-let [n (parse-number (first tokens) lang)]
      (let [rest-tokens (rest tokens)
            nav-cmds (select-keys (:navigation i18n/voice-commands) [:next :previous])]
        (some (fn [[nav-key phrases-map]]
                (when-let [after (match-cmd (vec rest-tokens) phrases-map lang)]
                  (let [unit (when (seq after) (parse-unit (first after) lang))]
                    {:action :navigate
                     :params {:direction nav-key :count n :unit (or unit :char)}})))
              nav-cmds)))))

(defn- try-goto-line
  "Handle 'riga N' / 'line N'."
  [tokens lang]
  (let [line-words (get-in i18n/text-units [lang])
        line-triggers (keep (fn [[word unit]] (when (= unit :line) word)) line-words)]
    (when (and (>= (count tokens) 2)
               (some #{(first tokens)} line-triggers))
      (when-let [n (parse-number (second tokens) lang)]
        {:action :goto-line :params {:line n}}))))

(defn- try-find
  "Handle 'cerca X' / 'find X'."
  [tokens lang]
  (let [find-words {:it ["cerca"] :en ["find" "search"]}
        phrases (get find-words lang [])]
    (when (some #{(first tokens)} phrases)
      (let [query (str/join " " (rest tokens))]
        (when (seq query)
          {:action :find :params {:query query}})))))

(defn- try-bottom
  "Handle 'fondo' / 'bottom'."
  [tokens lang]
  (let [bottom-words {:it ["fondo"] :en ["bottom"]}
        phrases (get bottom-words lang [])]
    (when (some #{(first tokens)} phrases)
      {:action :navigate :params {:direction :bottom}})))

;; ============================================================
;; Selection
;; ============================================================

(defn- try-selection [tokens lang]
  (let [sel-enter (get-in i18n/voice-commands [:selection :enter])]
    (when-let [rest-tokens (match-cmd tokens sel-enter lang)]
      (if (seq rest-tokens)
        ;; "seleziona parola/riga/tutto"
        (when-let [target (get-in i18n/select-targets [lang (first rest-tokens)])]
          {:action :select-immediate :params {:target target}})
        ;; Just "seleziona" — enter selection sub-mode
        {:action :selection-enter :params {}}))))

(defn- try-selection-exit [tokens lang]
  (let [sel-exit (get-in i18n/voice-commands [:selection :exit])]
    (when (match-cmd tokens sel-exit lang)
      {:action :selection-exit :params {}})))

;; ============================================================
;; Editing
;; ============================================================

(defn- try-editing [tokens lang]
  (let [edit-cmds (:editing i18n/voice-commands)]
    (some (fn [[edit-key phrases-map]]
            (when-let [rest-tokens (match-cmd tokens phrases-map lang)]
              (case edit-key
                :delete (let [unit (when (seq rest-tokens)
                                     (parse-unit (first rest-tokens) lang))]
                          {:action :delete :params {:unit unit}})
                :copy   (let [unit (when (seq rest-tokens)
                                     (parse-unit (first rest-tokens) lang))]
                          {:action :copy :params {:unit unit}})
                :cut    (let [unit (when (seq rest-tokens)
                                     (parse-unit (first rest-tokens) lang))]
                          {:action :cut :params {:unit unit}})
                :paste  {:action :paste :params {}}
                :change (when (seq rest-tokens)
                          (let [prep-cmds (get-in i18n/voice-commands [:prepositions :to])
                                after-prep (or (match-cmd (vec rest-tokens) prep-cmds lang)
                                               rest-tokens)
                                value (str/join " " after-prep)]
                            (when (seq value)
                              {:action :change :params {:value value}})))
                nil)))
          edit-cmds)))

;; ============================================================
;; Dictation
;; ============================================================

(defn- try-dictation [tokens lang]
  (let [dict-cmds (get-in i18n/voice-commands [:dictation :enter])]
    (when (match-cmd tokens dict-cmds lang)
      {:action :dictation-enter :params {}})))

;; ============================================================
;; Main parse
;; ============================================================

(defn parse
  "Parse tokens in text mode. Returns command map or nil."
  [tokens lang]
  (when (seq tokens)
    (or (try-navigation tokens lang)
        (try-counted-navigation tokens lang)
        (try-goto-line tokens lang)
        (try-find tokens lang)
        (try-bottom tokens lang)
        (try-selection tokens lang)
        (try-selection-exit tokens lang)
        (try-editing tokens lang)
        (try-dictation tokens lang))))
