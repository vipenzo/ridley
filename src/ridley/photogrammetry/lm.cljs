(ns ridley.photogrammetry.lm
  "Levenberg-Marquardt least squares with a numerical Jacobian.

   Sized for the parametric-acquisition problem: tens of parameters, low
   hundreds of residuals. A numerical Jacobian costs 2n residual evaluations
   per iteration (central differences), which at n = 21 is trivial and buys
   immunity from hand-derived-derivative bugs — the classic time sink in a
   bundle adjustment. If the solver ever grows to hundreds of parameters,
   analytic derivatives and a sparse normal-equation solve are the upgrade
   path, not a rewrite."
  (:require [ridley.photogrammetry.linalg :as la]))

(defn cost
  "Half the sum of squared residuals (the chi-square/2 convention)."
  [residuals]
  (* 0.5 (reduce + 0.0 (map #(* % %) residuals))))

(defn jacobian
  "Numerical Jacobian of residual-fn at p, by central differences.
   Returns a matrix of m rows (residuals) x n cols (parameters)."
  [residual-fn p]
  (let [n (count p)
        cols (mapv (fn [i]
                     (let [h (max 1e-7 (* 1e-7 (Math/abs (nth p i))))
                           rf (residual-fn (assoc (vec p) i (+ (nth p i) h)))
                           rb (residual-fn (assoc (vec p) i (- (nth p i) h)))]
                       (mapv (fn [a b] (/ (- a b) (* 2.0 h))) rf rb)))
                   (range n))]
    (la/transpose cols)))

(defn- augment
  "Marquardt damping: scale the added diagonal by the existing diagonal, so
   the step is invariant to the wildly different units of our parameters
   (millimetres for box dimensions, radians for rotations)."
  [jtj lam]
  (vec (map-indexed
        (fn [i row] (assoc row i (+ (nth row i) (* lam (max 1e-12 (nth row i))))))
        jtj)))

(defn- try-step
  "Search for a damping value that produces a cost decrease.
   Returns {:ok? true :p :r :cost :lambda} or {:ok? false}."
  [residual-fn jtj jtr p c lambda]
  (loop [lam lambda
         tries 0]
    (if (> tries 30)
      {:ok? false}
      (let [delta (la/solve (augment jtj lam) (mapv - jtr))]
        (if (nil? delta)
          (recur (* lam 10.0) (inc tries))
          (let [p-new (mapv + p delta)
                r-new (residual-fn p-new)
                c-new (cost r-new)]
            (if (< c-new c)
              {:ok? true :p p-new :r r-new :cost c-new
               :lambda (max 1e-12 (/ lam 10.0)) :step (la/v-norm delta)}
              (recur (* lam 10.0) (inc tries)))))))))

(defn solve
  "Minimise 0.5*sum(residual-fn(p)^2) over p, starting from p0.

   residual-fn must return a vector of ALREADY-WEIGHTED residuals (each
   divided by its own sigma), so the sum is a proper chi-square and
   heterogeneous observations — pixels and millimetres — are commensurable.
   It must also return a vector of CONSTANT length: a residual that vanishes
   (a point falling behind a camera) has to be reported as a large penalty,
   not dropped, or the cost surface becomes discontinuous and LM stalls on
   an artefact rather than on the data.

   Returns {:params :cost :iterations :converged? :residuals}."
  ([residual-fn p0] (solve residual-fn p0 {}))
  ([residual-fn p0 {:keys [max-iterations tol-cost tol-step lambda0]
                    :or {max-iterations 200 tol-cost 1e-12 tol-step 1e-12
                         lambda0 1e-3}}]
   (loop [p (vec p0)
          r (residual-fn (vec p0))
          lambda lambda0
          iter 0]
     (let [c (cost r)]
       (if (>= iter max-iterations)
         {:params p :cost c :iterations iter :converged? false :residuals r}
         (let [j (jacobian residual-fn p)
               jt (la/transpose j)
               jtj (la/mat*mat jt j)
               jtr (la/mat*vec jt r)
               step (try-step residual-fn jtj jtr p c lambda)]
           (cond
             ;; no downhill direction found: we are at a minimum (or stuck)
             (not (:ok? step))
             {:params p :cost c :iterations iter :converged? true :residuals r}

             (or (< (- c (:cost step)) (* tol-cost (max 1.0 c)))
                 (< (:step step) tol-step))
             {:params (:p step) :cost (:cost step) :iterations (inc iter)
              :converged? true :residuals (:r step)}

             :else
             (recur (:p step) (:r step) (:lambda step) (inc iter)))))))))

(defn covariance-spectrum
  "Eigenvalues of J^T J at p, largest first.

   This is the object the design doc calls for when it says the guidance is
   'il rango del sistema' and not a heuristic: a near-zero eigenvalue is a
   direction in parameter space the photographs do not constrain. The ratio
   of largest to smallest is the condition number of the fit."
  [residual-fn p]
  (let [j (jacobian residual-fn p)
        jt (la/transpose j)]
    (la/eigen-symmetric (la/mat*mat jt j))))
