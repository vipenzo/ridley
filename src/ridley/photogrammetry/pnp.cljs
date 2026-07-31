(ns ridley.photogrammetry.pnp
  "Camera pose from DECLARED 3D↔2D point correspondences (PnP), the core of
   the 'registrazione per corrispondenze' gesture (brief P2): the user pairs a
   known model point — a box corner in the object frame — with its pixel in
   the photo, so the correspondence is GIVEN, not inferred. That is what kills
   the two failure modes of the drag+snap path: the box's Klein-symmetry
   ambiguity (there is no search over which virtual corner is which physical
   one — the user said so) and the coarse-pose search basin per-photo
   solve-photo needs (the pose is computed in closed form, not swept for).

   Two stages:

   1. estimate-dlt — a SEEDLESS linear (DLT) estimate in CALIBRATED image
      coordinates: each pixel is normalised by the intrinsics (xn=(u-cx)/fx,
      yn=(v-cy)/fy), so the camera becomes K=I and we solve directly for the
      12 entries of [R|t]. The homogeneous scale is fixed by pinning tz (the
      object distance, always positive and non-zero), which turns the null-
      space problem into an ordinary least-squares solve (linalg/solve on the
      11×11 normal equations) — no SVD/eigenvector routine needed. Requires
      ≥6 correspondences that are NOT coplanar (box corners spanning ≥2 faces);
      a coplanar or under-determined set makes the normal equations singular
      and returns nil (an honest failure, not a wrong pose).

   2. refine — Levenberg-Marquardt (lm/solve) on the 6-DOF pose, minimising
      the reprojection of each model point onto its clicked pixel. Cleans up
      the linear estimate's orthonormality/scale slack to sub-pixel.

   correspondences: [{:world [x y z]  ; object-frame 3D point (a box corner)
                      :px    [u v]}]  ; the clicked pixel
   intrinsics: {:fx :fy :cx :cy ...}  (cam/intrinsics-from-fov)
   pose: {:rvec [..] :t [..]}          (cam's world→camera convention)

   KNOWN LIMITATION: the Gram-Schmidt orthonormalisation of the linear R is
   the NEAREST right-handed frame to r2/r0, not the full polar decomposition;
   for clean, well-spread box-corner clicks it lands in the correct basin and
   LM finishes the job, and a pathological (mirror) seed simply shows up as a
   high refined rms the caller can reject — it never silently passes."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.lm :as lm]))

(def min-correspondences
  "Twelve unknowns in [R|t], one pinned by the scale gauge, leaves eleven; two
   equations per point ⇒ six points is the minimum well-posed set (and forces
   the user off a single coplanar face)."
  6)

;; ---------------------------------------------------------------------------
;; Linear (DLT) estimate in calibrated coordinates

(defn- normalized-point [{:keys [fx fy cx cy]} [u v]]
  [(/ (- u cx) fx) (/ (- v cy) fy)])

(defn- dlt-rows
  "The two calibrated DLT rows (11 coefficients each, tz pinned to 1 and moved
   to the RHS) for one correspondence, as [[coeffs rhs] [coeffs rhs]]."
  [intrinsics {:keys [world px]}]
  (let [[x y z] world
        [xn yn] (normalized-point intrinsics px)]
    ;; unknowns p = [r0(3) tx  r1(3) ty  r2(3)]  (tz fixed = 1)
    [[[x y z  1  0 0 0  0  (- (* xn x)) (- (* xn y)) (- (* xn z))] xn]
     [[0 0 0  0  x y z  1  (- (* yn x)) (- (* yn y)) (- (* yn z))] yn]]))

(defn- normal-equations
  "(A^T A, A^T b) accumulated over all `rows` [[coeffs rhs]...], for an
   `n`-unknown least squares (11 for the calibrated DLT, 8 for the planar
   homography)."
  [n rows]
  (let [ata (mapv (fn [_] (double-array n)) (range n))
        atb (double-array n)]
    (doseq [[a b] rows]
      (dotimes [i n]
        (let [ai (nth a i)]
          (aset atb i (+ (aget atb i) (* ai b)))
          (dotimes [j n]
            (let [^doubles row (nth ata i)]
              (aset row j (+ (aget row j) (* ai (nth a j)))))))))
    [(mapv vec ata) (vec atb)]))

(defn- gram-schmidt-rotation
  "Nearest right-handed orthonormal frame to the raw rotation rows, anchored on
   r2 (the viewing direction, the best-determined row): r2 kept, r0
   orthogonalised against it, r1 reconstructed as r2×r0 (so the DLT's own noisy
   r1 row is discarded — r0 and r2 already fix a rotation). Returns rows-of-rows."
  [r0 r2]
  (let [r2n (la/v-scale r2 (/ 1.0 (max 1e-12 (la/v-norm r2))))
        r0p (la/v-sub r0 (la/v-scale r2n (la/v-dot r0 r2n)))
        r0n (la/v-scale r0p (/ 1.0 (max 1e-12 (la/v-norm r0p))))
        cross (fn [[a b c] [d e f]] [(- (* b f) (* c e)) (- (* c d) (* a f)) (- (* a e) (* b d))])
        r1n (cross r2n r0n)]
    [r0n r1n r2n]))

(defn estimate-dlt
  "Seedless linear pose from ≥6 non-coplanar correspondences, or nil when the
   system is singular (too few points / coplanar)."
  [correspondences intrinsics]
  (when (>= (count correspondences) min-correspondences)
    (let [rows (mapcat #(dlt-rows intrinsics %) correspondences)
          [ata atb] (normal-equations 11 rows)
          p (la/solve ata atb)]
      (when p
        (let [r0 [(nth p 0) (nth p 1) (nth p 2)]
              tx (nth p 3)
              r1 [(nth p 4) (nth p 5) (nth p 6)]
              ty (nth p 7)
              r2 [(nth p 8) (nth p 9) (nth p 10)]
              ;; scale = mean raw row norm = 1/tz_true (rows of the true R are
              ;; unit, the DLT solution is the true [R|t] divided by tz_true)
              s (/ (+ (la/v-norm r0) (la/v-norm r1) (la/v-norm r2)) 3.0)]
          (when (> s 1e-9)
            {:rvec (cam/rot-mat->rodrigues (gram-schmidt-rotation r0 r2))
             :t [(/ tx s) (/ ty s) (/ 1.0 s)]}))))))

;; ---------------------------------------------------------------------------
;; Planar (homography) estimate for COPLANAR correspondences
;;
;; The DLT above is singular on coplanar model points — a registration PLATE
;; puts every mark on ONE face, so `estimate-dlt` returns nil (and click noise
;; that breaks coplanarity only slightly makes it return a garbage pose). The
;; standard fix, exactly how ArUco/ChArUco/AprilTag register a flat target, is:
;; fit a plane to the model points, estimate the 3×3 homography from plane
;; coordinates to the calibrated image, and decompose it into a pose. `refine`
;; then cleans that seed to sub-pixel exactly as it does the DLT's.

(defn- cross3 [[a b c] [d e f]]
  [(- (* b f) (* c e)) (- (* c d) (* a f)) (- (* a e) (* b d))])

(defn- centroid [pts]
  (la/v-scale (reduce la/v-add [0.0 0.0 0.0] pts) (/ 1.0 (count pts))))

(defn- covariance
  "Σ q qᵀ over the centred points, a 3×3 symmetric matrix (rows-of-rows)."
  [centered]
  (reduce (fn [acc q]
            (mapv (fn [row qi] (mapv (fn [c qj] (+ c (* qi qj))) row q)) acc q))
          [[0.0 0.0 0.0] [0.0 0.0 0.0] [0.0 0.0 0.0]]
          centered))

(defn plane-frame
  "Orthonormal frame {:o :u :v :n} of the best-fit plane through `pts`: o the
   centroid; n the plane normal — recovered WITHOUT an eigenvector routine from
   the covariance's rank-2 structure (for coplanar points the covariance's
   columns span the plane, so the cross product of the two most-independent
   columns is the normal); u,v an in-plane right-handed basis with n = u×v (so
   the object→plane basis [u v n] is a proper rotation, det +1, for either sign
   of n). Returns nil when the points are collinear/degenerate (no plane).

   Public because the PLANE MARK gesture fits its surface with exactly this
   (ridley.photogrammetry.triangulate/fit-plane-mark) — same fit, different
   consumer: there the normal becomes a mark's heading instead of a pose seed."
  [pts]
  (let [o (centroid pts)
        centered (mapv #(la/v-sub % o) pts)
        cols (la/transpose (covariance centered))
        [raw-n _] (reduce (fn [[best m2max] [i j]]
                            (let [x (cross3 (nth cols i) (nth cols j))
                                  m2 (la/v-dot x x)]
                              (if (> m2 m2max) [x m2] [best m2max])))
                          [nil 0.0] [[0 1] [0 2] [1 2]])]
    (when (and raw-n (> (la/v-norm raw-n) 1e-9))
      (let [n (la/v-scale raw-n (/ 1.0 (la/v-norm raw-n)))
            biggest (apply max-key la/v-norm cols)
            up (la/v-sub biggest (la/v-scale n (la/v-dot biggest n)))]
        (when (> (la/v-norm up) 1e-9)
          (let [u (la/v-scale up (/ 1.0 (la/v-norm up)))
                v (cross3 n u)]
            {:o o :u u :v v :n n}))))))

(defn- homography-rows
  "The two DLT rows (8 unknowns, h33 pinned to 1 and moved to the RHS) for one
   correspondence, given its plane coordinates (a,b) and calibrated image
   coordinates (xn,yn). Unknowns h = [h11 h12 h13 h21 h22 h23 h31 h32]."
  [a b xn yn]
  [[[a b 1.0 0.0 0.0 0.0 (- (* xn a)) (- (* xn b))] xn]
   [[0.0 0.0 0.0 a b 1.0 (- (* yn a)) (- (* yn b))] yn]])

(defn estimate-homography
  "Seedless pose from ≥4 COPLANAR correspondences via the plane-induced
   homography (calibrated image, K=I), decomposed into {:rvec :t} in the SAME
   object frame the :world points live in. Returns nil when the points don't
   define a plane, are too few, or the homography is singular.

   Fit the plane (plane-frame) and write each :world as plane coordinates
   (a,b); solve the homography H mapping (a,b,1)→calibrated image, with h33
   pinned to 1 — because the plane origin sits in FRONT of the camera its depth
   is positive, so this pin fixes the homogeneous scale AND its sign in one
   move (no null-space SVD needed, mirroring estimate-dlt's tz pin). Then
   λ=2/(‖h1‖+‖h2‖)>0, r1=λh1, r2=λh2 (both = R·u, R·v up to noise); orthonormalise
   [r1 r2 r3=r1×r2] into the plane→camera rotation and compose with the
   object→plane basis to get object→camera R; t = λh3 − R·o. The classic 2-fold
   planar-pose ambiguity is left to `refine`+rms to arbitrate — the plate is
   photographed obliquely (never fronto-parallel), where the twin is far and LM
   won't be drawn to it."
  [correspondences intrinsics]
  (when (>= (count correspondences) 4)
    (when-let [{:keys [o u v n]} (plane-frame (mapv :world correspondences))]
      (let [rows (mapcat (fn [{:keys [world px]}]
                           (let [q (la/v-sub world o)
                                 [xn yn] (normalized-point intrinsics px)]
                             (homography-rows (la/v-dot q u) (la/v-dot q v) xn yn)))
                         correspondences)
            [ata atb] (normal-equations 8 rows)
            h (la/solve ata atb)]
        (when h
          (let [h1 [(nth h 0) (nth h 3) (nth h 6)]  ; column 1: h11 h21 h31
                h2 [(nth h 1) (nth h 4) (nth h 7)]  ; column 2: h12 h22 h32
                h3 [(nth h 2) (nth h 5) 1.0]        ; column 3: h13 h23 h33(=1)
                s1 (la/v-norm h1)
                s2 (la/v-norm h2)]
            (when (and (> s1 1e-9) (> s2 1e-9))
              (let [lam (/ 2.0 (+ s1 s2))
                    r1 (la/v-scale h1 lam)
                    r2 (la/v-scale h2 lam)
                    ;; orthonormalise the plane→camera columns, anchored on r1
                    r1n (la/v-scale r1 (/ 1.0 (la/v-norm r1)))
                    r2p (la/v-sub r2 (la/v-scale r1n (la/v-dot r2 r1n)))
                    r2n (la/v-scale r2p (/ 1.0 (max 1e-12 (la/v-norm r2p))))
                    r3n (cross3 r1n r2n)
                    ;; R = R_plane→cam · Bᵀ, with R_plane→cam's columns [r1n r2n r3n]
                    ;; and B (object→plane) = [u v n] as columns, so Bᵀ = rows u,v,n
                    r-full (la/mat*mat (la/transpose [r1n r2n r3n]) [u v n])
                    t-plane (la/v-scale h3 lam)      ; = R·o + t (origin in camera frame)
                    t-full (la/v-sub t-plane (la/mat*vec r-full o))]
                {:rvec (cam/rot-mat->rodrigues r-full)
                 :t t-full}))))))))

;; ---------------------------------------------------------------------------
;; Non-linear refinement

(def ^:private behind-camera-penalty 1000.0)

(defn- pose-residual-fn
  [correspondences intrinsics sigma-px]
  (fn [p]
    (let [pose {:rvec [(nth p 0) (nth p 1) (nth p 2)]
                :t [(nth p 3) (nth p 4) (nth p 5)]}]
      (vec (mapcat (fn [{:keys [world px]}]
                     (if-let [[pu pv] (cam/project intrinsics pose world)]
                       [(/ (- pu (first px)) sigma-px) (/ (- pv (second px)) sigma-px)]
                       [behind-camera-penalty behind-camera-penalty]))
                   correspondences)))))

(defn- rms-px
  [correspondences intrinsics pose]
  (let [sq (map (fn [{:keys [world px]}]
                  (if-let [[pu pv] (cam/project intrinsics pose world)]
                    (+ (* (- pu (first px)) (- pu (first px)))
                       (* (- pv (second px)) (- pv (second px))))
                    (* behind-camera-penalty behind-camera-penalty)))
                correspondences)]
    (Math/sqrt (/ (reduce + 0.0 sq) (max 1 (count correspondences))))))

(defn refine
  "LM-refine a pose from a seed against the correspondences. Returns
   {:pose :rms-px :n :per-point [{:world :px :residual-px}]}."
  [correspondences intrinsics seed {:keys [sigma-px] :or {sigma-px 1.0}}]
  (let [rfn (pose-residual-fn correspondences intrinsics sigma-px)
        p0 (vec (concat (:rvec seed) (:t seed)))
        res (lm/solve rfn p0 {:max-iterations 100})
        p (:params res)
        pose {:rvec [(nth p 0) (nth p 1) (nth p 2)]
              :t [(nth p 3) (nth p 4) (nth p 5)]}]
    {:pose pose
     :rms-px (rms-px correspondences intrinsics pose)
     :n (count correspondences)
     :per-point (mapv (fn [{:keys [world px] :as c}]
                        (assoc c :residual-px
                               (if-let [[pu pv] (cam/project intrinsics pose world)]
                                 (Math/sqrt (+ (* (- pu (first px)) (- pu (first px)))
                                               (* (- pv (second px)) (- pv (second px)))))
                                 behind-camera-penalty)))
                      correspondences)}))

(def accept-rms-px
  "A fit at or below this reprojection rms is 'clean' enough to stop rejecting
   correspondences. A single mislabeled corner (wrong declared identity on a
   symmetric box) does NOT show up as one big residual over an otherwise-clean
   fit — least squares spreads the damage, dragging the whole pose to a
   compromise where EVERY corner sits tens of px off. So the tell is: drop the
   worst correspondence and refit — if the rms collapses below this, that corner
   was the culprit and the rest were innocent."
  12.0)

(def ^:private outlier-factor 3.0)
(def ^:private outlier-floor-px 30.0)

(defn- gross-outlier?
  "Is the worst per-point residual a GROSS outlier — far above the rest — rather
   than ambient hand-click noise? Only then is dropping it justified; dropping to
   chase noise would wrongly flag innocent corners. True when the worst residual
   clears an absolute floor AND is several times the median of the others."
  [per-point]
  (when (> (count per-point) 1)
    (let [sorted (vec (sort > (map :residual-px per-point)))
          worst (first sorted)
          others (rest sorted)
          med (nth (vec (sort others)) (quot (count others) 2))]
      (and (> worst outlier-floor-px)
           (> worst (* outlier-factor (max 1e-6 med)))))))

(defn- coplanar?
  "Are the model points confined to a plane — so the DLT is singular/unstable
   and the planar homography is the right estimator (a registration plate, all
   marks on one face)? Measured directly on the :world points rather than
   inferred from an `estimate-dlt` nil: an EXACTLY coplanar plate makes the
   normal equations singular (nil), but click-plane marks that miss coplanarity
   by a hair of floating point make estimate-dlt return a GARBAGE pose instead —
   the RMS>126 failure. True when the max out-of-plane distance is a tiny
   fraction of the in-plane extent."
  [correspondences]
  (let [pts (mapv :world correspondences)]
    (boolean
     (when-let [{:keys [o n]} (plane-frame pts)]
       (let [off (fn [p] (Math/abs (la/v-dot (la/v-sub p o) n)))
             spread (fn [p] (la/v-norm (la/v-sub p o)))
             extent (reduce max 0.0 (map spread pts))]
         (< (reduce max 0.0 (map off pts)) (* 1e-3 (max 1e-9 extent))))))))

(defn- solve-once
  "One seedless-estimate + refine pass over `correspondences`, or nil if
   under-determined. `method` chooses the seedless estimator:
   :auto (default) routes on coplanarity — a flat plate to the planar
   homography, box corners spanning ≥2 faces to the calibrated DLT (with the
   planar homography as a fallback if the DLT is under-determined);
   :dlt forces the calibrated DLT; :planar forces the homography.
   A caller-supplied `seed` is the last resort when neither estimator applies
   (too few points)."
  [correspondences intrinsics sigma-px seed method]
  (let [use-planar? (or (= method :planar)
                        (and (= method :auto) (coplanar? correspondences)))
        dlt (when (and (not use-planar?) (not= method :planar))
              (estimate-dlt correspondences intrinsics))
        ;; planar only for a genuinely coplanar (or forced) set — never as a
        ;; blanket fallback for a non-coplanar set that was merely too small for
        ;; the DLT, where the homography is the wrong model and would return a
        ;; garbage pose instead of an honest nil.
        planar (when (and (nil? dlt) use-planar?)
                 (estimate-homography correspondences intrinsics))
        start (or dlt planar seed)]
    (when start
      (assoc (refine correspondences intrinsics start {:sigma-px sigma-px})
             :method (cond dlt :dlt planar :planar :else :seed)))))

(defn solve-pnp
  "Robust PnP: seedless DLT (≥6 correspondences) + LM refine, then GREEDY
   outlier rejection — while the fit is dirtier than accept-rms-px and there are
   points to spare (never below min-correspondences), drop the highest-residual
   correspondence and refit. Recovers a clean pose from the good corners even
   when one or two were mislabeled, and reports which were dropped so the caller
   can flag them for re-clicking. Falls back to a caller-supplied coarse `:seed`
   (e.g. the gizmo pose) when there are too few points for DLT.

   `:method` selects the seedless estimator — :auto (default) DLT-then-planar,
   :dlt (box corners), :planar (a flat registration plate) — see solve-once.

   Returns the refine map plus `:method`, `:outliers` (the dropped
   correspondences, each with its :residual-px at drop time), and `:per-point`
   over the surviving inliers — or nil when nothing can be fit."
  [correspondences intrinsics {:keys [sigma-px seed max-outliers method]
                               :or {sigma-px 1.0 max-outliers 2 method :auto}}]
  ;; tag each correspondence with a stable internal index so a dropped one can
  ;; be identified even if the caller supplied no :ci
  (let [indexed (vec (map-indexed (fn [i c] (assoc c ::i i)) correspondences))]
    (loop [corr indexed
           outliers []]
      (when-let [r (solve-once corr intrinsics sigma-px seed method)]
        (if (or (<= (:rms-px r) accept-rms-px)
                (<= (count corr) min-correspondences)
                (>= (count outliers) max-outliers)
                ;; only reject a GROSS outlier — never drop good corners to
                ;; chase ambient click noise
                (not (gross-outlier? (:per-point r))))
          (-> r
              (assoc :outliers (mapv #(dissoc % ::i) outliers))
              (update :per-point (fn [pp] (mapv #(dissoc % ::i) pp))))
          (let [worst (apply max-key :residual-px (:per-point r))]
            (recur (vec (remove #(= (::i %) (::i worst)) corr))
                   (conj outliers worst))))))))
