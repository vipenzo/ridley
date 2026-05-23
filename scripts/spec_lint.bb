#!/usr/bin/env bb
;; spec_lint.bb — Audit di coerenza Spec.md ↔ bindings SCI
;;
;; Cosa fa
;; -------
;; Confronta i simboli bound nella mappa `base-bindings` di
;; `src/ridley/editor/bindings.cljs` con quelli documentati in `docs/Spec.md`,
;; sottraendo i simboli presenti in un file di allowlist EDN.
;;
;; Il risultato sono i simboli "orfani": bound in SCI ma non documentati né
;; in allowlist. Lo script termina con exit code 1 se ce ne sono, 0 altrimenti.
;;
;; Uso
;; ---
;;   bb scripts/spec_lint.bb
;;   bb scripts/spec_lint.bb --spec docs/Spec.md \
;;                           --bindings src/ridley/editor/bindings.cljs \
;;                           --allowlist scripts/spec_allowlist.edn
;;
;; Default path:
;;   --spec       docs/Spec.md
;;   --bindings   src/ridley/editor/bindings.cljs
;;   --allowlist  scripts/spec_allowlist.edn
;;
;; Output
;; ------
;; - Se non ci sono orfani: messaggio di successo, exit 0.
;; - Se ci sono orfani: lista (con suggerimento), exit 1.
;;
;; Formato allowlist
;; -----------------
;; File EDN che contiene un singolo vettore di simboli. Ogni simbolo dovrebbe
;; essere preceduto da un commento `;;` che spieghi *perché* è esposto a SCI
;; ma non documentato in Spec.md. Esempio:
;;
;;   ;; Allowlist: simboli bound in SCI ma intenzionalmente non documentati.
;;   [
;;    ;; Internal helper used by the `box` DSL macro
;;    box-impl
;;    ;; Internal helper used by the `cyl` DSL macro
;;    cyl-impl
;;   ]
;;
;; Parsing
;; -------
;; - Bindings: regex sui simboli quotati `'name` dentro la mappa `base-bindings`.
;; - Spec.md: tutti i token simbolo-like che appaiono in inline-code (`name`) o
;;   in code-fence (```clojure ... ```). I token estratti vengono filtrati per
;;   somigliare a simboli Clojure (`[a-zA-Z][a-zA-Z0-9!?*+\-]*`).

(require '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[babashka.fs :as fs])

;; ============================================================
;; CLI parsing
;; ============================================================

(def default-opts
  {:spec       "docs/Spec.md"
   :bindings   "src/ridley/editor/bindings.cljs"
   :allowlist  "scripts/spec_allowlist.edn"})

(defn parse-args [args]
  (loop [args args
         opts default-opts]
    (cond
      (empty? args) opts
      (= "--spec" (first args))      (recur (drop 2 args) (assoc opts :spec (second args)))
      (= "--bindings" (first args))  (recur (drop 2 args) (assoc opts :bindings (second args)))
      (= "--allowlist" (first args)) (recur (drop 2 args) (assoc opts :allowlist (second args)))
      (or (= "-h" (first args))
          (= "--help" (first args)))
      (do (println "Usage: bb scripts/spec_lint.bb [--spec PATH] [--bindings PATH] [--allowlist PATH]")
          (System/exit 0))
      :else (do (binding [*out* *err*]
                  (println (str "Argomento sconosciuto: " (first args))))
                (System/exit 2)))))

;; ============================================================
;; ANSI colors (best-effort, no-op se TERM=dumb o stdout non-tty)
;; ============================================================

(def color?
  (and (not= "dumb" (System/getenv "TERM"))
       (some? (System/console))))

(defn- color [code s]
  (if color? (str "[" code "m" s "[0m") s))

(defn- green [s] (color "32" s))
(defn- red   [s] (color "31" s))
(defn- dim   [s] (color "2"  s))

;; ============================================================
;; Bindings parsing
;; ============================================================

(def base-bindings-marker "(def base-bindings")

;; Riga tipo:  '  'cyl   prims/cyl
;; oppure:     '  'cyl-impl    (fn [...] ...)
(def quoted-symbol-re #"^\s+'([a-zA-Z*][a-zA-Z0-9!?*+\-]*)\s")

(defn extract-bindings [bindings-path]
  (let [text (slurp bindings-path)
        ;; Tronca tutto ciò che precede `(def base-bindings`
        idx  (.indexOf text base-bindings-marker)
        body (if (neg? idx) text (subs text idx))
        lines (str/split-lines body)]
    (into (sorted-set)
          (keep (fn [line]
                  (when-let [[_ sym] (re-find quoted-symbol-re line)]
                    sym))
                lines))))

;; ============================================================
;; Spec.md parsing
;; ============================================================

;; Pattern simbolo Clojure: parte con lettera o `*`, può contenere letter/digits/!?*+-
;; Tollerante: accetta solo identificatori "puri" (no namespace, no /, no .)
(def symbol-token-re #"[a-zA-Z*][a-zA-Z0-9!?*+\-]*")

;; Inline-code `...`
(def inline-code-re #"`([^`\n]+)`")

(defn- tokens-from [s]
  (re-seq symbol-token-re s))

(defn- looks-like-symbol?
  "Filtra token che ovviamente NON sono simboli Clojure documentati come API:
   - lunghezza minima 1
   - non puramente alfabetico minuscolo lunghissimo tipo intera frase
   - non parole inglesi/italiane comuni di prosa (best-effort: troppe sarebbero
     da bloccare, qui ci limitiamo a un blocco minimo di rumore)."
  [tok]
  (and (string? tok)
       (>= (count tok) 1)
       ;; almeno una lettera minuscola: esclude costanti tipo `PI`
       ;; -> in realtà alcuni simboli sono tutti maiuscoli, ma per ora ok.
       ;; lasciamo passare tutto: filtreremo via l'intersezione con i bound.
       true))

(defn extract-documented [spec-path]
  (let [text (slurp spec-path)
        lines (str/split-lines text)]
    (loop [lines lines
           in-fence? false
           acc (transient #{})]
      (if (empty? lines)
        (persistent! acc)
        (let [line (first lines)
              fence? (str/starts-with? (str/triml line) "```")]
          (cond
            ;; Toggle code-fence
            fence?
            (recur (rest lines) (not in-fence?) acc)

            ;; Dentro code-fence: prendi tutti i token simbolo-like
            in-fence?
            (recur (rest lines) in-fence?
                   (reduce (fn [a t] (if (looks-like-symbol? t) (conj! a t) a))
                           acc
                           (tokens-from line)))

            ;; Fuori code-fence: estrai solo gli inline-code
            :else
            (let [inlines (map second (re-seq inline-code-re line))
                  toks   (mapcat tokens-from inlines)]
              (recur (rest lines) in-fence?
                     (reduce (fn [a t] (if (looks-like-symbol? t) (conj! a t) a))
                             acc
                             toks)))))))))

;; ============================================================
;; Allowlist parsing
;; ============================================================

(defn read-allowlist [allowlist-path]
  (if-not (fs/exists? allowlist-path)
    (do (binding [*out* *err*]
          (println (dim (str "(allowlist non trovata in " allowlist-path
                             ", procedo con allowlist vuota)"))))
        #{})
    (let [content (slurp allowlist-path)
          data    (try (edn/read-string content)
                       (catch Exception e
                         (binding [*out* *err*]
                           (println (red (str "Errore parsing allowlist EDN: " (.getMessage e)))))
                         (System/exit 2)))]
      (cond
        (nil? data)        #{}
        (sequential? data) (into #{} (map (comp str name)) data)
        :else (do (binding [*out* *err*]
                    (println (red "Allowlist deve essere una sequenza di simboli.")))
                  (System/exit 2))))))

;; ============================================================
;; Main
;; ============================================================

(defn -main [args]
  (let [{:keys [spec bindings allowlist]} (parse-args args)]
    (when-not (fs/exists? bindings)
      (binding [*out* *err*]
        (println (red (str "File bindings non trovato: " bindings))))
      (System/exit 2))
    (when-not (fs/exists? spec)
      (binding [*out* *err*]
        (println (red (str "File Spec.md non trovato: " spec))))
      (System/exit 2))

    (let [bound      (extract-bindings bindings)
          documented (extract-documented spec)
          allowed    (read-allowlist allowlist)
          orphans    (sort (remove (fn [s]
                                     (or (contains? documented s)
                                         (contains? allowed s)))
                                   bound))]
      (println (dim (str "bindings:  " bindings " (" (count bound) " simboli)")))
      (println (dim (str "spec:      " spec " (" (count documented) " token candidati)")))
      (println (dim (str "allowlist: " allowlist " (" (count allowed) " voci)")))
      (println)
      (if (empty? orphans)
        (do (println (green (str "✓ Tutti i " (count bound)
                                 " simboli bound sono documentati in Spec.md o in allowlist.")))
            (System/exit 0))
        (do (println (red (str "✗ " (count orphans)
                               " simboli bound ma non documentati né in allowlist:")))
            (doseq [[idx s] (map-indexed vector orphans)]
              (if (zero? idx)
                (println (str "  - " s
                              "  (suggerimento: aggiungere a Spec.md o ad allowlist)"))
                (println (str "  - " s))))
            (System/exit 1))))))

(-main *command-line-args*)
