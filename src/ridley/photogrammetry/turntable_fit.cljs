(ns ridley.photogrammetry.turntable-fit
  "Proxy fit under the calibrated-turntable constraint (design doc, § 'Input
   opzionale: giradischi calibrato', level 1).

   The camera is fixed on a tripod; the part sits on a printed turntable and
   is photographed at KNOWN rotation angles. The consequence for the solver
   is structural, not incremental: instead of 6 free parameters per view,
   every view shares ONE camera pose (6) and ONE rotation axis (4), with the
   per-view angle supplied rather than estimated.

       free-pose   : 3 + 6N parameters   (N=12 -> 75)
       turntable   : 3 + 6 + 4 = 13      (N=12 -> 13, independent of N)

   The axis is parameterised minimally and WITHOUT gauge freedom: two angles
   for its direction, and two coordinates for the point of the axis closest
   to the origin (which lives in the plane perpendicular to the direction).
   Adding a redundant 3-vector for the point would re-introduce a null
   direction — the axis sliding along itself — and pollute exactly the
   eigenvalue analysis this prototype uses to detect degeneracy."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.lm :as lm]))

;; ---------------------------------------------------------------------------
;; Axis parameterisation

(defn axis-basis
  "Orthonormal frame {d, u, v} from the two direction angles."
  [phi psi]
  (let [sp (Math/sin psi) cp (Math/cos psi)
        sf (Math/sin phi) cf (Math/cos phi)
        d [(* sp cf) (* sp sf) cp]
        ;; any vector not parallel to d
        helper (if (< (Math/abs (nth d 2)) 0.9) [0.0 0.0 1.0] [1.0 0.0 0.0])
        crs (fn [[a b c] [e f g]] [(- (* b g) (* c f)) (- (* c e) (* a g)) (- (* a f) (* b e))])
        nrm (fn [[a b c]] (let [m (Math/sqrt (+ (* a a) (* b b) (* c c)))]
                            (if (< m 1e-12) [1.0 0.0 0.0] [(/ a m) (/ b m) (/ c m)])))
        u (nrm (crs d helper))
        v (crs d u)]
    [d u v]))

(defn axis-from-params
  "{:point :dir} from [phi psi a b]."
  [phi psi a b]
  (let [[d u v] (axis-basis phi psi)]
    {:dir d
     :point [(+ (* a (nth u 0)) (* b (nth v 0)))
             (+ (* a (nth u 1)) (* b (nth v 1)))
             (+ (* a (nth u 2)) (* b (nth v 2)))]}))

(defn rotate-about-axis
  "Rotate point p by angle (radians) about the line {:point :dir}."
  [{:keys [point dir]} angle p]
  (let [rel (mapv - p point)
        [kx ky kz] dir
        c (Math/cos angle) s (Math/sin angle)
        [x y z] rel
        ;; Rodrigues applied directly to the relative vector
        dotkv (+ (* kx x) (* ky y) (* kz z))
        crossk [(- (* ky z) (* kz y)) (- (* kz x) (* kx z)) (- (* kx y) (* ky x))]
        rot [(+ (* x c) (* (nth crossk 0) s) (* kx dotkv (- 1.0 c)))
             (+ (* y c) (* (nth crossk 1) s) (* ky dotkv (- 1.0 c)))
             (+ (* z c) (* (nth crossk 2) s) (* kz dotkv (- 1.0 c)))]]
    (mapv + point rot)))

;; ---------------------------------------------------------------------------
;; Parameter packing
;;
;; p = [w h d  rvec(3) t(3)  phi psi a b]   -- 13, independent of view count

(defn pack
  "Pack the 13 shared parameters, then the optional fillet radius, then n
   per-view angle corrections. The radius sits before the variable-length
   tail so unpacking never has to guess where it is."
  ([dims pose axis] (pack dims pose axis 0 nil))
  ([dims pose axis n-soft] (pack dims pose axis n-soft nil))
  ([dims pose {:keys [phi psi a b]} n-soft fillet]
   (vec (concat dims (:rvec pose) (:t pose) [phi psi a b]
                (bf/fillet-seq fillet)
                (repeat n-soft 0.0)))))

(defn unpack
  ([p] (unpack p 0))
  ([p n-fillet]
   (let [p (vec p)
         tail (+ 13 n-fillet)]
     {:dims (subvec p 0 3)
      :pose {:rvec (subvec p 3 6) :t (subvec p 6 9)}
      :axis-params {:phi (nth p 9) :psi (nth p 10) :a (nth p 11) :b (nth p 12)}
      :fillet (when (pos? n-fillet) (subvec p 13 tail))
      :angle-deltas (subvec p tail)})))

;; ---------------------------------------------------------------------------
;; Residuals

(def ^:private behind-camera-penalty 1000.0)

(defn make-residual-fn
  "observations : [{:view :edge :line}] where :view indexes `angles`
   angles       : known turntable angle (radians) per view
   intrinsics   : ONE intrinsics map (a single fixed camera)

   opts :angle-prior-sigma-deg — when set, the reported angles are treated as
   PRIORS rather than as truth: each view gets a free correction parameter,
   penalised by how far it strays from the reported value. This costs N extra
   parameters but keeps the shared-axis structure, and it is what makes the
   turntable safe to use with a plate whose indexing is imperfect. With the
   angles taken as hard truth, a plate error of even a quarter of a degree
   makes the constrained fit WORSE than not using the turntable at all."
  [observations angles intrinsics
   {:keys [sigma-px scale-constraint angle-prior-sigma-deg n-fillet]
    :or {sigma-px 1.0 n-fillet 0}}]
  (fn [p]
    (let [{:keys [dims pose axis-params angle-deltas fillet]} (unpack p n-fillet)
          {:keys [phi psi a b]} axis-params
          axis (axis-from-params phi psi a b)
          pts (bf/corners dims)
          eff-angles (if (seq angle-deltas)
                       (mapv + angles angle-deltas)
                       angles)
          ;; The camera is fixed and the OBJECT turns, so the fillet silhouette
          ;; has to be evaluated with the camera centre expressed in the
          ;; object's own frame at that angle — i.e. counter-rotated.
          cam-c (cam/camera-center pose)
          centers (when fillet
                    (mapv #(rotate-about-axis axis (- %) cam-c) eff-angles))
          ;; pre-rotate the corners once per view rather than per edge
          rotated (mapv (fn [theta] (mapv #(rotate-about-axis axis theta %) pts))
                        eff-angles)
          reproj (mapv (fn [{:keys [view edge line]}]
                         (let [[p3a p3b]
                               (if fillet
                                 ;; compute the silhouette in the object frame,
                                 ;; then rotate it into place for this view
                                 (let [theta (nth eff-angles view)]
                                   (mapv #(rotate-about-axis axis theta %)
                                         (bf/edge-silhouette-points
                                          dims (bf/radius-for-edge fillet edge)
                                          (nth centers view) edge)))
                                 (let [vp (nth rotated view)
                                       [ia ib] (nth bf/edges edge)]
                                   [(nth vp ia) (nth vp ib)]))
                               pa (cam/project intrinsics pose p3a)
                               pb (cam/project intrinsics pose p3b)]
                           (if (and pa pb)
                             [(/ (bf/point-line-distance line pa) sigma-px)
                              (/ (bf/point-line-distance line pb) sigma-px)]
                             [behind-camera-penalty behind-camera-penalty])))
                       observations)
          flat (vec (apply concat reproj))
          ;; angle priors: each correction is pulled back toward zero, i.e.
          ;; toward the angle the plate reported
          with-priors (if (and angle-prior-sigma-deg (seq angle-deltas))
                        (let [s (/ (* angle-prior-sigma-deg Math/PI) 180.0)]
                          (into flat (map #(/ % s) angle-deltas)))
                        flat)]
      (if scale-constraint
        (let [{:keys [axis value sigma]} scale-constraint]
          (conj with-priors (/ (- (nth dims axis) value) sigma)))
        with-priors))))

(defn pose-at-angle
  "The equivalent free pose for 'fixed camera, object rotated by theta about
   axis'. Composing the two lets the turntable scene be fed to the free-pose
   solver unchanged, so the two can be compared on identical observations."
  [pose axis theta]
  (let [{:keys [point dir]} axis
        r-a (cam/rodrigues (mapv #(* % theta) dir))
        p-a (mapv - point (la/mat*vec r-a point))
        r-p (cam/rodrigues (:rvec pose))
        r-i (la/mat*mat r-p r-a)
        t-i (mapv + (la/mat*vec r-p p-a) (:t pose))]
    {:rvec (cam/rot-mat->rodrigues r-i) :t t-i}))

(defn visible-edges-at
  "Edges of the box visible from the fixed camera when the turntable has
   rotated by `theta`. Equivalent to rotating the camera by -theta."
  [dims pose axis theta]
  (let [c (cam/camera-center pose)
        ;; bring the camera into the object frame at this rotation
        c' (rotate-about-axis axis (- theta) c)]
    (bf/visible-edges dims {:rvec [0.0 0.0 0.0]
                            :t (mapv - c')})))

(defn fit
  "opts :fillet-init — when given, the fillet radius becomes a fitted
   parameter starting from this value (and :fillet? is implied)."
  [observations angles intrinsics init-dims init-pose init-axis opts]
  (let [n-soft (if (:angle-prior-sigma-deg opts) (count angles) 0)
        fillet-init (:fillet-init opts)
        n-fillet (count (bf/fillet-seq fillet-init))
        opts (assoc opts :n-fillet n-fillet)
        rfn (make-residual-fn observations angles intrinsics opts)
        res (lm/solve rfn (pack init-dims init-pose init-axis n-soft fillet-init)
                      (:lm opts))]
    (merge res (unpack (:params res) n-fillet) {:residual-fn rfn})))

(defn spectrum [result]
  (lm/covariance-spectrum (:residual-fn result) (:params result)))
