(ns ridley.library.core
  "SCI namespace integration for user-defined libraries.

   Evaluates active libraries and produces a namespace map suitable for
   SCI's :namespaces option, enabling prefixed access (e.g., robot/arm)."
  (:require [sci.core :as sci]
            [ridley.library.storage :as storage]))

;; ============================================================
;; Symbol extraction
;; ============================================================

(defn- extract-def-names
  "Extract public def/defn names from source string.
   Excludes defn- (private) definitions.
   Only matches defs at the start of a line (ignores commented-out code)."
  [source]
  (let [;; (?m) = multiline: ^ matches start of each line
        ;; Match (def name or (defn name but NOT (defn- name
        matches (re-seq #"(?m)^\s*\(def(?:n)?\s+(?:\^[^\s]+\s+)?([a-zA-Z*+!_?<>=][a-zA-Z0-9*+!_?<>=\-']*)" source)]
    (set (map second matches))))

;; ============================================================
;; Dependency validation
;; ============================================================

(defn validate-dependencies
  "Check if all requires of a library are in the active set.
   Returns {:ok true} or {:error \"msg\"}."
  [lib-name active-set]
  (when-let [lib (storage/get-library lib-name)]
    (let [missing (remove active-set (:requires lib))]
      (if (seq missing)
        {:error (str lib-name " requires: " (clojure.string/join ", " missing) " (not loaded)")}
        {:ok true}))))

(defn detect-circular-deps
  "Check for circular dependencies among active libraries.
   Returns nil if no cycles, or a string describing the cycle."
  [active-names]
  (let [get-requires (fn [name]
                       (or (:requires (storage/get-library name)) []))]
    ;; Simple DFS cycle detection
    (loop [remaining active-names
           visited #{}
           path []]
      (if (empty? remaining)
        nil ;; no cycles
        (let [name (first remaining)
              requires (get-requires name)]
          (if (some #{name} path)
            (str "Circular dependency: " (clojure.string/join " → " (conj path name)))
            (let [cycle-in-deps (some (fn [dep]
                                        (when (some #{dep} path)
                                          (str "Circular dependency: "
                                               (clojure.string/join " → " (conj path dep)))))
                                      requires)]
              (or cycle-in-deps
                  (recur (rest remaining) (conj visited name) path)))))))))

;; ============================================================
;; Topological sort
;; ============================================================

(defn- topo-sort
  "Sort active library names by dependency order (Kahn's algorithm).
   Libraries with no/satisfied deps come first. Unresolvable libraries
   are appended at the end (they'll be skipped during loading)."
  [active-names]
  (let [active-set (set active-names)
        get-requires (fn [name]
                       ;; Only consider requires that are in the active set
                       (filterv active-set
                                (or (:requires (storage/get-library name)) [])))]
    (loop [remaining active-set
           sorted []
           loaded #{}]
      (if (empty? remaining)
        sorted
        (let [ready (filterv (fn [name]
                               (every? loaded (get-requires name)))
                             remaining)]
          (if (empty? ready)
            ;; No progress — circular deps or missing deps; append rest
            (into sorted remaining)
            (recur (reduce disj remaining ready)
                   (into sorted ready)
                   (into loaded ready))))))))

;; ============================================================
;; Library loading
;; ============================================================

(defn- eval-library
  "Evaluate a single library's source in a fresh SCI context.
   The context includes base-bindings, macros, and previously loaded namespaces.
   Returns {:bindings {symbol value}} on success, or {:error \"msg\"} on failure."
  [lib-name source base-bindings macro-defs accumulated-ns]
  (try
    (let [ctx (sci/init {:bindings base-bindings
                         :namespaces accumulated-ns})
          ;; Auto-require accumulated namespaces so lib can use ns/fn syntax
          _ (doseq [ns-sym (keys accumulated-ns)]
              (sci/eval-string (str "(require '" ns-sym ")") ctx))
          _ (sci/eval-string macro-defs ctx)
          ;; Extract public def names from source
          def-names (extract-def-names source)
          ;; Evaluate library source (defines symbols in context)
          _ (sci/eval-string source ctx)
          ;; Retrieve each defined value
          lib-bindings (into {}
                         (keep (fn [sym-name]
                                 (try
                                   [(symbol sym-name)
                                    (sci/eval-string (str sym-name) ctx)]
                                   (catch :default _
                                     nil))))
                         def-names)]
      {:bindings lib-bindings})
    (catch :default e
      (let [data (ex-data e)
            line (:line data)
            col  (:column data)
            msg  (.-message e)
            loc  (when line (str " (line " line (when col (str ":" col)) ")"))]
        (js/console.error (str "Error loading library '" lib-name "':") msg)
        {:error (str msg loc)}))))

;; Warnings from the last load — accessible from UI
(defonce load-warnings (atom []))

(defn load-active-libraries
  "Load all active libraries in dependency order (topological sort).
   Returns map of {lib-symbol {fn-symbol fn-value}} suitable for SCI :namespaces.
   Skips libraries with unmet dependencies or eval errors.
   Populates load-warnings atom with any issues."
  [base-bindings macro-defs]
  (reset! load-warnings [])
  (let [active-names (storage/get-active-libraries)]
    (if (empty? active-names)
      {}
      (let [sorted-names (topo-sort active-names)]
        (loop [remaining sorted-names
               loaded-set #{}
               accumulated-ns {}]
          (if (empty? remaining)
            accumulated-ns
            (let [lib-name (first remaining)
                  lib (storage/get-library lib-name)]
              (cond
                ;; Library not found in storage
                (nil? lib)
                (do (swap! load-warnings conj (str "Library '" lib-name "' not found"))
                    (recur (rest remaining) loaded-set accumulated-ns))

                ;; Missing dependencies (not in active set at all)
                (let [missing (remove loaded-set (:requires lib))]
                  (seq missing))
                (do (swap! load-warnings conj
                           (str "Library '" lib-name "' skipped: requires "
                                (vec (remove loaded-set (:requires lib)))))
                    (recur (rest remaining) loaded-set accumulated-ns))

                ;; All good — evaluate
                :else
                (let [result (eval-library lib-name (:source lib)
                                           base-bindings macro-defs accumulated-ns)]
                  (if-let [bindings (:bindings result)]
                    (recur (rest remaining)
                           (conj loaded-set lib-name)
                           (assoc accumulated-ns (symbol lib-name) bindings))
                    ;; Eval error — skip but continue
                    (do (swap! load-warnings conj
                               (str "Library '" lib-name "': " (:error result)))
                        (recur (rest remaining) loaded-set accumulated-ns))))))))))))
