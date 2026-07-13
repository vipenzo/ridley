(ns ridley.geometry.symmetry
  "Pure geometry for symmetry-plane proposal (dev-docs/brief-mesh-symmetry.md,
   Part 3). Area-weighted PCA on the face centroids + candidate mirror planes,
   with a degenerate-eigenvalue fallback to the bounding-box axes. No Manifold,
   no DOM — unit-testable in node. The Manifold verification of each candidate
   (the B6 cascade) lives in ridley.manifold.core/symmetry-planes, which consumes
   candidate-planes from here.

   Why AREA-weighted (accertamento B7, a falsified hypothesis turned permanent
   test): PCA on the raw vertices is defeated by uneven tessellation — a denser
   half drags the centroid off the true symmetry plane (measured centroid.x 17.69
   where it should be 0). Weighting each face centroid by its triangle area is
   tessellation-invariant, so it is mandatory, not a refinement.")

;; ── small vector helpers (self-contained) ───────────────────

(defn- v- [[ax ay az] [bx by bz]] [(- ax bx) (- ay by) (- az bz)])
(defn- v+ [[ax ay az] [bx by bz]] [(+ ax bx) (+ ay by) (+ az bz)])
(defn- v* [[ax ay az] s] [(* ax s) (* ay s) (* az s)])
(defn- dot [[ax ay az] [bx by bz]] (+ (* ax bx) (* ay by) (* az bz)))
(defn- cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])
(defn- mag [v] (js/Math.sqrt (dot v v)))
(defn- normalize [v]
  (let [m (mag v)] (if (< m 1e-12) [0.0 0.0 0.0] (v* v (/ 1.0 m)))))

(defn- tri-area [a b c]
  (* 0.5 (mag (cross (v- b a) (v- c a)))))

;; ── area-weighted moments (weighted centroid + covariance) ──

(defn- add-outer!
  "Accumulate scale·(u ⊗ u) into the 6-element symmetric-matrix accumulator
   [xx xy xz yy yz zz] (u is used for both factors, so it stays symmetric)."
  [^js m [x y z] scale]
  (aset m 0 (+ (aget m 0) (* scale x x)))
  (aset m 1 (+ (aget m 1) (* scale x y)))
  (aset m 2 (+ (aget m 2) (* scale x z)))
  (aset m 3 (+ (aget m 3) (* scale y y)))
  (aset m 4 (+ (aget m 4) (* scale y z)))
  (aset m 5 (+ (aget m 5) (* scale z z)))
  m)

(defn area-weighted-moments
  "Area-weighted first and second moments of a mesh's SURFACE — returns
   {:centroid [x y z] :cov [[…]…] :total-area a}, or nil for a degenerate
   (zero-area) mesh.

   `cov` is the 3×3 symmetric surface covariance about the area centroid, using
   the EXACT per-triangle second-moment integral
     ∫_T x xᵀ dA = (A/12)[(a+b+c)(a+b+c)ᵀ + a aᵀ + b bᵀ + c cᵀ]
   (verified: ∫x² over the unit right triangle = 1/12). The exact integral is
   what makes both the centroid AND the principal axes triangulation-invariant —
   the cheaper triangle-CENTROID approximation leaks each face's triangulation
   diagonal into the off-diagonal covariance and tilts the axes (a box would read
   as spuriously degenerate)."
  [{:keys [vertices faces]}]
  (let [tris (keep (fn [[i j k]]
                     (let [a (vertices i) b (vertices j) c (vertices k)
                           area (tri-area a b c)]
                       (when (> area 1e-12)
                         {:a a :b b :c c :area area
                          :centroid (v* (v+ (v+ a b) c) (/ 1.0 3.0))})))
                   faces)
        total (reduce + 0.0 (map :area tris))]
    (when (> total 1e-12)
      (let [[cx cy cz :as centroid]
            (v* (reduce (fn [acc {:keys [centroid area]}] (v+ acc (v* centroid area)))
                        [0.0 0.0 0.0] tris)
                (/ 1.0 total))
            ;; M = Σ ∫_T x xᵀ dA  (exact surface second moment about the origin)
            M (reduce (fn [^js m {:keys [a b c area]}]
                        (let [w (/ area 12.0)]
                          (-> m (add-outer! (v+ (v+ a b) c) w)
                              (add-outer! a w) (add-outer! b w) (add-outer! c w))))
                      (js/Array. 0.0 0.0 0.0 0.0 0.0 0.0) tris)
            s (/ 1.0 total)
            ;; cov = M/total − centroid ⊗ centroid
            cxx (- (* s (aget M 0)) (* cx cx))
            cxy (- (* s (aget M 1)) (* cx cy))
            cxz (- (* s (aget M 2)) (* cx cz))
            cyy (- (* s (aget M 3)) (* cy cy))
            cyz (- (* s (aget M 4)) (* cy cz))
            czz (- (* s (aget M 5)) (* cz cz))]
        {:centroid centroid
         :total-area total
         :cov [[cxx cxy cxz] [cxy cyy cyz] [cxz cyz czz]]}))))

;; ── Jacobi eigendecomposition of a symmetric 3×3 ────────────

(defn jacobi-eigen-3x3
  "Eigen-decompose a symmetric 3×3 matrix `M` (vector of 3 rows) by cyclic Jacobi
   rotations. Returns {:values [λ0 λ1 λ2] :vectors [v0 v1 v2]} with columns as the
   eigenvectors, sorted by DESCENDING eigenvalue (v0 = largest-variance principal
   axis). Pure ClojureScript."
  [M]
  ;; A and V are flat 3×3 row-major JS arrays; A stays symmetric under Jᵀ A J.
  (let [A (js/Array. (double (get-in M [0 0])) (double (get-in M [0 1])) (double (get-in M [0 2]))
                     (double (get-in M [1 0])) (double (get-in M [1 1])) (double (get-in M [1 2]))
                     (double (get-in M [2 0])) (double (get-in M [2 1])) (double (get-in M [2 2])))
        V (js/Array. 1.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 1.0)
        at  (fn [^js a r c] (aget a (+ (* 3 r) c)))
        set (fn [^js a r c x] (aset a (+ (* 3 r) c) x))]
    (dotimes [_ 24]
      (doseq [[p q] [[0 1] [0 2] [1 2]]]
        (let [apq (at A p q)]
          (when (> (js/Math.abs apq) 1e-15)
            (let [app (at A p p) aqq (at A q q)
                  ;; θ that zeroes A_pq under JᵀAJ with J = [[c s][-s c]] (the
                  ;; convention the column/row updates below implement) is
                  ;; ½·atan2(2·a_pq, a_qq−a_pp). The second arg is a_qq−a_pp, NOT
                  ;; a_pp−a_qq: with the sign flipped a near-diagonal matrix (tiny
                  ;; a_pq, large a_pp−a_qq) rotated by ~90° — swapping axes and
                  ;; NEGATING a_pq instead of zeroing it — so the sweep never
                  ;; converged and the "principal axes" came out as tilted garbage
                  ;; (symmetry-planes then found nothing on axis-aligned CAD parts).
                  theta (* 0.5 (js/Math.atan2 (* 2.0 apq) (- aqq app)))
                  c (js/Math.cos theta) s (js/Math.sin theta)]
              ;; A ← A·J (rotate columns p,q)
              (dotimes [i 3]
                (let [aip (at A i p) aiq (at A i q)]
                  (set A i p (- (* c aip) (* s aiq)))
                  (set A i q (+ (* s aip) (* c aiq)))))
              ;; A ← Jᵀ·A (rotate rows p,q) — now A = Jᵀ A J
              (dotimes [j 3]
                (let [apj (at A p j) aqj (at A q j)]
                  (set A p j (- (* c apj) (* s aqj)))
                  (set A q j (+ (* s apj) (* c aqj)))))
              ;; V ← V·J (accumulate eigenvectors as columns)
              (dotimes [i 3]
                (let [vip (at V i p) viq (at V i q)]
                  (set V i p (- (* c vip) (* s viq)))
                  (set V i q (+ (* s vip) (* c viq))))))))))
    (let [vals [(at A 0 0) (at A 1 1) (at A 2 2)]
          col (fn [k] [(at V 0 k) (at V 1 k) (at V 2 k)])
          order (vec (sort-by #(- (nth vals %)) [0 1 2]))]
      {:values (mapv vals order)
       :vectors (mapv #(normalize (col %)) order)})))

;; ── principal frame + candidate planes ──────────────────────

(defn principal-frame
  "Area-weighted principal frame of a mesh: {:centroid [x y z] :axes [a0 a1 a2]
   :values [λ0 λ1 λ2]} (axes are unit vectors, descending eigenvalue), or nil for
   a degenerate mesh."
  [mesh]
  (when-let [{:keys [centroid cov]} (area-weighted-moments mesh)]
    (let [{:keys [values vectors]} (jacobi-eigen-3x3 cov)]
      {:centroid centroid :axes vectors :values values})))

(defn- perp
  "Any unit vector perpendicular to `n` — a free `up` for a plane pose (the
   symmetry plane is defined by point+normal alone; up only orients the quad)."
  [n]
  (let [t (if (> (js/Math.abs (dot n [0 0 1])) 0.9) [1 0 0] [0 0 1])]
    (normalize (cross n t))))

(defn- plane-pose
  "A mark/goto-style pose {:position :heading :up} for the mirror plane through
   `point` with normal `normal` — heading = the plane normal (what mesh-split
   reads), up = a free perpendicular."
  [point normal]
  (let [h (normalize normal)]
    {:position (vec point) :heading h :up (perp h)}))

(defn candidate-planes
  "A handful of candidate mirror-plane poses for `mesh` (Part 3), through the
   area-weighted centroid, for the caller (B6) to verify. TWO families, bbox first:
     • the three bounding-box axes (X/Y/Z) — real CAD parts are axis-aligned and
       only APPROXIMATELY symmetric, so the exact axis plane verifies where a PCA
       axis tilted even ~0.2° by minor asymmetric detail spuriously fails (its
       symmetric-difference ratio can double). Always included.
     • the three area-weighted principal axes — catches genuinely off-axis symmetry
       a bbox axis would miss.
   bbox is listed first so the dedup (drops a normal within |dot|>0.999 of one
   already kept) prefers the EXACT axis over a near-parallel tilted PCA axis. Poses
   only. Returns [] for a degenerate (zero-area) mesh."
  [mesh]
  (if-let [{:keys [centroid axes]} (principal-frame mesh)]
    (let [bbox (map #(plane-pose centroid %) [[1 0 0] [0 1 0] [0 0 1]])
          pca  (map #(plane-pose centroid %) axes)
          ;; dedup near-parallel normals (|dot| ~ 1), keeping the first (bbox) seen
          dedup (fn [poses]
                  (reduce (fn [acc p]
                            (if (some #(> (js/Math.abs (dot (:heading %) (:heading p))) 0.999) acc)
                              acc (conj acc p)))
                          [] poses))]
      (dedup (concat bbox pca)))
    []))
