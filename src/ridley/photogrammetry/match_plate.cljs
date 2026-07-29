(ns ridley.photogrammetry.match-plate
  "Identity-free registration of a plate (acquisition roadmap fetta B): the user
   clicks 4+ ARBITRARY crown discs — without declaring which mark id each one is —
   and this recovers the assignment (click → mark id) by an exhaustive search
   whose judge is the photo itself. It removes the last friction of the armed
   flow (counting marks from the zero-index) and works on the plate that already
   exists (no reprint, no numbers to read).

   Why exhaustive, not an angle estimate: the crown's angular spacing is warped by
   perspective, so estimating each click's ring angle to look up its id is fragile
   (the very failure the plate was built to avoid). Instead:

   1. Put the clicks in cyclic image order (angle about their centroid). A non-
      mirror camera preserves cyclic order, so the clicks map to 4 marks that are
      consecutive-in-cyclic-order around the crown — but WHICH 4, and at what
      rotation/handedness, is unknown.
   2. Enumerate every cyclic-order-preserving assignment: each k-subset of the m
      marks (in cyclic order) × k rotations × 2 handednesses (the image angle sense
      may be flipped by v-down pixel coordinates). For k=4, m=12 that is
      C(12,4)·4·2 = 3960 candidates — a few tens of ms.
   3. Score each by solving the plane homography from its 4 correspondences
      (pnp/estimate-homography — the marks are coplanar), reprojecting ALL m marks
      + the zero-index, and counting how many land on a real dark disc (a cheap
      `disc-at?` judge sampling the photo). The correct subset+rotation reprojects
      the whole crown onto discs.

   THE 12-FOLD SYMMETRY. A regular crown of m marks is symmetric under a 30°
   turn: an assignment rotated by one mark yields a pose rotated by 30°, under
   which all m reprojected marks STILL land on discs — so the crown count alone
   ties across all m rotations. Only the asymmetric ZERO-INDEX (a 13th disc
   radially inside one crown mark, `zero-obj`) breaks it: it reprojects onto its
   disc under the correct orientation only, so its hit is worth a large bonus and
   decides the rotation. (The twin planar-pose ambiguity does NOT matter here:
   reprojecting coplanar points uses the homography H, which both twins share, so
   scoring is twin-invariant; the final pose's twin is arbitrated downstream by
   refine+rms, exactly as the armed path already does.)

   Pure: it takes `disc-at?` (a fn [px r] -> bool) rather than reading a backdrop,
   so it is node-testable on a synthetic scene, mirroring blob/snap-to-blob's
   injected `lum-at`. The caller (edit_acquire) decides acceptance from the
   returned :zero-hit?/:crown-hits and hands the assignment to the armed solve."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.pnp :as pnp]))

(def zero-bonus
  "The zero-index hit is worth more than the whole crown, so the one orientation
   that places it wins outright over the m rotational twins that tie on crown
   count. Any value > m works; kept round."
  100)

(def ring-scale
  "The disc-presence ring is sampled at this multiple of the disc's own imaged
   radius, so it lands on the plate just outside the disc edge (not on the disc's
   own dark pixels, which would read as 'no mark')."
  1.5)

(def ^:private radius-fallback 15.0)

;; ---------------------------------------------------------------------------
;; Geometry helpers

(defn- cross3 [[a b c] [d e f]]
  [(- (* b f) (* c e)) (- (* c d) (* a f)) (- (* a e) (* b d))])

(defn- centroid-2d [pts]
  (let [n (double (count pts))]
    [(/ (reduce + 0.0 (map first pts)) n) (/ (reduce + 0.0 (map second pts)) n)]))

(defn- centroid-3d [pts]
  (la/v-scale (reduce la/v-add [0.0 0.0 0.0] pts) (/ 1.0 (count pts))))

(defn- dist-px [[ax ay] [bx by]]
  (Math/sqrt (+ (* (- ax bx) (- ax bx)) (* (- ay by) (- ay by)))))

(defn- combinations
  "Lazy seq of every k-subset of `coll`, each preserving coll's order."
  [coll k]
  (cond
    (zero? k) (list ())
    (empty? coll) ()
    :else (concat (map #(cons (first coll) %) (combinations (rest coll) (dec k)))
                  (combinations (rest coll) k))))

(defn- cyclic-order-2d
  "Indices of `pts` sorted by angle about their centroid — the clicks' cyclic
   image order."
  [pts]
  (let [[cx cy] (centroid-2d pts)]
    (vec (sort-by (fn [i] (let [[x y] (nth pts i)] (Math/atan2 (- y cy) (- x cx))))
                  (range (count pts))))))

(defn- plane-basis
  "In-plane orthonormal frame {:o :u :v} for the coplanar mark objects: o the
   centroid, u along (p0-o), v = n×u with n = (p0-o)×(p1-o). The v direction is
   arbitrary (fixed by the p0,p1 choice) — the search tries both cyclic
   handednesses, so this need only be consistent, not oriented."
  [objs]
  (let [o (centroid-3d objs)
        d0 (la/v-sub (nth objs 0) o)
        d1 (la/v-sub (nth objs 1) o)
        n (cross3 d0 d1)
        u (la/v-scale d0 (/ 1.0 (max 1e-12 (la/v-norm d0))))
        nn (la/v-scale n (/ 1.0 (max 1e-12 (la/v-norm n))))
        v (cross3 nn u)]
    {:o o :u u :v v :n nn}))

(defn- cyclic-order-3d
  "Indices of the mark objects sorted by their angle in the plane frame — the
   crown's cyclic object order."
  [objs {:keys [o u v]}]
  (vec (sort-by (fn [i] (let [d (la/v-sub (nth objs i) o)]
                          (Math/atan2 (la/v-dot d v) (la/v-dot d u))))
                (range (count objs)))))

;; ---------------------------------------------------------------------------
;; Candidate enumeration + scoring

(defn- candidate-assignments
  "Every cyclic-order-preserving map {click-idx -> mark-idx}: for each k-subset of
   the marks (in cyclic order) × k rotations × 2 handednesses, align the clicks
   (in cyclic image order) onto the subset."
  [click-order mark-order]
  (let [k (count click-order)]
    (for [positions (combinations (vec (range (count mark-order))) k)
          :let [marks-cyc (mapv #(nth mark-order %) positions)]
          dir [1 -1]
          rot (range k)]
      (into {} (map (fn [j]
                      [(nth click-order j)
                       (nth marks-cyc (mod (+ rot (* dir j)) k))])
                    (range k))))))

(defn- disc-px-radius
  "The disc's approximate imaged radius (px) at object point `obj` under `pose`:
   the larger pixel displacement of a `disc-r`-mm offset along the two in-plane
   axes (conservative under foreshortening). `radius-fallback` when an offset
   projects behind the camera."
  [intrinsics pose obj u v disc-r]
  (let [p0 (cam/project intrinsics pose obj)
        pu (cam/project intrinsics pose (la/v-add obj (la/v-scale u disc-r)))
        pv (cam/project intrinsics pose (la/v-add obj (la/v-scale v disc-r)))]
    (if (and p0 pu pv)
      (max (dist-px p0 pu) (dist-px p0 pv))
      radius-fallback)))

(defn- front-facing?
  "The plate's marked face — outward normal `n-obj` (object frame, oriented toward
   the camera side) — points TOWARD the camera under `pose`: a physically-possible,
   non-mirror view. This is the constraint that rejects the reflection twin: the
   crown+zero pattern is mirror-symmetric about the zero-index's radial axis, so a
   reflected assignment reprojects the whole pattern onto discs and ties on score
   (crown 12 + zero) — only the camera-side (non-mirror) test separates it from the
   true one. n_cam = R·n_obj; a face toward the camera has n_cam.z < 0 (the camera
   looks down +z, so its outward normal points back toward the lens)."
  [pose n-obj]
  (let [[_ _ row2] (cam/rodrigues (:rvec pose))]
    (neg? (la/v-dot row2 n-obj))))

(def max-refine
  "How many top coarse candidates to LM-refine in the fine pass. The true subset's
   ~m rotations + the mirror family are all that reproject the crown near the
   discs, so a few tens comfortably covers them; refining only these keeps the fine
   pass cheap (LM is far dearer than a seed homography)."
  40)

(defn- disc-hits
  "Reproject `objs` (+ `zero-obj`) under `pose` and count disc hits. TIGHT
   (coarse? false): the reprojection must land ON a disc (disc-at? at the point).
   COARSE (coarse? true): a hit also counts if a disc sits within ~2 ring-radii of
   the reprojection (sampled on the 4 axes) — this tolerates the seed homography's
   reprojection error (tens of px on real, lens-distorted data, comparable to the
   disc size), so the true candidate survives the coarse shortlist even though its
   UNREFINED seed misses the discs; the fine pass then LM-refines it onto them."
  [pose objs zero-obj intrinsics disc-at? disc-r {:keys [u v]} coarse?]
  (let [hit? (fn [obj]
               (when-let [[pu pv] (cam/project intrinsics pose obj)]
                 (let [r (* ring-scale (disc-px-radius intrinsics pose obj u v disc-r))]
                   (or (disc-at? [pu pv] r)
                       (when coarse?
                         (let [s (* 2.0 r)]
                           (some (fn [[dx dy]] (disc-at? [(+ pu dx) (+ pv dy)] r))
                                 [[s 0] [(- s) 0] [0 s] [0 (- s)]])))))))]
    {:crown (count (filter hit? objs))
     :zero? (boolean (hit? zero-obj))}))

(defn assign-marks
  "Recover which crown mark each of `clicks` is, identity-free (fetta B). Returns
   the best-scoring candidate or nil.

   clicks     [[u v] …]                 ≥4 blob-snapped disc centroids, any order
   marks      [{:id :obj [x y z]} …]    the m crown marks, object/solver frame
   zero-obj   [x y z]                    the asymmetric zero-index, same frame
   intrinsics {…}                        camera intrinsics
   disc-at?   (fn [px r] -> bool)        cheap dark-disc presence judge
   opts       {:disc-r mm                physical disc radius (default 1.25)
               :face-normal [x y z]}     marked-face outward normal (object frame,
                                         toward the camera) — rejects the mirror
                                         twin; omit only in tests that ignore it

   Two passes, because the seedless homography from k clicks reprojects the rest of
   the crown only coarsely (tens of px on real data, comparable to the disc size —
   too imprecise for the on-disc judge, and the zero-index tiebreak needs a tight
   pose): a COARSE pass seed-scores every cyclic-order-preserving candidate with a
   tolerant hit test and shortlists the top `max-refine`; a FINE pass LM-refines
   each shortlisted pose against its declared correspondences (→ sub-10px), rejects
   the back-facing mirror twin, and scores tightly (on-disc crown + zero bonus).

   Result: {:assignment {click-idx -> mark-idx}   mark-idx = index into `marks`
            :pose {:rvec :t}                        the winning candidate's REFINED pose
            :crown-hits n :zero-hit? bool :score s}
   The CALLER decides acceptance (require :zero-hit? — it fixes the rotation — and
   a crown-hits threshold), then feeds the assignment to the armed solve. Returns
   nil for <4 clicks, fewer marks than clicks, a missing zero-index, or when no
   candidate produced a valid (front-facing) homography at all."
  [clicks marks zero-obj intrinsics disc-at? {:keys [disc-r face-normal] :or {disc-r 1.25}}]
  (when (and (>= (count clicks) 4) (>= (count marks) (count clicks)) zero-obj)
    (let [click-order (cyclic-order-2d clicks)
          objs (mapv :obj marks)
          plane (plane-basis objs)
          mark-order (cyclic-order-3d objs plane)
          ;; COARSE: seed each candidate, tolerant hit count
          coarse (keep (fn [assignment]
                         (let [corr (mapv (fn [[ci mi]]
                                            {:world (:obj (nth marks mi)) :px (nth clicks ci)})
                                          assignment)]
                           (when-let [seed (pnp/estimate-homography corr intrinsics)]
                             {:assignment assignment :corr corr :seed seed
                              :coarse (:crown (disc-hits seed objs zero-obj intrinsics
                                                         disc-at? disc-r plane true))})))
                       (candidate-assignments click-order mark-order))
          shortlist (take max-refine (sort-by :coarse > coarse))
          ;; FINE: refine each shortlisted pose, drop the back-facing mirror, score tight
          fine (keep (fn [{:keys [assignment corr seed]}]
                       (let [pose (or (:pose (pnp/refine corr intrinsics seed {:sigma-px 1.0})) seed)]
                         (when (or (nil? face-normal) (front-facing? pose face-normal))
                           (let [{:keys [crown zero?]} (disc-hits pose objs zero-obj intrinsics
                                                                  disc-at? disc-r plane false)]
                             {:assignment assignment :pose pose
                              :crown-hits crown :zero-hit? zero?
                              :score (+ crown (if zero? zero-bonus 0))}))))
                     shortlist)]
      (when (seq fine)
        (apply max-key :score fine)))))

;; ---------------------------------------------------------------------------
;; Turntable ring — register a photo from an already-registered one (fetta B+)

(defn- rotate-about
  "Rodrigues rotation of vector `v` about the UNIT axis `k` by `angle` rad."
  [v k angle]
  (let [c (Math/cos angle) s (Math/sin angle)]
    (la/v-add (la/v-add (la/v-scale v c) (la/v-scale (cross3 k v) s))
              (la/v-scale k (* (la/v-dot k v) (- 1.0 c))))))

(defn find-ring-pose
  "Register a plate photo with ZERO clicks by exploiting a turntable ring: given
   the solver pose `ref-pose` of an ALREADY-registered photo, and knowing the
   plate spins about its own axis (the marks' plane normal, through their
   centroid), find the plate rotation θ whose reprojection lands the crown +
   zero-index on discs — the plate rotation of ANOTHER photo of the same ring (the
   camera fixed on the ring, the plate turned by θ from the reference).

   Reprojecting a mark at angle θ = project(ref-pose, spin(mark, θ)): spinning the
   OBJECT under the fixed reference view is exactly equivalent to the camera
   orbiting the axis, so one registered photo generates the whole ring. The crown
   is 12-fold symmetric, so 12 angles score the crown alike; the asymmetric
   zero-index elects the true one (as in assign-marks). The disc test is COARSE
   (the ring is only approximate — hand-held cameras scatter off it — and the disc
   size absorbs it); the caller blob-snaps the returned pixels and runs a full PnP
   to get the exact pose. `disc-at?` samples THIS photo's luminance.

   The plate spins about its own normal (the marks' plane) through their centre.
   That is accurate for a NEAR reference — a hand-held ring is not a perfect
   circle, so extrapolating a far-away photo drifts — hence the caller predicts
   each photo from its NEAREST registered reference (max score over all).

   Returns {:theta rad :crown-hits n :zero-hit? bool :score s
            :pixels {mark-idx [u v]}} (pixels = the predicted mark pixels to
   blob-snap + solve on) or nil when no angle lands the crown. The caller requires
   :zero-hit? and a crown threshold before trusting it."
  [ref-pose marks zero-obj intrinsics disc-at? {:keys [disc-r step-deg]
                                                :or {disc-r 1.25 step-deg 1.0}}]
  (when (and ref-pose zero-obj (>= (count marks) 4))
    (let [objs (mapv :obj marks)
          {:keys [o n] :as plane} (plane-basis objs)
          axis (la/v-scale n (/ 1.0 (max 1e-12 (la/v-norm n))))
          spin (fn [p a] (la/v-add o (rotate-about (la/v-sub p o) axis a)))
          step (* step-deg (/ Math/PI 180.0))
          n-steps (max 1 (int (/ (* 2.0 Math/PI) step)))
          scored (keep (fn [k]
                         (let [a (* k step)
                               spun (mapv #(spin % a) objs)
                               ;; TIGHT (not coarse): a clean ring pair reprojects
                               ;; WITHIN the disc, and only the tight test keeps the
                               ;; zero-index tiebreak sharp — a coarse ±2-radius test
                               ;; lets the zero "hit" at several rotations and a
                               ;; wrong θ wins (the 1115px failure on a 9° pair).
                               {:keys [crown zero?]} (disc-hits ref-pose spun (spin zero-obj a)
                                                                intrinsics disc-at? disc-r plane false)]
                           (when (pos? crown)
                             {:theta a :crown-hits crown :zero-hit? zero?
                              :score (+ crown (if zero? zero-bonus 0))})))
                       (range n-steps))]
      (when (seq scored)
        (let [best (apply max-key :score scored)]
          (assoc best :pixels
                 (into {} (keep-indexed
                           (fn [i m]
                             (when-let [px (cam/project intrinsics ref-pose (spin (:obj m) (:theta best)))]
                               [i px]))
                           marks))))))))
