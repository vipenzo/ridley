(ns ridley.jvm.tweak
  "Server-side tweak support: AST parsing for numeric literals,
   value substitution, and session management.

   The tweak macro in user scripts signals a tweak session. The eval
   engine detects it and returns literal metadata to the frontend,
   which creates slider UI. Slider changes trigger re-eval with
   substituted values.")

;; ============================================================
;; AST Walking — find numeric literals
;; ============================================================

(defn find-numeric-literals
  "Walk a Clojure form depth-first, collecting numeric literals.
   Returns vector of {:index :value :parent-fn :arg-idx :label}."
  [form]
  (let [results (volatile! [])
        idx (volatile! 0)]
    (letfn [(walk [form parent-fn arg-idx-in-parent]
              (cond
                (number? form)
                (do (vswap! results conj {:index @idx
                                          :value form
                                          :parent-fn parent-fn
                                          :arg-idx arg-idx-in-parent})
                    (vswap! idx inc))

                (and (list? form) (seq form))
                (let [fn-sym (when (symbol? (first form)) (first form))
                      args (if fn-sym (rest form) form)]
                  (dorun (map-indexed
                          (fn [i child]
                            (walk child (or fn-sym parent-fn) i))
                          args)))

                (sequential? form)
                (dorun (map-indexed
                        (fn [i child]
                          (walk child parent-fn i))
                        form))

                (map? form)
                (doseq [[k v] form]
                  (walk k parent-fn nil)
                  (walk v (when (keyword? k) k) nil))

                :else nil))]
      (walk form nil nil)
      @results)))

;; ============================================================
;; Label generation
;; ============================================================

(defn- generate-label
  "Generate a display label for a numeric literal."
  [lit all-lits]
  (let [{:keys [value parent-fn arg-idx]} lit
        siblings (filter #(= (:parent-fn %) parent-fn) all-lits)
        fn-name (when parent-fn (name parent-fn))]
    (if (and fn-name (= 1 (count siblings)))
      (str fn-name ": " value)
      (if fn-name
        (str fn-name "[" arg-idx "]: " value)
        (str value)))))

(defn extract-tweak-info
  "Parse an expression form and return tweak session info.
   Returns {:form <string> :literals [{:index :value :label :parent}...]}"
  [form]
  (let [lits (find-numeric-literals form)
        labeled (mapv (fn [lit]
                        (assoc lit :label (generate-label lit lits)
                               :parent (when (:parent-fn lit)
                                         (name (:parent-fn lit)))))
                      lits)]
    {:form (pr-str form)
     :literals labeled}))

;; ============================================================
;; Value substitution
;; ============================================================

(defn substitute-values
  "Walk a form and replace numeric literals at specified indices with new values.
   values-map: {index new-value}."
  [form values-map]
  (let [idx (volatile! 0)]
    (letfn [(walk [form]
              (cond
                (number? form)
                (let [i @idx
                      _ (vswap! idx inc)]
                  (get values-map i form))

                (and (list? form) (seq form))
                (apply list (map walk form))

                (vector? form)
                (mapv walk form)

                (map? form)
                (into {} (map (fn [[k v]] [(walk k) (walk v)]) form))

                (set? form)
                (set (map walk form))

                :else form))]
      (walk form))))

;; ============================================================
;; Tweak session atom (persists between eval calls)
;; ============================================================

(defonce tweak-session
  (atom nil))
;; When active: {:form <original form>
;;               :script <editor script without tweak line>
;;               :literals [...]}
