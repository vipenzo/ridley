(ns ridley.photogrammetry.camera
  "Pinhole camera model for parametric acquisition from photos.

   Conventions (chosen to match the design doc's 'foto come vista
   prospettica di una scena 3D'):

   - A pose maps world to camera:  Xc = R(rvec) * Xw + t
   - rvec is an axis-angle (Rodrigues) 3-vector: direction = axis,
     magnitude = angle in radians. Chosen over quaternions/matrices
     because the solver needs a minimal, unconstrained 3-parameter
     rotation — no normalisation constraint to fight during LM.
   - Image coords are pixels, origin top-left, +y down.
   - Intrinsics: {:fx :fy :cx :cy :k1 :k2} — k1/k2 are radial distortion
     coefficients applied to normalised coordinates (Brown-Conrady, the
     two terms that matter at phone-lens magnitudes).")

;; ---------------------------------------------------------------------------
;; Rotation

(defn rodrigues
  "Axis-angle 3-vector -> 3x3 rotation matrix (vector of row vectors)."
  [[rx ry rz]]
  (let [theta (Math/sqrt (+ (* rx rx) (* ry ry) (* rz rz)))]
    (if (< theta 1e-12)
      ;; first-order expansion near identity keeps the Jacobian well-behaved
      [[1.0 (- rz) ry]
       [rz 1.0 (- rx)]
       [(- ry) rx 1.0]]
      (let [kx (/ rx theta) ky (/ ry theta) kz (/ rz theta)
            c (Math/cos theta) s (Math/sin theta) v (- 1.0 c)]
        [[(+ c (* kx kx v))        (- (* kx ky v) (* kz s))  (+ (* kx kz v) (* ky s))]
         [(+ (* ky kx v) (* kz s)) (+ c (* ky ky v))         (- (* ky kz v) (* kx s))]
         [(- (* kz kx v) (* ky s)) (+ (* kz ky v) (* kx s))  (+ c (* kz kz v))]]))))

(defn rot-mat->rodrigues
  "3x3 rotation matrix -> axis-angle 3-vector. Inverse of `rodrigues`."
  [m]
  (let [[[m00 m01 m02] [m10 m11 m12] [m20 m21 m22]] m
        trace (+ m00 m11 m22)
        cos-t (max -1.0 (min 1.0 (/ (- trace 1.0) 2.0)))
        theta (Math/acos cos-t)]
    (cond
      (< theta 1e-9) [0.0 0.0 0.0]
      (< (- Math/PI theta) 1e-6)
      ;; near pi: axis from the diagonal of (R + I)/2
      (let [ax (Math/sqrt (max 0.0 (/ (+ m00 1.0) 2.0)))
            ay (Math/sqrt (max 0.0 (/ (+ m11 1.0) 2.0)))
            az (Math/sqrt (max 0.0 (/ (+ m22 1.0) 2.0)))
            ay (if (neg? (- m01 m10)) (- ay) ay)
            az (if (neg? (- m02 m20)) (- az) az)]
        [(* theta ax) (* theta ay) (* theta az)])
      :else
      (let [s (/ theta (* 2.0 (Math/sin theta)))]
        [(* s (- m21 m12)) (* s (- m02 m20)) (* s (- m10 m01))]))))

(defn transform-point
  "Apply a pose {:rvec :t} to a world point -> camera-frame point."
  [{:keys [rvec t]} [x y z]]
  (let [[[a b c] [d e f] [g h i]] (rodrigues rvec)
        [tx ty tz] t]
    [(+ (* a x) (* b y) (* c z) tx)
     (+ (* d x) (* e y) (* f z) ty)
     (+ (* g x) (* h y) (* i z) tz)]))

;; ---------------------------------------------------------------------------
;; Projection

(defn distort
  "Apply radial distortion to normalised image coordinates."
  [{:keys [k1 k2] :or {k1 0.0 k2 0.0}} [xn yn]]
  (if (and (zero? k1) (zero? k2))
    [xn yn]
    (let [r2 (+ (* xn xn) (* yn yn))
          f (+ 1.0 (* k1 r2) (* k2 r2 r2))]
      [(* xn f) (* yn f)])))

(defn project
  "World point -> pixel coordinates. Returns nil for points at or behind
   the camera plane (a point that does not image cannot constrain anything;
   the caller drops it rather than letting a sign flip poison the solve)."
  [intrinsics pose p]
  (let [[xc yc zc] (transform-point pose p)]
    (when (> zc 1e-6)
      (let [{:keys [fx fy cx cy]} intrinsics
            [xn yn] (distort intrinsics [(/ xc zc) (/ yc zc)])]
        [(+ (* fx xn) cx) (+ (* fy yn) cy)]))))

(defn camera-center
  "World-space position of the camera centre: C = -R^T t."
  [{:keys [rvec t]}]
  (let [[[a b c] [d e f] [g h i]] (rodrigues rvec)
        [tx ty tz] t]
    ;; R^T * t, negated
    [(- (+ (* a tx) (* d ty) (* g tz)))
     (- (+ (* b tx) (* e ty) (* h tz)))
     (- (+ (* c tx) (* f ty) (* i tz)))]))

(defn look-at-pose
  "Build a pose from a camera centre looking at a target, with an up hint.
   Used to construct synthetic viewpoints and to seed a hand-aligned view."
  [eye target up]
  (let [sub (fn [[a b c] [d e f]] [(- a d) (- b e) (- c f)])
        crs (fn [[a b c] [d e f]] [(- (* b f) (* c e)) (- (* c d) (* a f)) (- (* a e) (* b d))])
        nrm (fn [[a b c]] (let [m (Math/sqrt (+ (* a a) (* b b) (* c c)))]
                            (if (< m 1e-12) [0.0 0.0 0.0] [(/ a m) (/ b m) (/ c m)])))
        ;; camera looks down +z in camera frame
        zc (nrm (sub target eye))
        xc (nrm (crs zc up))
        yc (crs zc xc)
        ;; rows of R are the camera axes expressed in world coords
        r [xc yc zc]
        rvec (rot-mat->rodrigues r)
        [[a b c] [d e f] [g h i]] r
        [ex ey ez] eye
        t [(- (+ (* a ex) (* b ey) (* c ez)))
           (- (+ (* d ex) (* e ey) (* f ez)))
           (- (+ (* g ex) (* h ey) (* i ez)))]]
    {:rvec rvec :t t}))

(defn intrinsics-from-fov
  "Build intrinsics from a horizontal field of view (degrees) and image size.
   The EXIF path gives focal length in 35mm-equivalent terms, which reduces
   to exactly this (accertamento 3)."
  [fov-deg width height]
  (let [f (/ (/ width 2.0) (Math/tan (/ (* (/ fov-deg 180.0) Math/PI) 2.0)))]
    {:fx f :fy f :cx (/ width 2.0) :cy (/ height 2.0) :k1 0.0 :k2 0.0}))
