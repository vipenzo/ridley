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
;; Triangle–point closest-point (Ericson, Real-Time Collision Detection)
;; ============================================================

(defn- closest-point-on-triangle
  "Return the closest point on triangle (a,b,c) to point p.
   Uses Voronoi-region test — handles vertex, edge, and face regions."
  [p a b c]
  (let [ab (m/v- b a) ac (m/v- c a) ap (m/v- p a)
        d1 (m/dot ab ap) d2 (m/dot ac ap)]
    (if (and (<= d1 0) (<= d2 0))
      a ;; closest to vertex A
      (let [bp (m/v- p b)
            d3 (m/dot ab bp) d4 (m/dot ac bp)]
        (if (and (>= d3 0) (<= d4 d3))
          b ;; closest to vertex B
          (let [vc (- (* d1 d4) (* d3 d2))]
            (if (and (<= vc 0) (>= d1 0) (<= d3 0))
              (let [v (/ d1 (- d1 d3))]
                (m/v+ a (m/v* ab v))) ;; edge AB
              (let [cp (m/v- p c)
                    d5 (m/dot ab cp) d6 (m/dot ac cp)]
                (if (and (>= d6 0) (<= d5 d6))
                  c ;; closest to vertex C
                  (let [vb (- (* d5 d2) (* d1 d6))]
                    (if (and (<= vb 0) (>= d2 0) (<= d6 0))
                      (let [w (/ d2 (- d2 d6))]
                        (m/v+ a (m/v* ac w))) ;; edge AC
                      (let [va (- (* d3 d6) (* d5 d4))]
                        (if (and (<= va 0)
                                 (>= (- d4 d3) 0)
                                 (>= (- d5 d6) 0))
                          (let [w (/ (- d4 d3) (+ (- d4 d3) (- d5 d6)))]
                            (m/v+ b (m/v* (m/v- c b) w))) ;; edge BC
                          ;; inside face
                          (let [denom (/ 1.0 (+ va vb vc))
                                v (* vb denom)
                                w (* vc denom)]
                            (m/v+ a (m/v+ (m/v* ab v) (m/v* ac w)))))))))))))))))

;; ============================================================
;; Centroid subdivision
;; ============================================================

(defn- triangle-in-volume?
  "Check if a triangle intersects the volume.
   Tests: (1) any vertex inside volume, or (2) closest point on triangle
   to the volume center is inside volume (catches small volumes inside
   large triangles)."
  [vertices face center right up heading vol]
  (let [[i0 i1 i2] face
        v0 (nth vertices i0)
        v1 (nth vertices i1)
        v2 (nth vertices i2)
        l0 (world-to-local v0 center right up heading)
        l1 (world-to-local v1 center right up heading)
        l2 (world-to-local v2 center right up heading)]
    (or (point-in-volume-local? l0 vol)
        (point-in-volume-local? l1 vol)
        (point-in-volume-local? l2 vol)
        ;; Volume center inside a large triangle? Check closest surface point.
        (let [cp (closest-point-on-triangle center v0 v1 v2)]
          (point-in-volume-local?
            (world-to-local cp center right up heading) vol)))))

(defn- get-or-add-midpoint
  "Return midpoint vertex index for edge [vi0 vi1], creating it if needed.
   Returns [midpoint-index, updated-vertices, updated-edge-map]."
  [vi0 vi1 verts edge-map]
  (let [edge (if (< vi0 vi1) [vi0 vi1] [vi1 vi0])]
    (if-let [mid-idx (get edge-map edge)]
      [mid-idx verts edge-map]
      (let [v0 (nth verts vi0)
            v1 (nth verts vi1)
            mid [(/ (+ (nth v0 0) (nth v1 0)) 2.0)
                 (/ (+ (nth v0 1) (nth v1 1)) 2.0)
                 (/ (+ (nth v0 2) (nth v1 2)) 2.0)]
            idx (count verts)]
        [idx (conj verts mid) (assoc edge-map edge idx)]))))

(defn- subdivide-once
  "One pass of midpoint (1→4) subdivision on triangles inside the volume.
   Each affected triangle splits at edge midpoints into 4 well-shaped
   sub-triangles.  Shared edges reuse the same midpoint vertex.
   Triangles fully outside the volume are kept unchanged."
  [mesh vol]
  (let [{:keys [center right up heading]} vol
        vertices (vec (:vertices mesh))
        faces (:faces mesh)]
    (loop [remaining faces
           out-verts vertices
           out-faces []
           edge-map {}]
      (if (empty? remaining)
        (assoc mesh :vertices out-verts :faces out-faces)
        (let [face (first remaining)
              [i0 i1 i2] face]
          (if (triangle-in-volume? out-verts face center right up heading vol)
            (let [[m01 out-verts edge-map] (get-or-add-midpoint i0 i1 out-verts edge-map)
                  [m12 out-verts edge-map] (get-or-add-midpoint i1 i2 out-verts edge-map)
                  [m20 out-verts edge-map] (get-or-add-midpoint i2 i0 out-verts edge-map)]
              (recur (rest remaining)
                     out-verts
                     (into out-faces [[i0 m01 m20]
                                      [m01 i1 m12]
                                      [m12 i2 m20]
                                      [m01 m12 m20]])
                     edge-map))
            (recur (rest remaining)
                   out-verts
                   (conj out-faces face)
                   edge-map)))))))

(defn- subdivide-mesh
  "Apply n passes of midpoint subdivision inside the volume."
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
;; Vertex normals (crease-aware)
;; ============================================================

(defn- estimate-vertex-normals
  "Estimate per-vertex normals with crease detection.
   At sharp edges (face normals differ by > ~70°), only faces from the
   dominant smooth group contribute.  On smooth surfaces, all adjacent
   faces are averaged as before (area-weighted)."
  [vertices faces]
  (let [n (count vertices)
        ;; Pre-compute per-face unit normals and raw cross products
        face-data (mapv (fn [[i0 i1 i2]]
                          (let [v0 (nth vertices i0)
                                v1 (nth vertices i1)
                                v2 (nth vertices i2)
                                e1 (m/v- v1 v0)
                                e2 (m/v- v2 v0)
                                cross (m/cross e1 e2)
                                mag (m/magnitude cross)]
                            {:cross cross
                             :normal (if (pos? mag) (m/v* cross (/ 1.0 mag)) [0 0 0])
                             :area mag}))
                        faces)
        ;; Build per-vertex adjacency: vertex-index → [face-indices]
        vert-adj (reduce-kv
                   (fn [acc fi [i0 i1 i2]]
                     (-> acc
                         (update i0 (fnil conj []) fi)
                         (update i1 (fnil conj []) fi)
                         (update i2 (fnil conj []) fi)))
                   (vec (repeat n nil))
                   (vec faces))
        ;; cos(70°) ≈ 0.34 — faces above this angle are separate smooth groups
        threshold 0.34
        normals
        (mapv
          (fn [vi]
            (if-let [adj (nth vert-adj vi)]
              (if (<= (count adj) 1)
                ;; Single face — just use its normal
                (:normal (nth face-data (first adj)))
                ;; Seed: face with largest area
                (let [seed-fi (reduce (fn [best fi]
                                        (if (> (:area (nth face-data fi))
                                               (:area (nth face-data best)))
                                          fi best))
                                      (first adj) (rest adj))
                      seed-n (:normal (nth face-data seed-fi))
                      ;; Sum cross products of compatible faces only
                      sum (reduce
                            (fn [acc fi]
                              (let [{:keys [normal cross]} (nth face-data fi)]
                                (if (> (m/dot normal seed-n) threshold)
                                  (m/v+ acc cross)
                                  acc)))
                            [0 0 0]
                            adj)]
                  (m/normalize sum)))
              [0 0 0]))
          (range n))]
    normals))

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
        (-> mesh
            (dissoc :ridley.manifold.core/manifold-cache :ridley.manifold.core/raw-arrays)
            (assoc :vertices new-vertices))))))

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
