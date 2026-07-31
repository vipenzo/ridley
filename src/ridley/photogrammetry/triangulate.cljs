(ns ridley.photogrammetry.triangulate
  "Multi-view triangulation + plane fitting — the arithmetic behind the PLANE
   MARK gesture (dev-docs/brief-plane-marks.md, gradino 1).

   The problem this solves: with a registration PLATE the proxy sits UNDER the
   object, so it offers no working plane on the object itself — unlike a box,
   whose faces double as tracing planes. A plane mark fills that hole: the user
   clicks ≥3 points of a flat zone, each on ≥2 registered photos; each point is
   triangulated from the camera rays, and the points are fitted with a plane.
   The result is expressed as an ordinary Ridley mark — {:position :heading :up}
   with heading = the surface normal — so `(turtle (:zona (:marks A))
   (edit-path-2d …))` works with no new DSL and no change downstream.

   Both functions are pure and host-free (no THREE, no DOM): they take poses +
   pixels and return numbers, so they run in the node test suite. The gesture
   that collects the clicks lives in the editor; the guards it enforces
   (parallax, planarity, residuals) are computed here and reported per point,
   in the style of pnp's :per-point — a fit that is bad must SAY so rather than
   silently produce a plausible-looking plane."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.pnp :as pnp]))

(def min-parallax-deg
  "Below this angle between the two most-separated rays, a point is triangulated
   along a nearly-degenerate baseline: the two views see it from almost the same
   direction, so its DEPTH is barely constrained and it slides freely along the
   line of sight. Clicking the same point on two adjacent turntable shots is the
   easy way to hit this. 8° is roughly one turntable step at a 12-shot ring —
   deliberately permissive (it rejects only the truly degenerate), with the real
   feedback coming from the reprojection residual."
  8.0)

(defn- cross3 [[a b c] [d e f]]
  [(- (* b f) (* c e)) (- (* c d) (* a f)) (- (* a e) (* b d))])

(defn- unit [v]
  (let [n (la/v-norm v)]
    (when (> n 1e-12) (la/v-scale v (/ 1.0 n)))))

(defn- in-plane-dir
  "`hint` projected into the plane with unit normal `n`, or nil when the two are
   within ~6° of each other (‖projection‖ ≤ 0.1) — there the leftover is noise,
   not a direction, and using it would give the mark an essentially random up."
  [n hint]
  (let [proj (la/v-sub hint (la/v-scale n (la/v-dot hint n)))]
    (when (> (la/v-norm proj) 0.1) (unit proj))))

(defn- perp-projector
  "I − ddᵀ for a UNIT direction d: the matrix that kills the component along d,
   leaving the distance from a point to the ray's line."
  [d]
  (mapv (fn [i]
          (mapv (fn [j] (- (if (= i j) 1.0 0.0) (* (nth d i) (nth d j))))
                (range 3)))
        (range 3)))

(defn- pairwise-max-angle-deg
  "The LARGEST angle between any two ray directions — the effective parallax of
   the bundle. Max, not min: three rays where two are nearly parallel still
   triangulate well if the third comes from far away, so the best available
   baseline is what decides conditioning."
  [dirs]
  (reduce max 0.0
          (for [i (range (count dirs))
                j (range (inc i) (count dirs))]
            (let [c (max -1.0 (min 1.0 (la/v-dot (nth dirs i) (nth dirs j))))]
              (* (/ 180.0 Math/PI) (Math/acos c))))))

(defn- solve-point
  "The least-squares point + its reprojection residuals, without the outlier
   search. Closed form, no iteration: each observation is a ray (cam/pixel-ray,
   the exact inverse of cam/project) and the point minimising the sum of squared
   PERPENDICULAR distances to all the rays solves (Σ Pᵢ)·x = Σ Pᵢ·Cᵢ with
   Pᵢ = I − dᵢdᵢᵀ — a 3×3 system, la/solve. Parallel rays make that matrix
   singular and it returns nil."
  [intrinsics observations]
  (let [k-of (fn [o] (or (:intrinsics o) intrinsics))
        rays (mapv #(cam/pixel-ray (k-of %) (:pose %) (:px %)) observations)
        [a b] (reduce (fn [[a b] {:keys [origin dir]}]
                        (let [p (perp-projector dir)]
                          [(mapv #(mapv + %1 %2) a p)
                           (la/v-add b (la/mat*vec p origin))]))
                      [(la/mat-zeros 3 3) [0.0 0.0 0.0]]
                      rays)]
    (when-let [x (la/solve a b)]
      (when (every? #(js/isFinite %) x)
        (let [per-obs (mapv (fn [i {:keys [pose px] :as o}]
                              {:i i
                               :residual-px
                               (when-let [p (cam/project (k-of o) pose x)]
                                 (Math/hypot (- (nth p 0) (nth px 0))
                                             (- (nth p 1) (nth px 1))))})
                            (range) observations)
              res (keep :residual-px per-obs)]
          (when (seq res)
            {:point (vec x)
             :per-obs per-obs
             :rms-px (Math/sqrt (/ (reduce + 0.0 (map #(* % %) res)) (count res)))
             :max-residual-px (reduce max res)
             :parallax-deg (pairwise-max-angle-deg (mapv :dir rays))}))))))

(def ^:private outlier-improvement
  "A leave-one-out fit must bring the rms down to this fraction of the full fit's
   before we name that view the culprit — otherwise a merely noisy set would get
   an innocent view blamed every time."
  0.25)

(defn- worst-observation
  "WHICH click is wrong, when one of them is. The per-view residuals do NOT
   answer this: least squares moves the point until the error is spread over all
   the views, so a 120px slip on one of three came back as 62/11/62 px — with an
   UNTOUCHED view looking worst. Leave-one-out does answer it: drop each view in
   turn and see which removal makes the remaining ones agree.

   Returns the index of the offending observation, or nil — with only two views
   (neither can be blamed: they are symmetric by construction) or when no single
   removal helps enough (the set is merely noisy, not corrupted)."
  [intrinsics observations full-rms]
  (when (and (>= (count observations) 3) (pos? full-rms))
    (let [trials (keep (fn [i]
                         (when-let [r (solve-point intrinsics
                                                   (into (subvec observations 0 i)
                                                         (subvec observations (inc i))))]
                           [i (:rms-px r)]))
                       (range (count observations)))]
      (when (seq trials)
        (let [[i rms] (apply min-key second trials)]
          (when (< rms (* outlier-improvement full-rms)) i))))))

(defn triangulate
  "The world point that best explains the same physical feature clicked on
   several photos. `observations` is [{:pose {:rvec :t} :px [u v]} …] (≥2, the
   poses in the SAME world frame the result comes back in); `intrinsics` the
   shared pinhole {:fx :fy :cx :cy}, which an observation may override with its
   own :intrinsics — the clicks come from different photos and a session is not
   obliged to have shot them all at the same pixel size.

   Returns nil for <2 observations, a singular system, or a point that images
   in NO camera (behind them all). Otherwise:
     {:point [x y z]
      :per-obs [{:i <index into observations> :residual-px <or nil if behind>}]
      :rms-px, :max-residual-px  — over the observations that image
      :parallax-deg              — largest angle between two rays (conditioning)
      :worst-obs                 — index of the mis-clicked view, or nil}
   The residual is the reprojection error in PIXELS, the same currency PnP
   reports, so a bad point reads at a glance; :worst-obs (leave-one-out, see
   worst-observation) is what says WHICH click to re-take."
  [intrinsics observations]
  (when (>= (count observations) 2)
    (let [observations (vec observations)]
      (when-let [r (solve-point intrinsics observations)]
        (assoc r :worst-obs (worst-observation intrinsics observations (:rms-px r)))))))

(defn fit-plane-mark
  "Fit a plane to ≥3 world `points` and express it as a Ridley MARK —
   {:position :heading :up} — plus the numbers that say whether the zone really
   is flat.

   The frame comes from pnp/plane-frame (centroid + normal from the covariance's
   rank-2 structure, no eigen routine).

   The three components of the resulting mark are NOT equally well determined,
   and a caller should know which is which (Vincenzo 2026-07-31: 'la posizione in
   quel piano da cosa dipende?'):

   - :heading is a MEASUREMENT — averaged over every point, so it improves with
     more of them and with a wider spread, and barely moves if the same face is
     clicked again elsewhere;
   - :up is INHERITED from the caller's hints, so it is reproducible;
   - :position is the centroid of the points, i.e. an artefact of WHERE the user
     happened to click. It always lies exactly on the plane (a least-squares
     plane passes through the centroid of the points it fits), but nothing ties
     it to a feature of the surface. Callers that need a stable origin — anything
     that will have coordinates written against it — should place it themselves
     afterwards; once the plane is known that costs a single ray/plane
     intersection (the acquire stage's place-origin!).

   Two orientation choices make the mark usable rather than merely correct:

   - :toward — a world point the normal must face, in practice the mean centre
     of the cameras that saw the clicks. The surface was photographed, so it
     faces them; without this the sign of a fitted normal is arbitrary and the
     mark would extrude INTO the object half the time.
   - :up-hints — world directions to align the mark's `up` with, tried in order
     and projected into the plane (the object's own up first, so a traced
     contour comes out the way the user sees it). A hint within ~6° of the
     normal is skipped, its projection being noise — which is exactly what
     happens to 'the object's up' on a face PERPENDICULAR to it, hence a list
     rather than one hint. All exhausted → plane-frame's own in-plane axis.

   Returns nil for <3 points or a degenerate (collinear) set. Otherwise the mark
   plus:
     :flatness-mm  — largest |signed distance| from a point to the plane. This is
                     the honest verdict on 'is this zone flat': a rounded or
                     stepped area shows up here, not as a silently tilted plane.
     :per-point    — the signed distance of each point, so the WORST click can be
                     re-taken instead of the whole set."
  [points {:keys [toward up-hints]}]
  (when (>= (count points) 3)
    (when-let [{:keys [o u n]} (pnp/plane-frame points)]
      (let [n (if (and toward (neg? (la/v-dot n (la/v-sub toward o))))
                (la/v-scale n -1.0)
                n)
            up (or (some #(in-plane-dir n %) up-hints)
                   ;; plane-frame's u is in-plane by construction; re-orthogonalise
                   ;; against the possibly-flipped n so heading ⊥ up exactly.
                   (unit (la/v-sub u (la/v-scale n (la/v-dot u n)))))
            dists (mapv #(la/v-dot (la/v-sub % o) n) points)]
        (when up
          {:position o
           :heading n
           :up up
           :flatness-mm (reduce max 0.0 (map #(Math/abs %) dists))
           :per-point dists})))))

(defn plane-through-point
  "The degenerate-but-common case the brief calls out: a zone KNOWN to be
   parallel to the plate (an object sitting on the turntable, its upper face
   level) needs no plane fit at all — one triangulated point fixes the height
   and the plate's own axis gives the normal. Same {:position :heading :up}
   shape as fit-plane-mark, so the caller emits it identically; :flatness-mm is
   0 because nothing was fitted (the plane is DECLARED, not measured — and the
   disc preview is what tells the user whether the declaration holds)."
  [point normal up-hints]
  (when-let [n (unit normal)]
    (let [up (or (some #(in-plane-dir n %) up-hints)
                 ;; any in-plane axis: the smallest component of n gives the
                 ;; world axis least parallel to it, so the cross is well-conditioned
                 (let [k (apply min-key #(Math/abs (nth n %)) [0 1 2])]
                   (unit (cross3 n (assoc [0.0 0.0 0.0] k 1.0)))))]
      (when up
        {:position (vec point) :heading n :up up :flatness-mm 0.0 :per-point [0.0]}))))

(defn disc-mesh
  "A translucent disc ON a plane mark, `segments` around — the ergonomic check
   the brief asks for: drawn in the world and navigated across photos, it stays
   glued to the surface only if the plane is right (the same live-reprojection
   trick the ricalco uses). Returns preview-item mesh data {:vertices :faces};
   the caller adds the material."
  [{:keys [position heading up]} radius segments]
  (let [n (unit heading)
        u (unit (la/v-sub up (la/v-scale n (la/v-dot up n))))
        v (cross3 n u)
        rim (mapv (fn [i]
                    (let [a (* 2.0 Math/PI (/ (double i) segments))]
                      (la/v-add position
                                (la/v-add (la/v-scale u (* radius (Math/cos a)))
                                          (la/v-scale v (* radius (Math/sin a)))))))
                  (range segments))]
    {:vertices (into [(vec position)] rim)
     :faces (mapv (fn [i] [0 (inc i) (inc (mod (inc i) segments))]) (range segments))}))
