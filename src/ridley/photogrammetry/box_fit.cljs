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

(defn pack [dims poses]
  (vec (concat dims (mapcat (fn [{:keys [rvec t]}] (concat rvec t)) poses))))

(defn unpack [p n-views]
  {:dims (vec (take 3 p))
   :poses (vec (for [i (range n-views)]
                 (let [base (+ 3 (* i 6))]
                   {:rvec (vec (subvec (vec p) base (+ base 3)))
                    :t (vec (subvec (vec p) (+ base 3) (+ base 6)))})))})

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
    (let [{:keys [dims poses]} (unpack p n-views)
          pts (corners dims)
          reproj (mapv (fn [{:keys [view edge line]}]
                         (let [pose (nth poses view)
                               k (nth intrinsics view)
                               [ia ib] (nth edges edge)
                               pa (cam/project k pose (nth pts ia))
                               pb (cam/project k pose (nth pts ib))]
                           (if (and pa pb)
                             [(/ (point-line-distance line pa) sigma-px)
                              (/ (point-line-distance line pb) sigma-px)]
                             [behind-camera-penalty behind-camera-penalty])))
                       observations)
          flat (vec (apply concat reproj))]
      (if scale-constraint
        (let [{:keys [axis value sigma]} scale-constraint]
          (conj flat (/ (- (nth dims axis) value) sigma)))
        flat))))

(defn fit
  "Run the proxy fit. Returns the LM result augmented with :dims and :poses."
  ([observations intrinsics init-dims init-poses opts]
   (let [n-views (count init-poses)
         rfn (make-residual-fn observations intrinsics n-views opts)
         res (lm/solve rfn (pack init-dims init-poses) (:lm opts))]
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
