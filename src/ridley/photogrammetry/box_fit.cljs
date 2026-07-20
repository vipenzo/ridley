(ns ridley.photogrammetry.box-fit
  "The 'registrazione per proxy' fit, for a box proxy.

   This is the mathematical core the design doc puts on the gate: the user
   aligns a box by eye on photo 1, re-aims the camera on photo 2+, and the
   solver refines poses AND box dimensions together against edge
   observations. Everything here is pure.

   Two modelling choices carry most of the weight:

   1. AN EDGE CONSTRAINS ONLY ITS PERPENDICULAR. What an edge detector (or a
      human tracing) can localise is the line an edge lies on, not where
      along that line its endpoints sit — the aperture problem. So the
      residual of a projected edge is the point-to-LINE distance of its two
      endpoints from the observed line, never point-to-point. Modelling it
      as point-to-point would invent information the image does not contain
      and would report an over-optimistic error.

   2. SCALE IS A GAUGE FREEDOM, NOT AN ERROR. Scaling the box and every
      camera translation by the same factor reprojects identically, so
      J^T J is singular by exactly one dimension no matter how many photos
      are taken. That null direction is what the caliper measurement fixes —
      it is not something more views can cure."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.lm :as lm]))

;; ---------------------------------------------------------------------------
;; Box geometry

(def ^:private corner-signs
  "The 8 corners as sign triples, indexed 0-7 by the bits of the index."
  (vec (for [a [-1 1] b [-1 1] c [-1 1]] [a b c])))

(def edges
  "The 12 edges as [corner-index corner-index]. Two corners are adjacent
   when their sign triples differ in exactly one component."
  (vec (for [i (range 8)
             j (range (inc i) 8)
             :let [si (corner-signs i) sj (corner-signs j)]
             :when (= 1 (count (filter false? (map = si sj))))]
         [i j])))

(def ^:private faces
  "The 6 faces as {:axis 0|1|2 :sign -1|1}, used for back-face culling."
  (vec (for [axis (range 3) sign [-1 1]] {:axis axis :sign sign})))

(defn corners
  "World-space corner positions of a box of full extents [w h d] centred at
   the origin and axis-aligned (the object frame IS the world frame)."
  [[w h d]]
  (mapv (fn [[a b c]] [(* a w 0.5) (* b h 0.5) (* c d 0.5)]) corner-signs))

(defn- face-of-corner? [face corner-index]
  (= (:sign face) (nth (corner-signs corner-index) (:axis face))))

(defn- adjacent-faces [[i j]]
  (filterv (fn [f] (and (face-of-corner? f i) (face-of-corner? f j))) faces))

(defn edge-axis
  "Which axis (0/1/2) edge `k` runs along. A box's 12 edges fall into three
   groups of four by direction, and a moulded or machined part very often has
   a different radius per group — so this is the natural way to let the fillet
   be more than one number."
  [k]
  (let [[i j] (nth edges k)]
    (first (keep-indexed (fn [ax [a b]] (when (not= a b) ax))
                         (map vector (corner-signs i) (corner-signs j))))))

(defn edge-geometry
  "For edge `k` of a box of extents `dims`, return

     {:corners [p1 p2]   the two sharp corner positions
      :dir     e         unit vector along the edge
      :normals [n1 n2]}  outward unit normals of the two adjacent faces

   This is what a fillet model needs: the rounded edge is a cylinder whose
   axis is the sharp edge pulled inward by r along BOTH adjacent normals."
  [dims k]
  (let [pts (corners dims)
        [i j] (nth edges k)
        si (corner-signs i) sj (corner-signs j)
        diff (first (keep-indexed (fn [ax [a b]] (when (not= a b) ax))
                                  (map vector si sj)))
        axis-vec (fn [ax s] (assoc [0.0 0.0 0.0] ax (double s)))]
    {:corners [(nth pts i) (nth pts j)]
     :dir (axis-vec diff 1)
     :normals (vec (for [ax (range 3) :when (not= ax diff)]
                     (axis-vec ax (nth si ax))))}))

;; ---------------------------------------------------------------------------
;; Fillets
;;
;; A real part has rounded edges, and what the camera sees on one is NOT the
;; sharp corner: it is where the view ray is tangent to the fillet cylinder,
;; which sits inside the corner by an amount that DEPENDS ON VIEWING ANGLE
;; (zero looking square at a face, maximal at 45 degrees). That angular
;; signature is what makes the radius identifiable rather than degenerate with
;; the box dimensions — but only across several viewpoints. From a single view
;; a fillet is indistinguishable from a smaller box.

(defn- v- [a b] (mapv - a b))
(defn- v+ [a b] (mapv + a b))
(defn- v* [a s] (mapv #(* % s) a))
(defn- dot3 [a b] (reduce + 0.0 (map * a b)))
(defn- cross3 [[a b c] [d e f]]
  [(- (* b f) (* c e)) (- (* c d) (* a f)) (- (* a e) (* b d))])
(defn- norm3 [v]
  (let [m (Math/sqrt (dot3 v v))] (if (< m 1e-12) v (v* v (/ 1.0 m)))))

(defn- face-front-facing?
  "Is the face with outward normal `n` (a signed axis vector) turned toward
   the camera at `c`, for a box of extents `dims`?"
  [dims n c]
  (let [ax (first (keep-indexed (fn [i v] (when (not= 0.0 v) i)) n))
        s (nth n ax)
        extent (* s 0.5 (nth dims ax))]
    (> (* s (- (nth c ax) extent)) 1e-9)))

(defn edge-silhouette-points
  "The two 3D points whose projection is the visible line of edge `k`, for a
   box of extents `dims` with fillet radius `r`, seen from camera centre `c`.

   Models the SILHOUETTE case: the visible boundary is where the view ray
   grazes the fillet, so the line sits at the outer tangent of the fillet
   cylinder. With r = 0 it returns the sharp corners exactly, so the model is
   continuous at zero and the radius can be optimised from there.

   KNOWN LIMITATION, measured on the real session (2026-07-19). An interior
   crease — an edge whose two faces are BOTH visible — is not a silhouette at
   all: there is only a shaded band across the rounding, whose crest lies
   along the bisector of the two faces, about r away from the tangent. That
   is physically the wrong feature to compare against here.

   Branching on it made things WORSE, and instructively so: the branch is a
   DISCONTINUITY. As an edge approaches the silhouette boundary, an
   infinitesimal parameter change flips the branch and jumps the predicted
   line by a whole radius, so the numerical Jacobian across that jump is
   meaningless and the fit ran away to a -5.33 mm radius. The fix is a smooth
   blend between crest and tangent weighted by how close the face is to
   grazing, not a hard test. Until that exists, the smooth silhouette-only
   model is the one the experiments validate.

   Takes the camera CENTRE rather than the pose so the caller can hoist that
   computation out of the per-observation loop."
  [dims r c k]
  (let [{:keys [corners dir normals]} (edge-geometry dims k)]
    (if (< (Math/abs r) 1e-12)
      corners
      (let [[n1 n2] normals
            inward (v+ (v* n1 (- r)) (v* n2 (- r)))
            axis-pts (mapv #(v+ % inward) corners)
            mid (v* (v+ (first axis-pts) (second axis-pts)) 0.5)
            view (v- c mid)
            e (norm3 dir)
            v-perp (norm3 (v- view (v* e (dot3 view e))))
            w0 (norm3 (cross3 e v-perp))
            outward (v+ n1 n2)
            w (if (pos? (dot3 w0 outward)) w0 (v* w0 -1.0))]
        (mapv #(v+ % (v* w r)) axis-pts)))))

(defn visible-edges
  "Indices into `edges` of the edges visible from a camera pose, for a convex
   box: an edge is visible when at least one of its two adjacent faces is
   front-facing. This is what makes the synthetic experiment honest — a real
   photo never shows all 12 edges, and pretending otherwise would roughly
   double the constraints for free."
  [dims pose]
  (let [c (cam/camera-center pose)
        dims-v (vec dims)]
    (vec (keep-indexed
          (fn [idx edge]
            (when (some (fn [{:keys [axis sign]}]
                          (let [;; a point on the face, and its outward normal
                                extent (* sign 0.5 (nth dims-v axis))
                                to-cam (- (nth c axis) extent)]
                            (> (* sign to-cam) 1e-9)))
                        (adjacent-faces edge))
              idx))
          edges))))

;; ---------------------------------------------------------------------------
;; Observations

;; An observation is {:view n :edge k :line [nx ny c]} where the line is
;; normalised (nx^2 + ny^2 = 1) and a pixel p lies on it when
;; nx*px + ny*py + c = 0.

(defn line-through
  "Normalised line through two image points, as [nx ny c]. Returns nil for
   coincident points."
  [[x1 y1] [x2 y2]]
  (let [dx (- x2 x1) dy (- y2 y1)
        len (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (when (> len 1e-9)
      (let [nx (/ (- dy) len) ny (/ dx len)]
        [nx ny (- (+ (* nx x1) (* ny y1)))]))))

(defn point-line-distance [[nx ny c] [px py]]
  (+ (* nx px) (* ny py) c))

;; ---------------------------------------------------------------------------
;; Parameter packing
;;
;; p = [w h d  rvec0(3) t0(3)  rvec1(3) t1(3) ...]

(defn fillet-seq
  "Normalise a fillet spec to a vector of radii: nil (no fillet), one shared
   radius, or one per edge-direction axis."
  [fillet]
  (cond (nil? fillet) nil
        (number? fillet) [fillet]
        :else (vec fillet)))

(defn radius-for-edge
  "Pick the radius that applies to edge k from a packed fillet vector."
  [fillets k]
  (when (seq fillets)
    (if (= 1 (count fillets)) (nth fillets 0) (nth fillets (edge-axis k)))))

(defn pack
  "p = [w h d  rvec0 t0  rvec1 t1 ...  (fillet radii)]
   The optional fillet radii go LAST so that every existing index is unchanged
   whether or not they are being fitted. One value = a single shared radius;
   three = one per edge-direction axis."
  ([dims poses] (pack dims poses nil))
  ([dims poses fillet]
   (vec (concat dims
                (mapcat (fn [{:keys [rvec t]}] (concat rvec t)) poses)
                (fillet-seq fillet)))))

(defn unpack [p n-views]
  (let [p (vec p)
        n-fixed (+ 3 (* 6 n-views))]
    {:dims (subvec p 0 3)
     :poses (vec (for [i (range n-views)]
                   (let [base (+ 3 (* i 6))]
                     {:rvec (subvec p base (+ base 3))
                      :t (subvec p (+ base 3) (+ base 6))})))
     :fillet (when (> (count p) n-fixed) (subvec p n-fixed))}))

;; ---------------------------------------------------------------------------
;; Residuals

(def ^:private behind-camera-penalty
  "Penalty (in sigmas) for a corner that fails to image. Large enough to
   repel the optimiser, finite so the cost stays differentiable-ish and the
   residual vector keeps a constant length."
  1000.0)

(defn make-residual-fn
  "Build the weighted residual function for LM.

   observations : [{:view :edge :line}]
   intrinsics   : per-view vector of intrinsics maps
   opts         : {:sigma-px       edge localisation sigma, pixels
                   :scale-constraint {:axis 0|1|2 :value mm :sigma mm}}

   The scale constraint is the caliper reading. Without it the problem is
   rank-deficient by one and LM will wander along the scale direction."
  [observations intrinsics n-views
   {:keys [sigma-px scale-constraint] :or {sigma-px 1.0}}]
  (fn [p]
    (let [{:keys [dims poses fillet]} (unpack p n-views)
          pts (corners dims)
          ;; hoisted out of the observation loop: one camera centre per view,
          ;; not one per observed edge
          centers (when fillet (mapv cam/camera-center poses))
          reproj (mapv (fn [{:keys [view edge line]}]
                         (let [pose (nth poses view)
                               k (nth intrinsics view)
                               [p3a p3b] (if fillet
                                           (edge-silhouette-points
                                            dims (radius-for-edge fillet edge)
                                            (nth centers view) edge)
                                           (let [[ia ib] (nth edges edge)]
                                             [(nth pts ia) (nth pts ib)]))
                               pa (cam/project k pose p3a)
                               pb (cam/project k pose p3b)]
                           (if (and pa pb)
                             [(/ (point-line-distance line pa) sigma-px)
                              (/ (point-line-distance line pb) sigma-px)]
                             [behind-camera-penalty behind-camera-penalty])))
                       observations)
          flat (vec (apply concat reproj))]
      ;; scale-constraint may be one constraint or several. Several is what
      ;; per-photo solving needs: with the part measured, all three dimensions
      ;; are known and the only unknown left is the pose.
      (if scale-constraint
        (reduce (fn [acc {:keys [axis value sigma]}]
                  (conj acc (/ (- (nth dims axis) value) sigma)))
                flat
                (if (map? scale-constraint) [scale-constraint] scale-constraint))
        flat))))

(defn fit
  "Run the proxy fit. Returns the LM result augmented with :dims, :poses and
   (when fitted) :fillet.

   opts :fillet-init — when given, the fillet radius becomes a fitted
   parameter starting from this value. Costs one parameter and models the
   dominant systematic error on any part that is not knife-edged."
  ([observations intrinsics init-dims init-poses opts]
   (let [n-views (count init-poses)
         rfn (make-residual-fn observations intrinsics n-views opts)
         res (lm/solve rfn (pack init-dims init-poses (:fillet-init opts))
                       (:lm opts))]
     (merge res (unpack (:params res) n-views)
            {:residual-fn rfn :n-views n-views}))))

(defn rms-reprojection-px
  "RMS of the reprojection residuals in PIXELS (undoing the sigma weighting
   and excluding any scale constraint), which is the number an operator can
   actually judge: 'the wireframe sits within N pixels of the photo'."
  [result observations {:keys [sigma-px] :or {sigma-px 1.0}}]
  (let [n (* 2 (count observations))
        rs (take n (:residuals result))
        ss (map #(* % % sigma-px sigma-px) rs)]
    (Math/sqrt (/ (reduce + 0.0 ss) (max 1 n)))))

(defn spectrum
  "Eigenvalue spectrum of J^T J at the solution — the rank analysis that the
   design doc wants to drive the 'serve una vista da qui' guidance."
  [result]
  (lm/covariance-spectrum (:residual-fn result) (:params result)))
