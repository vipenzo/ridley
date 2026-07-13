(ns ridley.geometry.cut-candidates
  "Pure geometry for cut-candidate generation (dev-docs/brief-cut-candidates.md,
   Part 2). No WASM: the section-area PROFILE sampling (for necks) lives in
   ridley.manifold.core, which owns the live Manifold (accertamento B2). THIS
   namespace owns the tessellation-exact, WASM-free parts — vertex-projection
   offsets, coplanar-face STEP detection (|ΔA| read DIRECTLY from the mesh, exact
   and free — B1/B3, not by sampling which would smooth thin steps away), and
   local-minima NECK detection on an already-sampled profile. All pure,
   node-testable.

   Convention: the sweep is a translation along `heading`; a candidate's `:offset`
   is signed distance from `point` along `heading` ((v−point)·heading), so offset 0
   is the current plane. Poses come back in the standard {:position :heading :up}
   format (turtle-placeable, like symmetry-planes).")

;; ── tiny vector ops (kept local, like ridley.geometry.symmetry) ──
(defn- v- [[ax ay az] [bx by bz]] [(- ax bx) (- ay by) (- az bz)])
(defn- v+ [[ax ay az] [bx by bz]] [(+ ax bx) (+ ay by) (+ az bz)])
(defn- v* [[ax ay az] s] [(* ax s) (* ay s) (* az s)])
(defn- dot [[ax ay az] [bx by bz]] (+ (* ax bx) (* ay by) (* az bz)))
(defn- cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])
(defn- mag [v] (js/Math.sqrt (dot v v)))

(defn- tri-normal-area
  "[unit-normal area] of triangle a,b,c — [nil 0] for a degenerate (zero-area) tri."
  [a b c]
  (let [n (cross (v- b a) (v- c a))
        m (mag n)]
    (if (> m 1e-12) [(v* n (/ 1.0 m)) (* 0.5 m)] [nil 0.0])))

(defn vertex-offsets
  "Signed offset of each vertex along `heading` from `point`: (v−point)·heading."
  [vertices heading point]
  (mapv (fn [v] (dot (v- v point) heading)) vertices))

(defn offset-range
  "[min-offset max-offset] of the mesh's vertices along `heading` from `point` — the
   span the translation profile is sampled over. nil for an empty vertex list."
  [vertices heading point]
  (when (seq vertices)
    (let [os (vertex-offsets vertices heading point)]
      [(reduce min os) (reduce max os)])))

(defn offset->pose
  "The candidate plane pose at relative `offset` along `heading` from `point`,
   keeping the current heading/up: {:position :heading :up}. position moves only
   along heading, so the plane stays laterally where it was (centred on the piece
   when `point` is the bbox centre, as edit-mesh-split places it)."
  [offset heading up point]
  {:position (v+ point (v* heading offset)) :heading heading :up up})

(defn step-candidates
  "STEP candidates for a translation sweep along `heading` (brief Part 2). A face
   COPLANAR with the sweep plane (its normal ∥ heading) marks a discontinuity in the
   section area A(offset); the jump is exact and free from the mesh:
     ΔA(o) = −Σ area·sign(n·heading)   (a +heading-facing face leaves the section as
   the plane passes it → −area; a −heading-facing face enters → +area), so
   salience = |ΔA| = |Σ area·sign(n·heading)| over the faces coplanar at that offset.
   Faces are grouped by offset within `tol`; a face counts as coplanar when its
   normal is within `angle-tol` degrees of ±heading. Returns
   [{:offset o :salience |ΔA|} …] (unsorted), dropping sub-`min-salience` jumps."
  [{:keys [vertices faces]} heading point {:keys [tol angle-tol min-salience]
                                           :or {tol 0.1 angle-tol 1.0 min-salience 1e-6}}]
  (let [cos-min (js/Math.cos (* angle-tol (/ js/Math.PI 180.0)))
        ;; coplanar faces → {:o offset :sa signed-area}
        entries (->> faces
                     (keep (fn [[i j k]]
                             (let [a (nth vertices i) b (nth vertices j) c (nth vertices k)
                                   [n area] (tri-normal-area a b c)
                                   nh (when n (dot n heading))]
                               (when (and n (>= (js/Math.abs nh) cos-min))
                                 (let [ctr (v* (v+ (v+ a b) c) (/ 1.0 3.0))]
                                   {:o (dot (v- ctr point) heading)
                                    :sa (* area (if (pos? nh) 1.0 -1.0))})))))
                     (sort-by :o))
        ;; greedy cluster by offset gap ≤ tol (coplanar step faces share an exact
        ;; offset; tol only absorbs float noise)
        groups (reduce (fn [acc {:keys [o sa]}]
                         (if-let [g (peek acc)]
                           (if (<= (- o (:o0 g)) tol)
                             (conj (pop acc) (-> g (update :sum + sa)
                                                 (update :wsum + (* o (js/Math.abs sa)))
                                                 (update :asum + (js/Math.abs sa))))
                             (conj acc {:o0 o :sum sa :wsum (* o (js/Math.abs sa)) :asum (js/Math.abs sa)}))
                           [{:o0 o :sum sa :wsum (* o (js/Math.abs sa)) :asum (js/Math.abs sa)}]))
                       [] entries)]
    (->> groups
         (keep (fn [{:keys [o0 sum wsum asum]}]
                 (let [salience (js/Math.abs sum)]
                   (when (> salience min-salience)
                     {:offset (if (pos? asum) (/ wsum asum) o0)
                      :salience salience}))))
         vec)))

(defn profile-minima
  "NECK candidates (brief Part 2, colli): strict local minima of a sampled profile
   `samples` = [{:offset o :area a} …] ordered by offset, with plateau handling (a
   flat valley bottom of equal-area samples collapses to its middle sample). Salience
   = valley depth = min(left-neighbour, right-neighbour) − a. Drops dips shallower
   than `min-depth`. A step-down is NOT a neck (A stays low after, so no rise → no
   local min)."
  [samples min-depth]
  (let [v (vec samples)
        n (count v)
        area #(:area (nth v %))
        flat-eps 1e-6]
    (if (< n 3)
      []
      (loop [i 1, out []]
        (if (>= i (dec n))
          out
          (let [a (area i)
                ;; extend over a flat run of ~equal area
                j (loop [k i]
                    (if (and (< (inc k) n) (<= (js/Math.abs (- (area (inc k)) a)) flat-eps))
                      (recur (inc k)) k))
                left (area (dec i))
                ;; a neck needs the profile to RISE again on the right; a plateau that
                ;; runs to the profile edge is the mesh ending (a step), not a valley
                right (when (< (inc j) n) (area (inc j)))]
            (if (and (< a left) right (< a right))
              (let [depth (- (min left right) a)
                    mid (quot (+ i j) 2)]
                (recur (inc j)
                       (if (>= depth min-depth)
                         (conj out {:offset (:offset (nth v mid)) :salience depth})
                         out)))
              (recur (inc j) out))))))))
