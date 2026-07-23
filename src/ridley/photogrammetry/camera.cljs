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

(defn pixel-ray
  "Backprojection — the exact inverse of `project` for a pinhole (k1=k2=0, this
   session's intrinsics): the world-frame ray through pixel [u v]. Returns
   {:origin C :dir d} with C the camera centre and d a UNIT direction into the
   scene; a point C + s·d at any positive depth s projects back to [u v]. Used
   to turn a photo click into a 3D ray, which the caller intersects with a
   declared plane (ridley.math/ray-plane-point) to recover the traced point.

   Derivation: with Xc = R·Xw + t, a camera-frame direction maps to world by
   R^T; the camera looks down +z here, so the ray direction in camera coords
   for a pixel is [xn yn 1] (xn=(u-cx)/fx, yn=(v-cy)/fy) and the world direction
   is R^T·[xn yn 1]. Distortion is deliberately ignored — this session runs
   k1=k2=0 (see intrinsics-from-fov); a distorted lens would undistort [u v]
   first."
  [{:keys [fx fy cx cy]} pose [u v]]
  (let [xn (/ (- u cx) fx)
        yn (/ (- v cy) fy)
        [[a b c] [d e f] [g h i]] (rodrigues (:rvec pose))
        ;; world dir = R^T · [xn yn 1]  (R^T's rows are R's columns)
        dx (+ (* a xn) (* d yn) g)
        dy (+ (* b xn) (* e yn) h)
        dz (+ (* c xn) (* f yn) i)
        len (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))]
    {:origin (camera-center pose)
     :dir [(/ dx len) (/ dy len) (/ dz len)]}))

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

(defn focal-mm->fov-deg
  "Field of view (degrees) subtended by a `sensor-mm`-wide frame at a
   `focal-mm` focal length. A geometric primitive — the caller supplies the
   dimension it wants the angle for. NOTE: a 35mm-EQUIVALENT focal length is
   NOT defined against the 36mm width (that is only right for a 3:2 image);
   for a 35mm-equivalent focal use equiv-focal->hfov-deg, which accounts for
   the diagonal convention and the image's own aspect ratio."
  ([focal-mm] (focal-mm->fov-deg focal-mm 36.0))
  ([focal-mm sensor-mm]
   (* 2.0 (/ 180.0 Math/PI) (Math/atan (/ sensor-mm (* 2.0 focal-mm))))))

(def frame-35mm-diagonal-mm
  "Diagonal of a full 35mm frame (36×24 mm): sqrt(36²+24²). The
   '35mm-equivalent' focal length reported by cameras (EXIF
   FocalLengthIn35mmFilm) is defined against THIS diagonal — the crop factor
   is the diagonal ratio (the CIPA/ISO convention) — NOT against the 36mm
   width. Splitting by width silently assumes a 3:2 frame; a 4:3 phone photo
   needs the diagonal split by its OWN aspect ratio (equiv-focal->hfov-deg)."
  (Math/sqrt (+ (* 36.0 36.0) (* 24.0 24.0)))) ; ≈ 43.2666

(defn equiv-focal->hfov-deg
  "Horizontal field of view (degrees) from a 35mm-EQUIVALENT focal length and
   the image's aspect ratio `aspect` = width/height. The equivalent focal maps
   to the 43.27mm full-frame diagonal, so the horizontal half-angle is the
   diagonal half-angle scaled by width/diagonal = aspect/sqrt(aspect²+1). For
   a 3:2 image this reduces exactly to focal-mm->fov-deg with sensor-mm=36;
   for the iPhone's 4:3 it gives ~39.7° at 48mm-eq, not the 41.1° the
   width-based formula wrongly returns (a ~4% focal-scale error — nearly
   invisible in a single-photo fit, where it is absorbed into camera distance,
   but corrupting to the multi-photo turntable geometry and the initial per-
   photo seeds; see dev-docs/HANDOVER-edit-acquire-gate.md)."
  [focal-mm aspect]
  (let [w-over-diag (/ aspect (Math/sqrt (+ (* aspect aspect) 1.0)))
        eff-width (* frame-35mm-diagonal-mm w-over-diag)]
    (* 2.0 (/ 180.0 Math/PI) (Math/atan (/ eff-width (* 2.0 focal-mm))))))

(defn intrinsics-from-fov
  "Build intrinsics from a horizontal field of view (degrees) and image size.
   The EXIF path gives focal length in 35mm-equivalent terms, which reduces
   to exactly this (accertamento 3)."
  [fov-deg width height]
  (let [f (/ (/ width 2.0) (Math/tan (/ (* (/ fov-deg 180.0) Math/PI) 2.0)))]
    {:fx f :fy f :cx (/ width 2.0) :cy (/ height 2.0) :k1 0.0 :k2 0.0}))
