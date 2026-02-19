(ns ridley.geometry.warp
  "Spatial deformation: modify mesh vertices within a bounding volume.

   (warp mesh volume deform-fn & more-deform-fns)

   The volume is any Ridley mesh (sphere, box, cyl, cone) positioned via attach.
   Its :primitive and :creation-pose determine the deformation zone shape.
   Deformation functions are applied sequentially to each vertex inside the zone."
  (:require [ridley.math :as m]))

;; ============================================================
;; World ↔ local coordinate transforms
;; ============================================================

(defn- world-to-local
  "Transform world point to volume-local coordinates.
   Local frame: X=right, Y=up (cyl/cone axis), Z=heading.
   Uses the volume's creation-pose as the orthonormal basis."
  [pos center right up heading]
  (let [d (m/v- pos center)]
    [(m/dot d right) (m/dot d up) (m/dot d heading)]))

;; ============================================================
;; Volume bounds computation
;; ============================================================

(defn- compute-volume-bounds
  "Extract geometry from a volume mesh for intersection and distance tests.
   All dimensions are computed in the volume's local frame (from creation-pose)
   so that rotated volumes work correctly."
  [volume-mesh]
  (let [pose (or (:creation-pose volume-mesh)
                 {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
        center (:position pose)
        heading (:heading pose)
        up (:up pose)
        ;; right = up × heading — matches apply-transform convention
        right (m/cross up heading)
        prim (or (:primitive volume-mesh) :box)
        verts (:vertices volume-mesh)
        ;; Transform all volume vertices to local frame
        local-verts (mapv #(world-to-local % center right up heading) verts)
        ys (mapv second local-verts)
        min-y (apply min ys)
        max-y (apply max ys)
        half-h (/ (- max-y min-y) 2.0)
        ;; Radial distance in local XZ plane
        radial-fn (fn [[lx _ lz]] (Math/sqrt (+ (* lx lx) (* lz lz))))
        ;; AABB half-extents in local space
        xs (mapv first local-verts)
        zs (mapv #(nth % 2) local-verts)
        half-ext [(/ (- (apply max xs) (apply min xs)) 2.0)
                  half-h
                  (/ (- (apply max zs) (apply min zs)) 2.0)]
        ;; Primitive-specific dimensions
        height (* 2.0 half-h)
        radius (case prim
                 :sphere (apply max (map #(m/magnitude (m/v- % center)) verts))
                 (:cylinder :cone) (apply max (map radial-fn local-verts))
                 nil)
        ;; Cone: bottom/top radii (vertices near min-y / max-y)
        cone-radii
        (when (= prim :cone)
          (let [tol (max (* 0.02 height) 0.001)
                bottom-r (apply max 0.0
                           (keep #(when (< (Math/abs (- (second %) min-y)) tol)
                                    (radial-fn %))
                                 local-verts))
                top-r (apply max 0.0
                        (keep #(when (< (Math/abs (- (second %) max-y)) tol)
                                 (radial-fn %))
                              local-verts))]
            {:bottom-radius bottom-r :top-radius top-r}))]
    (merge
      {:center center
       :heading heading
       :up up
       :right right
       :half-ext half-ext
       :primitive prim
       :height height
       :radius radius}
      cone-radii)))

;; ============================================================
;; Volume intersection (local space)
;; ============================================================

(defn- point-in-volume-local?
  "Check if a local-space point falls inside the volume shape."
  [[lx ly lz] {:keys [primitive radius height half-ext bottom-radius top-radius]}]
  (let [half-h (/ height 2.0)
        radial (Math/sqrt (+ (* lx lx) (* lz lz)))]
    (case primitive
      :sphere
      (<= (+ (* lx lx) (* ly ly) (* lz lz)) (* radius radius))

      :cylinder
      (and (<= (Math/abs ly) half-h)
           (<= radial radius))

      :cone
      (let [t (if (pos? height) (/ (+ ly half-h) height) 0.5)
            r-at-t (+ bottom-radius (* t (- top-radius bottom-radius)))]
        (and (<= (Math/abs ly) half-h)
             (<= radial r-at-t)))

      ;; Default: axis-aligned bounding box
      (let [[hx hy hz] half-ext]
        (and (<= (Math/abs lx) hx)
             (<= (Math/abs ly) hy)
             (<= (Math/abs lz) hz))))))

;; ============================================================
;; Centroid subdivision
;; ============================================================

(defn- triangle-in-volume?
  "Check if any vertex of a triangle falls inside the volume."
  [vertices face center right up heading vol]
  (let [[i0 i1 i2] face]
    (or (point-in-volume-local?
          (world-to-local (nth vertices i0) center right up heading) vol)
        (point-in-volume-local?
          (world-to-local (nth vertices i1) center right up heading) vol)
        (point-in-volume-local?
          (world-to-local (nth vertices i2) center right up heading) vol))))

(defn- subdivide-once
  "One pass of centroid subdivision on triangles inside the volume.
   Each affected triangle (1→3): add centroid, replace with 3 sub-triangles.
   Triangles fully outside the volume are kept unchanged."
  [mesh vol]
  (let [{:keys [center right up heading]} vol
        vertices (vec (:vertices mesh))
        faces (:faces mesh)]
    (loop [remaining faces
           out-vertices vertices
           out-faces []]
      (if (empty? remaining)
        (assoc mesh :vertices out-vertices :faces out-faces)
        (let [face (first remaining)
              [i0 i1 i2] face]
          (if (triangle-in-volume? out-vertices face center right up heading vol)
            (let [v0 (nth out-vertices i0)
                  v1 (nth out-vertices i1)
                  v2 (nth out-vertices i2)
                  cx (/ (+ (nth v0 0) (nth v1 0) (nth v2 0)) 3.0)
                  cy (/ (+ (nth v0 1) (nth v1 1) (nth v2 1)) 3.0)
                  cz (/ (+ (nth v0 2) (nth v1 2) (nth v2 2)) 3.0)
                  ic (count out-vertices)]
              (recur (rest remaining)
                     (conj out-vertices [cx cy cz])
                     (into out-faces [[i0 i1 ic] [i1 i2 ic] [i2 i0 ic]])))
            (recur (rest remaining)
                   out-vertices
                   (conj out-faces face))))))))

(defn- subdivide-mesh
  "Apply n passes of centroid subdivision inside the volume."
  [mesh vol n]
  (loop [m mesh, i 0]
    (if (>= i n)
      m
      (recur (subdivide-once m vol) (inc i)))))

;; ============================================================
;; Normalized distance (0 = center, 1 = boundary)
;; ============================================================

(defn- compute-dist-local
  "Normalized distance from volume center.
   0 at center, 1 at boundary. Clamped to [0, 1]."
  [[lx ly lz] {:keys [primitive radius height half-ext bottom-radius top-radius]}]
  (let [half-h (/ height 2.0)]
    (min 1.0
      (case primitive
        :sphere
        (let [d (Math/sqrt (+ (* lx lx) (* ly ly) (* lz lz)))]
          (if (pos? radius) (/ d radius) 0.0))

        :cylinder
        (let [radial (Math/sqrt (+ (* lx lx) (* lz lz)))
              rdist (if (pos? radius) (/ radial radius) 0.0)
              hdist (if (pos? half-h) (/ (Math/abs ly) half-h) 0.0)]
          (max rdist hdist))

        :cone
        (let [t (if (pos? height) (/ (+ ly half-h) height) 0.5)
              r-at-t (+ bottom-radius (* t (- top-radius bottom-radius)))
              radial (Math/sqrt (+ (* lx lx) (* lz lz)))
              rdist (if (pos? r-at-t) (/ radial r-at-t) 0.0)
              hdist (if (pos? half-h) (/ (Math/abs ly) half-h) 0.0)]
          (max rdist hdist))

        ;; Default: max-norm (Chebyshev distance)
        (let [[hx hy hz] half-ext]
          (max (if (pos? hx) (/ (Math/abs lx) hx) 0.0)
               (if (pos? hy) (/ (Math/abs ly) hy) 0.0)
               (if (pos? hz) (/ (Math/abs lz) hz) 0.0)))))))

;; ============================================================
;; Normalized local position [-1, 1] per axis
;; ============================================================

(defn- compute-local-pos
  "Normalized position within volume: each axis in [-1, 1].
   Useful for deform functions that need to know where in the
   volume the point lies (e.g. twist uses Y for axis position)."
  [[lx ly lz] {:keys [half-ext]}]
  (let [[hx hy hz] half-ext]
    [(if (pos? hx) (/ lx hx) 0.0)
     (if (pos? hy) (/ ly hy) 0.0)
     (if (pos? hz) (/ lz hz) 0.0)]))

;; ============================================================
;; Vertex normals
;; ============================================================

(defn- estimate-vertex-normals
  "Estimate per-vertex normals by averaging adjacent face normals.
   Area-weighted (larger faces contribute more)."
  [vertices faces]
  (let [n (count vertices)
        normals (reduce
                  (fn [acc [i0 i1 i2]]
                    (let [v0 (nth vertices i0)
                          v1 (nth vertices i1)
                          v2 (nth vertices i2)
                          e1 (m/v- v1 v0)
                          e2 (m/v- v2 v0)
                          face-n (m/cross e1 e2)]
                      (-> acc
                          (update i0 #(m/v+ % face-n))
                          (update i1 #(m/v+ % face-n))
                          (update i2 #(m/v+ % face-n)))))
                  (vec (repeat n [0 0 0]))
                  faces)]
    (mapv m/normalize normals)))

;; ============================================================
;; Falloff
;; ============================================================

(defn ^:export smooth-falloff
  "Hermite smooth falloff: 1 at center (dist=0), 0 at boundary (dist=1).
   Smooth step: 3t² - 2t³ where t = 1 - dist."
  [dist]
  (let [t (- 1.0 (max 0.0 (min 1.0 dist)))]
    (* t t (- 3.0 (* 2.0 t)))))

;; ============================================================
;; Core warp
;; ============================================================

(defn ^:export warp
  "Deform mesh vertices inside volume using deform-fn(s).

   Each deform-fn: (fn [pos local-pos dist normal vol-bounds] -> new-pos)
     pos       — world position [x y z]
     local-pos — normalized position in volume [-1,1] per axis
     dist      — normalized distance from center (0=center, 1=boundary)
     normal    — estimated vertex normal [nx ny nz]
     vol       — volume bounds map (keys: :center :up :heading :right
                 :half-ext :primitive :radius :height etc.)

   Multiple deform-fns are applied sequentially to each vertex.

   Options (keyword args mixed with deform-fns):
     :subdivide n — centroid-subdivide triangles inside volume n times
                    before deforming (each pass: 1 triangle → 3).

   Returns new mesh with modified vertices."
  [mesh volume & args]
  (let [{:keys [subdivide deform-fns]}
        (loop [remaining args, opts {}, fns []]
          (if (empty? remaining)
            (assoc opts :deform-fns fns)
            (if (= :subdivide (first remaining))
              (recur (drop 2 remaining)
                     (assoc opts :subdivide (second remaining))
                     fns)
              (recur (rest remaining)
                     opts
                     (conj fns (first remaining))))))]
    (if (empty? deform-fns)
      mesh
      (let [vol (compute-volume-bounds volume)
            mesh (if subdivide
                   (-> (subdivide-mesh mesh vol subdivide)
                       (dissoc :face-groups))
                   mesh)
            {:keys [center right up heading]} vol
            vertices (:vertices mesh)
            faces (:faces mesh)
            normals (estimate-vertex-normals vertices faces)
            new-vertices
            (vec (map-indexed
                   (fn [i pos]
                     (let [local (world-to-local pos center right up heading)]
                       (if (point-in-volume-local? local vol)
                         (let [local-pos (compute-local-pos local vol)
                               dist (compute-dist-local local vol)
                               normal (nth normals i)]
                           (reduce
                             (fn [p dfn] (dfn p local-pos dist normal vol))
                             pos
                             deform-fns))
                         pos)))
                   vertices))]
        (assoc mesh :vertices new-vertices)))))

;; ============================================================
;; Preset deformation functions
;; ============================================================

(defn ^:export inflate
  "Push vertices outward along normals.
   amount: displacement in world units at center (falls off toward boundary)."
  [amount]
  (fn [pos _local-pos dist normal _vol]
    (let [f (smooth-falloff dist)]
      (m/v+ pos (m/v* normal (* amount f))))))

(defn ^:export dent
  "Push vertices inward. Opposite of inflate."
  [amount]
  (inflate (- amount)))

(defn ^:export attract
  "Pull vertices toward volume center (sphere/box) or axis (cyl/cone).
   strength: 0 = no effect, 1 = fully pulled to center/axis."
  [strength]
  (fn [pos _local-pos dist _normal {:keys [primitive center up]}]
    (let [f (smooth-falloff dist)
          target (case primitive
                   ;; Cylinder/cone: project pos onto axis (radial squeeze)
                   (:cylinder :cone)
                   (let [d (m/v- pos center)
                         along (m/dot d up)]
                     (m/v+ center (m/v* up along)))
                   ;; Sphere/box: pull toward center point
                   center)]
      (m/v+ pos (m/v* (m/v- target pos) (* strength f))))))

(defn ^:export twist
  "Rotate vertices around an axis. Angle varies with position along axis.
   angle: max rotation in degrees at the extremes.
   axis: :x :y :z (explicit world axis) or nil (auto from cyl/cone volume).

   For cylinder/cone volumes, auto-detects the axis. For sphere/box, you
   must specify an explicit axis."
  ([angle] (twist angle nil))
  ([angle explicit-axis]
   (fn [pos _local-pos dist _normal {:keys [primitive center up height]}]
     (let [f (smooth-falloff dist)
           axis-dir (if explicit-axis
                      (case explicit-axis :x [1 0 0] :y [0 1 0] :z [0 0 1])
                      (case primitive
                        (:cylinder :cone) up
                        (throw (js/Error. "twist: specify axis for box/sphere volumes"))))
           ;; Position along axis: -1 to 1
           d (m/v- pos center)
           along (m/dot d axis-dir)
           half-h (/ height 2.0)
           t (if (pos? half-h)
               (max -1.0 (min 1.0 (/ along half-h)))
               0.0)
           ;; Rotation angle at this point
           rotation-rad (* angle f t (/ Math/PI 180.0))
           ;; Rotate around axis through volume center
           offset (m/v- pos center)
           rotated (m/rotate-point-around-axis offset axis-dir rotation-rad)]
       (m/v+ center rotated)))))

(defn ^:export squash
  "Flatten vertices toward a plane through volume center.
   axis: :x :y :z — world axis to flatten along.
   amount: 0 = fully flat (default), 1 = no effect."
  ([axis] (squash axis 0))
  ([axis amount]
   (fn [pos _local-pos dist _normal {:keys [center]}]
     (let [f (smooth-falloff dist)
           axis-idx (case axis :x 0 :y 1 :z 2)
           center-val (nth center axis-idx)
           current-val (nth pos axis-idx)
           ;; Target: blend toward center plane by (1 - amount)
           target-val (+ center-val (* amount (- current-val center-val)))
           new-val (+ current-val (* f (- target-val current-val)))]
       (assoc pos axis-idx new-val)))))

;; ============================================================
;; Noise helpers
;; ============================================================

(defn- hash-3d
  "Deterministic pseudo-random hash from 3D position. Returns [-1, 1]."
  [x y z]
  (let [n (* (Math/sin (+ (* x 127.1) (* y 311.7) (* z 74.7))) 43758.5453)]
    (- (* 2.0 (- n (Math/floor n))) 1.0)))

(defn ^:export roughen
  "Displace vertices along normals by deterministic noise.
   amplitude: max displacement in world units.
   frequency: spatial frequency (default 1). Higher = more detail."
  ([amplitude] (roughen amplitude 1))
  ([amplitude frequency]
   (fn [pos _local-pos dist normal _vol]
     (let [f (smooth-falloff dist)
           [px py pz] pos
           n (hash-3d (* px frequency) (* py frequency) (* pz frequency))]
       (m/v+ pos (m/v* normal (* amplitude f n)))))))
