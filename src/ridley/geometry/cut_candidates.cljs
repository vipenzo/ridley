(ns ridley.geometry.cut-candidates
  "Pure geometry for cut-candidate generation (dev-docs/brief-cut-candidates.md,
   Part 2). No WASM: the section-area PROFILE sampling (for necks) lives in
   ridley.manifold.core, which owns the live Manifold (accertamento B2). THIS
   namespace owns the tessellation-exact, WASM-free parts — vertex-projection
   offsets, coplanar-face STEP detection (|ΔA| read DIRECTLY from the mesh, exact
   and free — B1/B3, not by sampling which would smooth thin steps away), and
   local-minima NECK detection on an already-sampled profile. It also owns the
   :reflex generator (dev-docs/brief-cut-candidates-reflex.md) — candidates from a
   mesh's reflex (concave, dihedral > 180°) edges, clustered per candidate plane
   (refinement-invariant, B4) and ranked by concavity mass. All pure, node-testable.

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
(defn- normalize [v] (let [m (mag v)] (if (> m 1e-12) (v* v (/ 1.0 m)) nil)))

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

(defn step-pose
  "Flush-cut pose for a STEP candidate. The plane's heading is SNAPPED to the step
   face's `normal` (step-candidates' area-weighted mean normal), NOT the sweep
   `heading` — which step detection only requires to be within angle-tol (≤1°) of the
   face, so cutting along it would slice the flat step OBLIQUELY and shave a thin,
   irregular-contoured wafer (live-confirmed 2026-07-14; symmetry/reflex candidates
   already snap, only steps didn't). The plane passes through the faces' centroid
   `point` and is re-projected laterally through `ref` (the sweep point / bbox centre)
   so the quad stays centred on the piece; `up` carries through (its ≤angle-tol
   non-perpendicularity is imperceptible — up only spins the quad, never the cut).

   `bias` (brief-step-bias.md Part 1, default 0) shifts the flush position along
   `normal` by that SIGNED amount — a flush plane sits exactly at the fp32-noisy
   face, so a nm of tessellation jitter can throw a vertex to either side and shave
   an irregular sliver; the caller (cut-candidates) resolves the sign from the
   cluster's ΔA so the shift lands the plane in clean bulk material, not at 0 (see
   default-bias-epsilon for the magnitude)."
  ([normal point ref up] (step-pose normal point ref up 0.0))
  ([normal point ref up bias]
   (let [pos (v- ref (v* normal (- (dot ref normal) (dot point normal))))]
     {:position (v+ pos (v* normal bias)) :heading normal :up up})))

(defn bbox-diagonal
  "Diagonal length of `vertices`' axis-aligned bounding box — 0.0 for an empty mesh."
  [vertices]
  (if (empty? vertices)
    0.0
    (let [span (fn [idx] (let [cs (map #(nth % idx) vertices)]
                           (- (reduce max cs) (reduce min cs))))]
      (mag [(span 0) (span 1) (span 2)]))))

(defn default-bias-epsilon
  "Scale-aware default ε for the STEP bias (brief-step-bias.md Part 1):
   max(1e-3 mm, 1e-4 × bbox-diagonal) — comfortably above fp32 quantization noise
   (~nm at real part scale: ulp32 at ~2mm ≈ 0.12nm) and comfortably below any real
   feature, while staying scale-aware for large pieces."
  [vertices]
  (max 1e-3 (* 1e-4 (bbox-diagonal vertices))))

(defn component-thickness
  "Extension of `vertices` along `normal` — max minus min of (v·normal). The
   thickness signature heal-slivers (brief-step-bias.md Part 2) classifies mesh-
   split's post-split connected components by: a wafer sliver is thin in exactly
   this direction (the CUT plane's normal), unlike a plain volume threshold, which
   would also eat an intentionally thin-but-WIDE tab — that tab has a large
   extension in the other two directions and a large area, but is just as thin
   along the normal, so thickness alone tells them apart. 0.0 for an empty mesh."
  [vertices normal]
  (if-let [[mn mx] (offset-range vertices normal [0 0 0])]
    (- mx mn)
    0.0))

(defn default-sliver-threshold
  "Default heal-slivers thickness threshold (brief-step-bias.md Part 2): 2× the
   Part-1 bias epsilon — headroom above it, since a biased flush cut can still
   leave the robbed side up to ~ε thick before healing."
  [vertices]
  (* 2.0 (default-bias-epsilon vertices)))

(defn step-candidates
  "STEP candidates for a translation sweep along `heading` (brief Part 2). A face
   COPLANAR with the sweep plane (its normal ∥ heading) marks a discontinuity in the
   section area A(offset); the jump is exact and free from the mesh:
     ΔA(o) = −Σ area·sign(n·heading)   (a +heading-facing face leaves the section as
   the plane passes it → −area; a −heading-facing face enters → +area), so
   salience = |ΔA| = |Σ area·sign(n·heading)| over the faces coplanar at that offset.
   A face counts as coplanar when its normal is within `angle-tol` degrees of ±heading;
   coplanar faces are then clustered per PLANE — mean normal aligned within `angle-tol`
   AND plane offset within `tol` — so two distinct near-parallel faces at the same
   sweep offset stay separate candidates (each snaps to its own normal) instead of
   merging into one blended-normal group. Returns
   [{:offset o :salience |ΔA| :normal N :point P :sum ΔA'} …] (unsorted), dropping
   sub-`min-salience` jumps. `:sum` is the SIGNED cluster sum (salience = |:sum|,
   :sum = −ΔA per the formula above) — brief-step-bias.md Part 1 reads its sign to
   tell which side of the cluster's snapped `:normal` the bulk continues on: sum>0
   means `:normal` is the faces' own (unflipped) outward normal, so the solid — and
   the bias shift — goes the OTHER way, along −:normal (see cut-candidates' use)."
  [{:keys [vertices faces]} heading point {:keys [tol angle-tol min-salience]
                                           :or {tol 0.1 angle-tol 1.0 min-salience 1e-6}}]
  (let [cos-min (js/Math.cos (* angle-tol (/ js/Math.PI 180.0)))
        ;; coplanar faces → {:o along-heading-offset :pd plane-offset :sa signed-area
        ;;                    :n oriented-normal :ctr :area}.
        ;; :n is flipped into the +heading hemisphere (n·sign(n·heading)) so a cluster's
        ;; area-weighted Σ :n points coherently along +heading regardless of each face's
        ;; own winding — that mean is the flush-cut normal the candidate snaps to.
        ;; :pd = ctr·n is the face's own plane offset (along its normal); clustering on it
        ;; (not just :o) keeps two DISTINCT near-parallel faces apart.
        entries (->> faces
                     (keep (fn [[i j k]]
                             (let [a (nth vertices i) b (nth vertices j) c (nth vertices k)
                                   [n area] (tri-normal-area a b c)
                                   nh (when n (dot n heading))]
                               (when (and n (>= (js/Math.abs nh) cos-min))
                                 (let [ctr (v* (v+ (v+ a b) c) (/ 1.0 3.0))
                                       sgn (if (pos? nh) 1.0 -1.0)
                                       on (v* n sgn)]
                                   {:o (dot (v- ctr point) heading)
                                    :pd (dot ctr on)
                                    :sa (* area sgn)
                                    :n on
                                    :ctr ctr
                                    :area area}))))))
        ;; PLANE-clustering (reflex-style, cut-cand/cluster-planes): each coplanar face
        ;; joins the nearest cluster whose mean normal ALIGNS (dot > cos-min) AND whose
        ;; plane offset matches within `tol`, else opens a new cluster. Grouping by the
        ;; along-heading offset alone (the earlier version) merged two distinct
        ;; near-parallel faces sitting at the same sweep-offset into ONE blended-normal
        ;; group whose mean shaved a wafer off both on a tilted heading — STL-confirmed
        ;; 2026-07-15. O(n·k), n coplanar faces × k clusters (tens).
        clusters (reduce
                  (fn [cls {:keys [o pd sa n ctr area]}]
                    (let [best (reduce-kv
                                (fn [b i cl]
                                  (let [s (dot n (:N cl))]
                                    (if (and (> s cos-min)
                                             (< (js/Math.abs (- pd (:d cl))) tol)
                                             (or (nil? b) (> s (:s b))))
                                      {:i i :s s} b)))
                                nil cls)]
                      (if best
                        (update cls (:i best)
                                (fn [cl]
                                  (let [nsum (v+ (:nsum cl) (v* n area))
                                        wt (+ (:wt cl) area)
                                        dsum (+ (:dsum cl) (* pd area))]
                                    (-> cl
                                        (assoc :nsum nsum :wt wt :dsum dsum
                                               :N (or (normalize nsum) (:N cl))
                                               :d (/ dsum wt))
                                        (update :sum + sa)
                                        (update :wsum + (* o (js/Math.abs sa)))
                                        (update :asum + (js/Math.abs sa))
                                        (update :csum v+ (v* ctr area))))))
                        (conj cls {:sum sa :wsum (* o (js/Math.abs sa)) :asum (js/Math.abs sa)
                                   :nsum (v* n area) :csum (v* ctr area) :wt area
                                   :dsum (* pd area) :d pd :N n}))))
                  [] entries)]
    (->> clusters
         (keep (fn [{:keys [sum wsum asum nsum csum wt]}]
                 (let [salience (js/Math.abs sum)]
                   (when (> salience min-salience)
                     ;; :normal = the cluster's area-weighted mean normal (the flush-cut
                     ;; orientation); :point = its area-weighted centroid (a point ON that
                     ;; plane). The caller (cut-candidates) builds the pose from these so
                     ;; the plane is PARALLEL to the flat step, not merely offset along
                     ;; the ≤1°-misaligned sweep heading (which shaved a wafer). :offset
                     ;; stays the sweep-axis position for the strip / next-event ordering.
                     {:offset (/ wsum asum)
                      :salience salience
                      :normal (normalize nsum)
                      :point (v* csum (/ 1.0 wt))
                      :sum sum}))))
         vec)))

;; ── rotation (brief Part 2, rotazione) ──────────────────────
;; A pencil of planes about an axis (:up or :right) through `point`, the normal
;; starting at `heading`. `:offset` here carries the rotation ANGLE (radians) so the
;; profile/neck machinery is shared with translation.

(defn rotate-about
  "Rodrigues rotation of `v` about unit axis `a` by `theta` radians."
  [v a theta]
  (let [c (js/Math.cos theta) s (js/Math.sin theta) d (dot a v)]
    (v+ (v+ (v* v c) (v* (cross a v) s)) (v* a (* d (- 1.0 c))))))

(defn angle->pose
  "The candidate plane pose at rotation `theta` (rad) of the current heading/up about
   unit axis `axis` through `point`: heading and up both rotate, position fixed."
  [theta heading up point axis]
  {:position point :heading (rotate-about heading axis theta) :up (rotate-about up axis theta)})

(defn- normal-at
  "The plane normal at rotation angle `theta` in the pencil {heading, perp}."
  [heading perp theta]
  (let [c (js/Math.cos theta) s (js/Math.sin theta)]
    [(+ (* (heading 0) c) (* (perp 0) s))
     (+ (* (heading 1) c) (* (perp 1) s))
     (+ (* (heading 2) c) (* (perp 2) s))]))

(defn rotation-step-candidates
  "STEP candidates for a rotation of the plane about unit axis `axis` through `point`
   (brief Part 2, rotazione). A face is coplanar with the pencil's plane at some angle
   ONLY if (a) its normal lies in the pencil plane span{heading, axis×heading} — n·axis
   ≈ 0 within `angle-tol` deg — AND (b) it actually lies ON a plane through `point`,
   i.e. |(centroid−point)·n| ≈ 0 within `pos-tol` mm (a face parallel to the plane but
   offset from the axis never becomes the cutting plane — which is why a centred box
   correctly yields no rotation steps). Coplanar angle θ = atan2(n·(axis×heading),
   n·heading), reduced to (−π/2, π/2]. Salience = |ΔA| = |Σ area·sign(n·normal(θ))|.
   Returns [{:offset θ :salience s}] (θ radians)."
  [{:keys [vertices faces]} heading point axis {:keys [angle-tol pos-tol min-salience]
                                                :or {angle-tol 1.0 pos-tol 0.1 min-salience 1e-6}}]
  (let [perp (cross axis heading)
        sin-a (js/Math.sin (* angle-tol (/ js/Math.PI 180.0)))
        tol-rad (* angle-tol (/ js/Math.PI 180.0))
        half-pi (/ js/Math.PI 2.0)
        norm-pi (fn [a] (cond (> a half-pi) (- a js/Math.PI)
                              (<= a (- half-pi)) (+ a js/Math.PI)
                              :else a))
        entries (->> faces
                     (keep (fn [[i j k]]
                             (let [va (nth vertices i) vb (nth vertices j) vc (nth vertices k)
                                   [n area] (tri-normal-area va vb vc)
                                   ctr (when n (v* (v+ (v+ va vb) vc) (/ 1.0 3.0)))]
                               (when (and n
                                          (< (js/Math.abs (dot n axis)) sin-a)
                                          (< (js/Math.abs (dot (v- ctr point) n)) pos-tol))
                                 (let [theta (norm-pi (js/Math.atan2 (dot n perp) (dot n heading)))
                                       nt (normal-at heading perp theta)]
                                   {:o theta :sa (* area (if (pos? (dot n nt)) 1.0 -1.0))})))))
                     (sort-by :o))
        groups (reduce (fn [acc {:keys [o sa]}]
                         (if-let [g (peek acc)]
                           (if (<= (- o (:o0 g)) tol-rad)
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
                     {:offset (if (pos? asum) (/ wsum asum) o0) :salience salience}))))
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

;; ── reflex-edge candidates (dev-docs/brief-cut-candidates-reflex.md) ─────────
;; The third generator: propose cuts where the CONCAVITY lives. A mesh's reflex
;; edges (dihedral > 180°) are thousands on tessellated fillets, but clustering
;; their two face-planes per candidate plane is refinement-invariant (B4) and
;; collapses them to tens; ranking by concavity mass (Σ length × angle-excess)
;; floats the cuts that resolve real concavity and sinks fillet-crumb clusters.
;; Unlike step/neck this reads NO turtle pose — the candidates are complete poses
;; sparse in space (orientation + position together), so the interaction is
;; propose-and-cycle by salience, not next-event along a DOF.

(defn- deg->rad [d] (* d (/ js/Math.PI 180.0)))

(defn- face-normal [vertices [i j k]]
  (first (tri-normal-area (nth vertices i) (nth vertices j) (nth vertices k))))

(defn- edge-key [a b] (if (< a b) [a b] [b a]))

(defn- build-edge-map
  "edge-key {min max} → vector of {:n face-normal :opp opposite-vertex-index} for the
   faces on that edge. A manifold edge has exactly two; degenerate (nil-normal) faces
   are skipped so their edges may end up with fewer."
  [vertices faces]
  (reduce (fn [m [i j k :as f]]
            (if-let [n (face-normal vertices f)]
              (-> m
                  (update (edge-key i j) (fnil conj []) {:n n :opp k})
                  (update (edge-key j k) (fnil conj []) {:n n :opp i})
                  (update (edge-key k i) (fnil conj []) {:n n :opp j}))
              m))
          {} faces))

(defn- reflex-edge-candidates
  "The two plane-candidates of edge {va,vb} shared by faces `fa`,`fb` — the planes of
   the two adjacent faces — IF the edge is reflex (concave) by more than the reflex
   tolerance (`reflex-cos` = cos(reflex-tol)). Concavity test: `fb`'s far vertex pokes
   ABOVE `fa`'s outward plane (sign is winding-robust; a convex edge has it below).
   The excess angle (θ−π, the concavity's sharpness) = acos(nA·nB) — near-flat
   tessellation seams (excess < reflex-tol) are dropped. Each candidate carries the
   edge weight w = length × excess and the edge midpoint. nil for a convex/flat edge."
  [vertices va vb fa fb reflex-cos]
  (let [pa (nth vertices va)
        pb (nth vertices vb)
        na (:n fa) nb (:n fb)
        rb (nth vertices (:opp fb))
        concave? (pos? (dot (v- rb pa) na))
        cos-ab (max -1.0 (min 1.0 (dot na nb)))]
    (when (and concave? (< cos-ab reflex-cos))
      (let [excess (js/Math.acos cos-ab)
            w (* (mag (v- pb pa)) excess)
            mid (v* (v+ pa pb) 0.5)]
        [{:n na :d (dot na pa) :w w :mid mid}
         {:n nb :d (dot nb pa) :w w :mid mid}]))))

(defn- accumulate-cluster
  "Fold candidate `c` into a cluster's running accumulators (Σw, Σw·n, Σw·d, Σw·mid),
   refreshing the cached representative plane (unit mean-normal, mean-offset)."
  [cl {:keys [n d w mid]}]
  (let [sw (+ (:sw cl) w)
        swn (v+ (:swn cl) (v* n w))
        swd (+ (:swd cl) (* d w))
        swm (v+ (:swm cl) (v* mid w))]
    {:sw sw :swn swn :swd swd :swm swm
     :n (or (normalize swn) (:n cl)) :d (/ swd sw)}))

(defn- cluster-planes
  "Greedy plane-clustering of candidate planes (B4, refinement-invariant): each
   candidate joins the existing cluster whose representative is nearest in normal
   (dot > `cluster-cos`) AND within `offset-tol` in signed offset, else starts a new
   one. O(n·k), n candidates × k clusters (k is tens even when n is thousands, which is
   the whole point). Order-stable given deterministic face iteration."
  [cands cluster-cos offset-tol]
  (reduce
   (fn [clusters c]
     (let [best (reduce-kv
                 (fn [b i cl]
                   (let [s (dot (:n c) (:n cl))]
                     (if (and (> s cluster-cos)
                              (< (js/Math.abs (- (:d c) (:d cl))) offset-tol)
                              (or (nil? b) (> s (:s b))))
                       {:i i :s s} b)))
                 nil clusters)]
       (if best
         (update clusters (:i best) accumulate-cluster c)
         (conj clusters {:sw (:w c) :swn (v* (:n c) (:w c)) :swd (* (:d c) (:w c))
                         :swm (v* (:mid c) (:w c)) :n (:n c) :d (:d c)}))))
   [] cands))

(defn- perp
  "A deterministic unit vector ⊥ n — the free `up` of a reflex candidate pose (the
   plane's orientation is fixed by its normal; up only spins the quad, brief 'up libero')."
  [n]
  (let [t (if (> (js/Math.abs (nth n 2)) 0.9) [1.0 0.0 0.0] [0.0 0.0 1.0])]
    (or (normalize (cross n t)) [0.0 0.0 1.0])))

(defn- cluster->candidate
  "One cluster → a cut candidate. heading = the cluster's weighted mean outgoing face
   normal; position = the weight-averaged edge midpoint projected onto the cluster
   plane (lands the teleport ON the plane, next to the concavity, not at an arbitrary
   far point); up free. salience = Σ length × angle-excess = the cluster's concavity mass."
  [{:keys [sw swn swd swm]}]
  (let [n (normalize swn)
        d (/ swd sw)
        ctr (v* swm (/ 1.0 sw))
        pos (v- ctr (v* n (- (dot ctr n) d)))]
    {:pose {:position pos :heading n :up (perp n)}
     :kind :reflex
     :salience sw}))

(defn reflex-candidates
  "Cut candidates from `mesh`'s reflex (concave) edges (brief-cut-candidates-reflex.md,
   Part 1). PURE and pose-free: reads only the mesh (:vertices/:faces), never a turtle
   pose. Each reflex edge (dihedral > 180° beyond `:reflex-tol`) offers two candidates —
   its two adjacent face-planes; candidates are clustered per plane (normal within
   `:cluster-angle-tol` deg, offset within `:tolerance` mm — refinement-invariant, B4)
   and ranked by concavity mass. opts:
     :reflex-tol        1.0  min reflex angle-excess (deg) — discards near-flat seams
     :cluster-angle-tol 5.0  normals within this (deg) share a cluster
     :tolerance         0.1  cluster offset tolerance (mm)
   Returns [{:pose {:position :heading :up} :kind :reflex :salience Σlen·excess} …]
   sorted by salience DESCENDING (the cuts that resolve the most concavity first, the
   fillet-crumb clusters in the tail). A convex mesh has no reflex edges → []."
  ([mesh] (reflex-candidates mesh {}))
  ([{:keys [vertices faces]} {:keys [reflex-tol cluster-angle-tol tolerance]
                              :or {reflex-tol 1.0 cluster-angle-tol 5.0 tolerance 0.1}}]
   (let [reflex-cos (js/Math.cos (deg->rad reflex-tol))
         cluster-cos (js/Math.cos (deg->rad cluster-angle-tol))
         cands (->> (build-edge-map vertices faces)
                    (mapcat (fn [[[va vb] fs]]
                              (when (= 2 (count fs))
                                (reflex-edge-candidates vertices va vb (first fs) (second fs) reflex-cos)))))]
     (->> (cluster-planes cands cluster-cos tolerance)
          (map cluster->candidate)
          (sort-by :salience >)
          vec))))
