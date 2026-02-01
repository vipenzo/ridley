(ns ridley.voice.parser
  "Deterministic pattern-matching parser for voice commands.
   Tokenizes input, strips fillers, and matches against i18n command tables."
  (:require [clojure.string :as str]
            [ridley.voice.i18n :as i18n]
            [ridley.voice.modes.structure :as structure]))

;; ============================================================
;; Tokenizer
;; ============================================================

(defn- normalize [s]
  (-> s str/trim str/lower-case (str/replace #"[.,;:!?\"'…]" "")))

(defn- strip-fillers [tokens lang]
  (let [filler-set (get i18n/fillers lang #{})]
    (remove filler-set tokens)))

(defn tokenize
  "Split text into tokens, lowercase, strip fillers."
  [text lang]
  (let [tokens (str/split (normalize text) #"\s+")]
    (vec (strip-fillers tokens lang))))

;; ============================================================
;; Number parsing
;; ============================================================

(defn parse-number
  "Parse a token as a number. Supports digits and word forms."
  [token lang]
  (or (get-in i18n/numbers [lang token])
      (when (re-matches #"\d+" token)
        (js/parseInt token 10))))

;; ============================================================
;; Command lookup helpers
;; ============================================================

(defn- match-phrases
  "Check if tokens start with any phrase from a command group.
   Returns [command-key remaining-tokens] or nil.
   Multi-word phrases are checked first (longest match)."
  [tokens command-group lang]
  (let [text (str/join " " tokens)]
    ;; Sort by phrase length descending for longest match
    (some (fn [[cmd-key phrases-map]]
            (let [phrases (get phrases-map lang [])]
              (some (fn [phrase]
                      (when (and (str/starts-with? text phrase)
                                 ;; Ensure word boundary after phrase
                                 (let [plen (count phrase)]
                                   (or (= plen (count text))
                                       (= " " (subs text plen (inc plen))))))
                        (let [rest-text (str/trim (subs text (count phrase)))
                              rest-tokens (if (seq rest-text)
                                            (str/split rest-text #"\s+")
                                            [])]
                          [cmd-key rest-tokens])))
                    ;; Sort phrases longest first
                    (sort-by count > phrases))))
          command-group)))

;; ============================================================
;; Mode switching
;; ============================================================

(defn- try-mode-switch
  "Check if tokens match a mode switch command. Returns {:action :mode-switch :params {:mode X}} or nil."
  [tokens lang]
  (when-let [[mode-key _rest] (match-phrases tokens (:modes i18n/voice-commands) lang)]
    {:action :mode-switch :params {:mode mode-key}}))

;; ============================================================
;; Language switching
;; ============================================================

(defn- try-language-switch
  "Check if tokens match a language switch command. Returns {:action :language-switch :params {:language X}} or nil."
  [tokens lang]
  (when-let [[lang-key _rest] (match-phrases tokens (:language i18n/voice-commands) lang)]
    {:action :language-switch :params {:language lang-key}}))

;; ============================================================
;; Meta commands (work in all modes)
;; ============================================================

(defn- try-meta
  "Try to match meta commands: undo, redo, run, stop."
  [tokens lang]
  (when-let [[cmd-key rest-tokens] (match-phrases tokens (:meta i18n/voice-commands) lang)]
    (let [n (when (seq rest-tokens)
              (some #(parse-number % lang) rest-tokens))]
      (case cmd-key
        :undo {:action :undo :params (when n {:count n})}
        :redo {:action :redo :params (when n {:count n})}
        :run  {:action :run :params {}}
        :stop {:action :stop :params {}}
        nil))))

;; ============================================================
;; Dictation sub-mode
;; ============================================================

(defn- try-dictation-enter
  "Check if tokens match dictation entry command."
  [tokens lang]
  (when-let [[_key _rest] (match-phrases tokens (:dictation i18n/voice-commands) lang)]
    {:action :dictation-enter :params {}}))

;; ============================================================
;; Main parse entry point
;; ============================================================

(defn parse-command
  "Parse voice text into a command map.
   Returns {:action keyword :params map} or nil if unrecognized.

   Priority:
   1. Mode switch (always checked first)
   2. Meta commands (undo/redo/run — work in all modes)
   3. Dictation entry
   4. Mode-specific commands"
  [text mode lang & _opts]
  (let [tokens (tokenize text lang)]
    (when (seq tokens)
      (or
       ;; 1. Mode switch
       (try-mode-switch tokens lang)
       ;; 2. Language switch
       (try-language-switch tokens lang)
       ;; 3. Meta commands
       (try-meta tokens lang)
       ;; 3. Dictation entry (from structure mode)
       (when (= mode :structure)
         (try-dictation-enter tokens lang))
       ;; 4. Mode-specific parsing
       (case mode
         :structure (structure/parse tokens lang)
         :turtle    nil  ;; Phase 4
         :help      nil  ;; Phase 5
         :ai        nil  ;; Passthrough to LLM
         nil)))))
