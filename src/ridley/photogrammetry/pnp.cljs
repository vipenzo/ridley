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
  "(A^T A, A^T b) accumulated over all rows, for the 11-unknown least squares."
  [rows]
  (let [n 11
        ata (mapv (fn [_] (double-array n)) (range n))
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
          [ata atb] (normal-equations rows)
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

(defn- solve-once
  "One DLT+refine pass over `correspondences`, or nil if under-determined."
  [correspondences intrinsics sigma-px seed]
  (let [dlt (estimate-dlt correspondences intrinsics)
        start (or dlt seed)]
    (when start
      (assoc (refine correspondences intrinsics start {:sigma-px sigma-px})
             :method (if dlt :dlt :seed)))))

(defn solve-pnp
  "Robust PnP: seedless DLT (≥6 correspondences) + LM refine, then GREEDY
   outlier rejection — while the fit is dirtier than accept-rms-px and there are
   points to spare (never below min-correspondences), drop the highest-residual
   correspondence and refit. Recovers a clean pose from the good corners even
   when one or two were mislabeled, and reports which were dropped so the caller
   can flag them for re-clicking. Falls back to a caller-supplied coarse `:seed`
   (e.g. the gizmo pose) when there are too few points for DLT.

   Returns the refine map plus `:method`, `:outliers` (the dropped
   correspondences, each with its :residual-px at drop time), and `:per-point`
   over the surviving inliers — or nil when nothing can be fit."
  [correspondences intrinsics {:keys [sigma-px seed max-outliers]
                               :or {sigma-px 1.0 max-outliers 2}}]
  ;; tag each correspondence with a stable internal index so a dropped one can
  ;; be identified even if the caller supplied no :ci
  (let [indexed (vec (map-indexed (fn [i c] (assoc c ::i i)) correspondences))]
    (loop [corr indexed
           outliers []]
      (when-let [r (solve-once corr intrinsics sigma-px seed)]
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
