(ns ridley.photogrammetry.linalg
  "Small dense linear algebra for the parametric-acquisition solver.

   Deliberately dependency-free and sized for the problem at hand: the
   parameter vector of a proxy fit is 3 + 6N (N = number of views), so
   N = 3 gives a 21x21 normal-equation system. Gaussian elimination and
   cyclic Jacobi are more than adequate at that size and keep the solver
   portable to any host (accertamento 8: cljs vs Rust).

   Matrices are vectors of row vectors.")

(defn zeros [n] (vec (repeat n 0.0)))

(defn mat-zeros [rows cols]
  (vec (repeat rows (zeros cols))))

(defn identity-mat [n]
  (mapv (fn [i] (mapv (fn [j] (if (= i j) 1.0 0.0)) (range n))) (range n)))

(defn transpose [m]
  (apply mapv vector m))

(defn mat*vec [m v]
  (mapv (fn [row] (reduce + 0.0 (map * row v))) m))

(defn mat*mat [a b]
  (let [bt (transpose b)]
    (mapv (fn [row] (mapv (fn [col] (reduce + 0.0 (map * row col))) bt)) a)))

(defn v-dot [a b] (reduce + 0.0 (map * a b)))

(defn v-norm [a] (Math/sqrt (v-dot a a)))

(defn v-scale [a s] (mapv #(* % s) a))

(defn v-sub [a b] (mapv - a b))

(defn v-add [a b] (mapv + a b))

(defn solve
  "Solve A x = b by Gaussian elimination with partial pivoting.
   Returns nil if the system is numerically singular — the caller decides
   what that means (for LM it means: raise the damping and retry)."
  [a b]
  (let [n (count b)
        ;; augmented matrix as a mutable JS array of JS arrays
        m (into-array (map (fn [row bv] (into-array (concat (map double row) [(double bv)])))
                           a b))]
    (loop [col 0]
      (if (>= col n)
        ;; back substitution
        (let [x (make-array n)]
          (doseq [i (range (dec n) -1 -1)]
            (let [row (aget m i)
                  s (reduce (fn [acc j] (+ acc (* (aget row j) (aget x j))))
                            0.0 (range (inc i) n))]
              (aset x i (/ (- (aget row n) s) (aget row i)))))
          (vec x))
        ;; pivot
        (let [pivot-row (reduce (fn [best i]
                                  (if (> (Math/abs (aget (aget m i) col))
                                         (Math/abs (aget (aget m best) col)))
                                    i best))
                                col (range col n))
              pval (aget (aget m pivot-row) col)]
          (if (< (Math/abs pval) 1e-14)
            nil ;; singular
            (do
              (when (not= pivot-row col)
                (let [tmp (aget m col)]
                  (aset m col (aget m pivot-row))
                  (aset m pivot-row tmp)))
              (let [prow (aget m col)]
                (doseq [i (range (inc col) n)]
                  (let [row (aget m i)
                        f (/ (aget row col) (aget prow col))]
                    (when (not= f 0.0)
                      (doseq [j (range col (inc n))]
                        (aset row j (- (aget row j) (* f (aget prow j)))))))))
              (recur (inc col)))))))))

(defn eigen-symmetric
  "Eigenvalues of a symmetric matrix by the cyclic Jacobi method.
   Returns eigenvalues sorted descending.

   Used for rank analysis of J^T J: the design doc wants the solver to know
   its own discovered degrees of freedom ('la guida non e euristica: e il
   rango del sistema'), and the eigenvalue spectrum is exactly that."
  ([a] (eigen-symmetric a 100))
  ([a max-sweeps]
   (let [n (count a)
         m (into-array (map #(into-array (map double %)) a))]
     (dotimes [_ max-sweeps]
       (let [off (reduce (fn [acc [i j]]
                           (if (not= i j)
                             (+ acc (* (aget (aget m i) j) (aget (aget m i) j)))
                             acc))
                         0.0
                         (for [i (range n) j (range n)] [i j]))]
         (when (> off 1e-22)
           (doseq [p (range (dec n))
                   q (range (inc p) n)]
             (let [apq (aget (aget m p) q)]
               (when (> (Math/abs apq) 1e-18)
                 (let [app (aget (aget m p) p)
                       aqq (aget (aget m q) q)
                       theta (/ (- aqq app) (* 2.0 apq))
                       t (let [sgn (if (>= theta 0.0) 1.0 -1.0)]
                           (/ sgn (+ (Math/abs theta)
                                     (Math/sqrt (+ 1.0 (* theta theta))))))
                       c (/ 1.0 (Math/sqrt (+ 1.0 (* t t))))
                       s (* t c)]
                   ;; rotate rows/cols p and q
                   (doseq [k (range n)]
                     (let [mkp (aget (aget m k) p)
                           mkq (aget (aget m k) q)]
                       (aset (aget m k) p (- (* c mkp) (* s mkq)))
                       (aset (aget m k) q (+ (* s mkp) (* c mkq)))))
                   (doseq [k (range n)]
                     (let [mpk (aget (aget m p) k)
                           mqk (aget (aget m q) k)]
                       (aset (aget m p) k (- (* c mpk) (* s mqk)))
                       (aset (aget m q) k (+ (* s mpk) (* c mqk))))))))))))
     (vec (sort > (map #(aget (aget m %) %) (range n)))))))
