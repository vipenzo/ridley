(ns ridley.turtle.shape-fn
  "Shape functions: shapes that vary along the extrusion path.

   A shape-fn is a function (fn [t] -> shape) with metadata {:type :shape-fn}.
   At each point along a loft path, t goes from 0 to 1, and the shape-fn
   returns the appropriate 2D shape for that position.

   Built-in shape-fns compose with Clojure's -> threading:
     (-> (circle 20) (fluted :flutes 12 :depth 2) (tapered :to 0))

   Use with loft:
     (loft (tapered (circle 20) :to 0) (f 30))"
  (:require [ridley.turtle.transform :as xform]
            [ridley.turtle.shape :as shape]))

;; ============================================================
;; Path-length context (set by loft at runtime)
;; ============================================================

(def ^:dynamic *path-length*
  "Total path length in world units, bound by loft during shape-fn evaluation.
   Used by capped to auto-calculate transition fraction."
  nil)

;; ============================================================
;; 2D vector math (private)
;; ============================================================

(defn- v2-sub [[x1 y1] [x2 y2]] [(- x1 x2) (- y1 y2)])
(defn- v2-add [[x1 y1] [x2 y2]] [(+ x1 x2) (+ y1 y2)])
(defn- v2-scale [[x y] s] [(* x s) (* y s)])

(defn- v2-mag [[x y]]
  (Math/sqrt (+ (* x x) (* y y))))

(defn- v2-normalize [[x y]]
  (let [m (v2-mag [x y])]
    (if (< m 0.0001) [0 0] [(/ x m) (/ y m)])))

(defn- shape-centroid [shape]
  (let [pts (:points shape)
        n (count pts)]
    (if (zero? n)
      [0 0]
      [(/ (reduce + (map first pts)) n)
       (/ (reduce + (map second pts)) n)])))

;; ============================================================
;; Core: shape-fn predicate and constructor
;; ============================================================

(defn ^:export shape-fn?
  "Returns true if x is a shape-fn (a function with :type :shape-fn metadata)."
  [x]
  (and (fn? x) (= :shape-fn (:type (meta x)))))

(defn ^:export shape-fn
  "Create a shape-fn from a base shape (or shape-fn) and a transform function.
   transform: (fn [shape t] -> shape) where t in [0, 1].
   Returns a callable (fn [t] -> shape) with :shape-fn metadata."
  [base transform]
  (let [evaluate (if (shape-fn? base)
                   (fn [t] (transform (base t) t))
                   (fn [t] (transform base t)))]
    (with-meta evaluate
      {:type :shape-fn
       :base base
       :point-count (if (shape-fn? base)
                      (:point-count (meta base))
                      (count (:points base)))})))

;; ============================================================
;; Helpers
;; ============================================================

(defn ^:export angle
  "Returns the angle (radians) of a 2D point relative to the origin.
   Useful in displacement functions for angular patterns."
  [p]
  (Math/atan2 (second p) (first p)))

(defn ^:export displace-radial
  "Displace each point of a shape radially from its centroid.
   offset-fn: (fn [point] -> number) returns the radial offset for each point."
  [shape offset-fn]
  (let [center (shape-centroid shape)
        displace-point (fn [p]
                         (let [dir (v2-normalize (v2-sub p center))
                               offset (offset-fn p)]
                           (v2-add p (v2-scale dir offset))))]
    (cond-> (update shape :points (fn [pts] (mapv displace-point pts)))
      (:holes shape)
      (update :holes (fn [holes] (mapv (fn [hole] (mapv displace-point hole)) holes))))))

;; ============================================================
;; Built-in shape-fns
;; ============================================================

(defn ^:export tapered
  "Scale a shape from :from (default 1) to :to (default 0) along the path.
   (tapered (circle 20) :to 0)       ;; cone
   (tapered (circle 20) :from 0.5 :to 1)  ;; expand"
  [shape-or-fn & {:keys [to from] :or {to 0 from 1}}]
  (shape-fn shape-or-fn
            (fn [s t]
              (xform/scale s (+ from (* t (- to from)))))))

(defn ^:export twisted
  "Rotate a shape progressively along the path.
   At t=0 rotation is 0, at t=1 rotation is :angle degrees.
   (twisted (rect 20 10) :angle 90)"
  [shape-or-fn & {:keys [angle] :or {angle 360}}]
  (shape-fn shape-or-fn
            (fn [s t]
              (xform/rotate s (* t angle)))))

(defn ^:export rugged
  "Displace vertices radially with a sin pattern (constant along path).
   (rugged (circle 15) :amplitude 2 :frequency 8)"
  [shape-or-fn & {:keys [amplitude frequency] :or {amplitude 1 frequency 6}}]
  (shape-fn shape-or-fn
            (fn [s _t]
              (displace-radial s (fn [p]
                                   (* amplitude (Math/sin (* (angle p) frequency))))))))

(defn ^:export fluted
  "Longitudinal grooves using cos pattern (aligned with shape axes).
   (fluted (circle 20) :flutes 12 :depth 2)"
  [shape-or-fn & {:keys [flutes depth] :or {flutes 6 depth 1}}]
  (shape-fn shape-or-fn
            (fn [s _t]
              (displace-radial s (fn [p]
                                   (* depth (Math/cos (* (angle p) flutes))))))))

(defn ^:export displaced
  "Custom per-vertex radial displacement.
   displace-fn: (fn [point t] -> number) returns radial offset.
   (displaced (circle 15 64) (fn [p t] (* 2 (sin (+ (* (angle p) 6) (* t 20))))))"
  [shape-or-fn displace-fn]
  (shape-fn shape-or-fn
            (fn [s t]
              (displace-radial s (fn [p] (displace-fn p t))))))

(defn ^:export morphed
  "Interpolate between two shapes along the path.
   At t=0 returns shape-a, at t=1 returns shape-b.
   Both must have the same point count (use resample if needed).
   (morphed (resample (star 5 20 8) 32) (circle 15 32))"
  [shape-a shape-b]
  (shape-fn shape-a
            (fn [s t]
              (xform/morph s shape-b t))))

;; ============================================================
;; Procedural noise
;; ============================================================

(defn- hash-noise
  "Deterministic pseudorandom value for integer grid coordinates.
   Returns a value in [-1, 1]."
  [x y]
  (let [n (* (Math/sin (+ (* x 127.1) (* y 311.7))) 43758.5453)]
    (- (* 2 (- n (Math/floor n))) 1)))

(defn ^:export noise
  "2D deterministic continuous noise. Returns value in approximately [-1, 1].
   Same inputs always produce the same output. Nearby inputs produce nearby outputs."
  [x y]
  (let [ix (Math/floor x) iy (Math/floor y)
        fx (- x ix)  fy (- y iy)
        ;; Hermite smoothstep for C1 continuity
        sx (* fx fx (- 3 (* 2 fx)))
        sy (* fy fy (- 3 (* 2 fy)))
        ;; Four corner values
        n00 (hash-noise ix iy)
        n10 (hash-noise (inc ix) iy)
        n01 (hash-noise ix (inc iy))
        n11 (hash-noise (inc ix) (inc iy))]
    (+ (* n00 (- 1 sx) (- 1 sy))
       (* n10 sx (- 1 sy))
       (* n01 (- 1 sx) sy)
       (* n11 sx sy))))

(defn ^:export fbm
  "Fractal Brownian Motion — layered noise for natural-looking surfaces.
   octaves: number of layers (default 4, more = more detail)
   lacunarity: frequency multiplier per octave (default 2.0)
   gain: amplitude multiplier per octave (default 0.5)"
  ([x y] (fbm x y 4))
  ([x y octaves] (fbm x y octaves 2.0 0.5))
  ([x y octaves lacunarity gain]
   (loop [i 0 freq 1.0 amp 1.0 total 0.0 max-amp 0.0]
     (if (>= i octaves)
       (/ total max-amp)
       (recur (inc i)
              (* freq lacunarity)
              (* amp gain)
              (+ total (* amp (noise (* x freq) (* y freq))))
              (+ max-amp amp))))))

;; ============================================================
;; Procedural displacement shape-fns
;; ============================================================

(defn ^:export noisy
  "Noise-based displacement shape-fn.
   (noisy (circle 15 64) :amplitude 1.5 :scale 3)
   (noisy (circle 15 64) :amplitude 2 :scale 3 :octaves 4)
   (noisy (circle 15 64) :amplitude 1 :scale-x 8 :scale-y 3 :seed 42)"
  [shape-or-fn & {:keys [amplitude scale scale-x scale-y octaves seed]
                  :or {amplitude 1.0 scale 3.0 octaves 1 seed 0}}]
  (let [sx (or scale-x scale)
        sy (or scale-y scale)]
    (displaced shape-or-fn
               (fn [p t]
                 (let [a (angle p)
                       nx (+ (* a sx) seed)
                       ny (+ (* t sy) seed)]
                   (* amplitude (if (= octaves 1)
                                  (noise nx ny)
                                  (fbm nx ny octaves))))))))

(defn ^:export woven
  "Woven fabric displacement — interlocking over/under thread pattern.
   (woven (circle 18 96) :warp 6 :weft 4 :amplitude 1.5)
   (woven (circle 18 96) :warp 8 :weft 6 :amplitude 1 :thread 0.4)"
  [shape-or-fn & {:keys [warp weft amplitude thread]
                  :or {warp 6 weft 4 amplitude 1.0 thread 0.42}}]
  (displaced shape-or-fn
             (fn [p t]
               (let [a (angle p)
            ;; Map to repeating cell coordinates
                     u (* (/ (+ a Math/PI) (* 2 Math/PI)) warp)
                     v (* t weft)
            ;; Fractional position within cell [0,1)
                     fu (- u (Math/floor u))
                     fv (- v (Math/floor v))
            ;; Distance from thread center (threads run through cell center)
                     warp-d (Math/abs (- fv 0.5))
                     weft-d (Math/abs (- fu 0.5))
            ;; Raised-cosine thread profile: smooth round cross-section
                     prof (fn [d]
                            (if (< d thread)
                              (* 0.5 (+ 1 (Math/cos (* (/ d thread) Math/PI))))
                              0))
                     warp-h (prof warp-d)
                     weft-h (prof weft-d)
            ;; Checkerboard: determines which thread is on top
                     iu (int (Math/floor u))
                     iv (int (Math/floor v))
                     warp-top? (zero? (mod (+ iu iv) 2))
            ;; "Over" thread at full height, "under" at reduced height
                     under 0.15]
                 (* amplitude
                    (max (if warp-top? warp-h (* under warp-h))
                         (if warp-top? (* under weft-h) weft-h)))))))

;; ============================================================
;; Analytical heightmap generators
;; ============================================================

(defn ^:export weave-heightmap
  "Generate a weave pattern heightmap analytically (no mesh needed).
   Returns a heightmap struct usable with (heightmap shape hm ...).
   Much faster than building tube meshes + rasterizing.

   :threads    - threads per direction in one tile (default 4, must be even for tiling)
   :spacing    - center-to-center thread distance (default 5)
   :radius     - thread radius (default 2)
   :lift       - over/under amplitude (default: same as radius)
   :resolution - heightmap grid size (default 128)
   :profile    - :round or :flat (default :round)
   :thickness  - for :flat profile, ribbon thickness (default: radius * 0.5)"
  [& {:keys [threads spacing radius lift resolution profile thickness]
      :or {threads 4, spacing 5, radius 2, resolution 128, profile :round}}]
  (let [lift (or lift radius)
        thickness (or thickness (* radius 0.5))
        size (* threads spacing)
        w resolution
        h resolution
        n (* w h)
        data (js/Float32Array. n)
        pi Math/PI
        inv-s (/ 1.0 spacing)
        ;; Background Z (below all threads)
        z-floor (- 0 lift radius)
        ;; Profile: distance from center → Z above centerline
        prof (if (= profile :flat)
               (fn [d] (if (< d radius) (* 0.5 thickness) -1e10))
               (fn [d] (if (< d radius)
                         (Math/sqrt (- (* radius radius) (* d d)))
                         -1e10)))]
    (dotimes [iy h]
      (let [y (* (/ (+ iy 0.5) h) size)]
        (dotimes [ix w]
          (let [x (* (/ (+ ix 0.5) w) size)
                ;; Nearest warp thread (runs along X, spaced in Y)
                wi (int (Math/round (* y inv-s)))
                dy (Math/abs (- y (* wi spacing)))
                ;; Nearest weft thread (runs along Y, spaced in X)
                wj (int (Math/round (* x inv-s)))
                dx (Math/abs (- x (* wj spacing)))
                ;; Warp centerline Z: cos gives smooth over/under
                ;; At crossing (wi,wj): (-1)^wi * cos(πj) = (-1)^(wi+j)
                ;; Positive = warp on top, negative = warp below
                warp-z (when (< dy radius)
                         (+ (* lift
                               (if (even? wi) 1.0 -1.0)
                               (Math/cos (* pi x inv-s)))
                            (prof dy)))
                ;; Weft centerline Z: opposite phase from warp
                weft-z (when (< dx radius)
                         (+ (* lift
                               (if (odd? wj) 1.0 -1.0)
                               (Math/cos (* pi y inv-s)))
                            (prof dx)))
                z (cond
                    (and warp-z weft-z) (Math/max warp-z weft-z)
                    warp-z warp-z
                    weft-z weft-z
                    :else z-floor)]
            (aset data (+ ix (* iy w)) z)))))
    ;; Normalize to [0,1]
    (let [z-min (loop [i 0 m js/Number.POSITIVE_INFINITY]
                  (if (>= i n) m
                      (recur (inc i) (Math/min m (aget data i)))))
          z-max (loop [i 0 m js/Number.NEGATIVE_INFINITY]
                  (if (>= i n) m
                      (recur (inc i) (Math/max m (aget data i)))))
          z-range (- z-max z-min)]
      (when (> z-range 0)
        (dotimes [i n]
          (aset data i (/ (- (aget data i) z-min) z-range))))
      {:type :heightmap
       :data data
       :width w :height h
       :bounds [0 0 size size]
       :z-min z-min :z-max z-max})))

;; ============================================================
;; Heightmap displacement
;; ============================================================

(defn- mesh-xy-bounds
  "Compute XY axis-aligned bounding box of mesh vertices.
   Returns [x-min y-min x-max y-max]."
  [verts]
  (reduce (fn [[xn yn xx yx] v]
            [(min xn (nth v 0)) (min yn (nth v 1))
             (max xx (nth v 0)) (max yx (nth v 1))])
          [js/Number.POSITIVE_INFINITY js/Number.POSITIVE_INFINITY
           js/Number.NEGATIVE_INFINITY js/Number.NEGATIVE_INFINITY]
          verts))

(defn ^:export mesh-bounds
  "Return the 3D axis-aligned bounding box of a mesh.
   Returns {:min [x y z] :max [x y z] :center [cx cy cz] :size [sx sy sz]}."
  [mesh]
  (let [verts (:vertices mesh)]
    (when (seq verts)
      (let [[min-x min-y min-z max-x max-y max-z]
            (reduce (fn [[xn yn zn xx yx zx] v]
                      [(min xn (nth v 0)) (min yn (nth v 1)) (min zn (nth v 2))
                       (max xx (nth v 0)) (max yx (nth v 1)) (max zx (nth v 2))])
                    [js/Number.POSITIVE_INFINITY js/Number.POSITIVE_INFINITY js/Number.POSITIVE_INFINITY
                     js/Number.NEGATIVE_INFINITY js/Number.NEGATIVE_INFINITY js/Number.NEGATIVE_INFINITY]
                    verts)]
        {:min [min-x min-y min-z]
         :max [max-x max-y max-z]
         :center [(/ (+ min-x max-x) 2) (/ (+ min-y max-y) 2) (/ (+ min-z max-z) 2)]
         :size [(- max-x min-x) (- max-y min-y) (- max-z min-z)]}))))

(defn- barycentric
  "Barycentric coordinates of point (px, py) in triangle v0-v1-v2.
   Returns [u v w] where u + v + w = 1. Negative values mean outside."
  [px py v0 v1 v2]
  (let [x0 (nth v0 0) y0 (nth v0 1)
        x1 (nth v1 0) y1 (nth v1 1)
        x2 (nth v2 0) y2 (nth v2 1)
        det (+ (* (- y1 y2) (- x0 x2)) (* (- x2 x1) (- y0 y2)))]
    (if (zero? det)
      [-1 -1 -1]
      (let [u (/ (+ (* (- y1 y2) (- px x2)) (* (- x2 x1) (- py y2))) det)
            v (/ (+ (* (- y2 y0) (- px x2)) (* (- x0 x2) (- py y2))) det)]
        [u v (- 1 u v)]))))

(defn- rasterize-triangle!
  "Rasterize a single triangle onto the z-buffer grid, keeping max z."
  [data grid-w grid-h x-min y-min sx sy v0 v1 v2]
  (let [gx0 (max 0 (int (Math/floor (/ (- (min (nth v0 0) (nth v1 0) (nth v2 0)) x-min) sx))))
        gy0 (max 0 (int (Math/floor (/ (- (min (nth v0 1) (nth v1 1) (nth v2 1)) y-min) sy))))
        gx1 (min (dec grid-w) (int (Math/floor (/ (- (max (nth v0 0) (nth v1 0) (nth v2 0)) x-min) sx))))
        gy1 (min (dec grid-h) (int (Math/floor (/ (- (max (nth v0 1) (nth v1 1) (nth v2 1)) y-min) sy))))]
    (doseq [gy (range gy0 (inc gy1))
            gx (range gx0 (inc gx1))]
      (let [px (+ x-min (* (+ gx 0.5) sx))
            py (+ y-min (* (+ gy 0.5) sy))
            [bu bv bw] (barycentric px py v0 v1 v2)]
        (when (and (>= bu 0) (>= bv 0) (>= bw 0))
          (let [z (+ (* bu (nth v0 2)) (* bv (nth v1 2)) (* bw (nth v2 2)))
                idx (+ gx (* gy grid-w))]
            (when (> z (aget data idx))
              (aset data idx z))))))))

(defn ^:export mesh-to-heightmap
  "Convert a mesh to a heightmap by rasterizing max-z onto a 2D grid.
   (mesh-to-heightmap mesh :resolution 128)
   (mesh-to-heightmap mesh :resolution 128 :bounds [x0 y0 x1 y1])
   (mesh-to-heightmap mesh :resolution 128 :offset-x 0 :offset-y 0 :length-x 10 :length-y 10)"
  [mesh & {:keys [resolution bounds offset-x offset-y length-x length-y]
           :or {resolution 128}}]
  (let [verts (:vertices mesh)
        faces (:faces mesh)
        auto-bounds (mesh-xy-bounds verts)
        [x-min y-min x-max y-max]
        (cond
          bounds bounds
          (or offset-x offset-y length-x length-y)
          (let [ox (or offset-x (nth auto-bounds 0))
                oy (or offset-y (nth auto-bounds 1))
                lx (or length-x (- (nth auto-bounds 2) (nth auto-bounds 0)))
                ly (or length-y (- (nth auto-bounds 3) (nth auto-bounds 1)))]
            [ox oy (+ ox lx) (+ oy ly)])
          :else auto-bounds)
        w resolution
        h resolution
        n (* w h)
        data (js/Float32Array. n)
        _ (dotimes [i n] (aset data i js/Number.NEGATIVE_INFINITY))
        sx (/ (- x-max x-min) w)
        sy (/ (- y-max y-min) h)]
    ;; Rasterize each face (handle both triangles and quads)
    (doseq [face faces]
      (let [v0 (nth verts (nth face 0))
            v1 (nth verts (nth face 1))
            v2 (nth verts (nth face 2))]
        (rasterize-triangle! data w h x-min y-min sx sy v0 v1 v2)
        (when (>= (count face) 4)
          (let [v3 (nth verts (nth face 3))]
            (rasterize-triangle! data w h x-min y-min sx sy v0 v2 v3)))))
    ;; Normalize to [0, 1], skipping -Infinity cells
    (let [z-min (loop [i 0 m js/Number.POSITIVE_INFINITY]
                  (if (>= i n) m
                      (let [v (aget data i)]
                        (recur (inc i) (if (js/isFinite v) (min m v) m)))))
          z-max (loop [i 0 m js/Number.NEGATIVE_INFINITY]
                  (if (>= i n) m
                      (let [v (aget data i)]
                        (recur (inc i) (if (js/isFinite v) (max m v) m)))))
          z-range (- z-max z-min)]
      (if (> z-range 0)
        (dotimes [i n]
          (let [v (aget data i)]
            (aset data i (if (js/isFinite v)
                           (/ (- v z-min) z-range)
                           0))))
        ;; z-range = 0: binary heightmap (finite → 1, -Inf → 0)
        (dotimes [i n]
          (let [v (aget data i)]
            (aset data i (if (js/isFinite v) 1.0 0)))))
      {:type :heightmap
       :data data
       :width w :height h
       :bounds [x-min y-min x-max y-max]
       :z-min z-min :z-max z-max})))

(defn ^:export sample-heightmap
  "Sample a heightmap at (u, v) with bilinear interpolation.
   u, v in [0, 1]. Wraps automatically for tiling."
  [hm u v]
  (let [u (mod u 1)
        v (mod v 1)
        w (:width hm)
        h (:height hm)
        x (* u (dec w))
        y (* v (dec h))
        ix (int (Math/floor x))
        iy (int (Math/floor y))
        fx (- x ix)
        fy (- y iy)
        data (:data hm)
        i00 (+ (mod ix w) (* (mod iy h) w))
        i10 (+ (mod (inc ix) w) (* (mod iy h) w))
        i01 (+ (mod ix w) (* (mod (inc iy) h) w))
        i11 (+ (mod (inc ix) w) (* (mod (inc iy) h) w))]
    (+ (* (aget data i00) (- 1 fx) (- 1 fy))
       (* (aget data i10) fx (- 1 fy))
       (* (aget data i01) (- 1 fx) fy)
       (* (aget data i11) fx fy))))

(defn ^:export heightmap-to-mesh
  "Convert a heightmap back to a flat mesh for visualization/debugging.
   The mesh lies in the XY plane with Z from the heightmap values.
   Uses the original bounds and z-range stored in the heightmap.
   (heightmap-to-mesh hm)                  ; original scale
   (heightmap-to-mesh hm :z-scale 5)       ; amplify Z
   (heightmap-to-mesh hm :size 20)         ; fit into 20x20 square at origin"
  [hm & {:keys [z-scale size] :or {z-scale 1.0}}]
  (let [data (:data hm)
        w (:width hm)
        h (:height hm)
        [x-min y-min x-max y-max] (:bounds hm)
        x-span (- x-max x-min)
        y-span (- y-max y-min)
        z-min (:z-min hm)
        z-max (:z-max hm)
        z-range (- z-max z-min)
        ;; When :size is given, scale XY to fit in [-size/2, size/2]
        ;; and scale Z proportionally
        xy-scale (when size (/ size (max x-span y-span)))
        x-off (if size (* -0.5 size) 0)
        y-off (if size (* -0.5 size) 0)
        ;; Build vertices: one per grid cell
        verts (vec (for [iy (range h)
                         ix (range w)]
                     (let [fx (/ (+ ix 0.5) w)
                           fy (/ (+ iy 0.5) h)
                           x (if size
                               (+ x-off (* fx size))
                               (+ x-min (* fx x-span)))
                           y (if size
                               (+ y-off (* fy size))
                               (+ y-min (* fy y-span)))
                           val (aget data (+ ix (* iy w)))
                           raw-z (+ z-min (* val z-range))
                           z (* z-scale (if size (* raw-z xy-scale) raw-z))]
                       [x y z])))
        ;; Build faces: two triangles per grid quad
        faces (vec (for [iy (range (dec h))
                         ix (range (dec w))
                         :let [i00 (+ ix (* iy w))
                               i10 (+ (inc ix) (* iy w))
                               i01 (+ ix (* (inc iy) w))
                               i11 (+ (inc ix) (* (inc iy) w))]
                         tri [[i00 i10 i11] [i00 i11 i01]]]
                     tri))]
    {:type :mesh
     :vertices verts
     :faces faces
     :creation-pose {:position [0 0 0]
                     :heading [1 0 0]
                     :up [0 0 1]}}))

(defn ^:export heightmap
  "Heightmap displacement shape-fn.
   (heightmap (circle 20 128) hm :amplitude 2 :tile-x 4 :tile-y 3)
   (heightmap (circle 20 128) hm :amplitude 2 :center true)  ; centered [-0.5, 0.5]"
  [shape-or-fn hm & {:keys [amplitude tile-x tile-y offset-x offset-y center]
                     :or {amplitude 1.0 tile-x 1 tile-y 1
                          offset-x 0 offset-y 0 center false}}]
  (displaced shape-or-fn
             (fn [p t]
               (let [u (+ offset-x (* tile-x (/ (+ (angle p) Math/PI) (* 2 Math/PI))))
                     v (+ offset-y (* tile-y t))
                     s (sample-heightmap hm u v)]
                 (* amplitude (if center (- s 0.5) s))))))

;; ============================================================
;; Profile shape-fn (path silhouette → cross-section scaling)
;; ============================================================

(defn- interpolate-table
  "Linear interpolation in a sorted [[t value] ...] table.
   Clamps to first/last values outside the table range."
  [table t]
  (let [n (count table)]
    (cond
      (<= n 0) 1.0
      (= n 1) (second (first table))
      (<= t (ffirst table)) (second (first table))
      (>= t (first (peek table))) (second (peek table))
      :else
      (loop [i 0]
        (if (>= i (dec n))
          (second (peek table))
          (let [[t0 v0] (nth table i)
                [t1 v1] (nth table (inc i))]
            (if (<= t t1)
              (let [frac (if (> (- t1 t0) 0.0001) (/ (- t t0) (- t1 t0)) 0)]
                (+ v0 (* frac (- v1 v0))))
              (recur (inc i)))))))))

(defn ^:export profile
  "Convert a path (silhouette) into a shape-fn that scales the cross-section.
   The path's X coordinates represent radius at each point along the path.
   At each loft step t, the cross-section is uniformly scaled to match
   the silhouette's radius at that position.

   (-> (circle R 64) (profile silhouette-path))

   The path should start at the base radius and trace the silhouette.
   Uses cumulative arc length for smooth parameterization.
   Works best with bezier-smoothed paths (bezier-as) for smooth results."
  [shape-or-fn path]
  (let [wps (shape/path-to-2d-waypoints path)
        all-pts (mapv :pos (rest wps))
        n-all (count all-pts)]
    (if (< n-all 2)
      ;; Not enough points — pass through unchanged
      (shape-fn shape-or-fn (fn [s _t] s))
      (let [;; Skip the initial horizontal segment (origin → base radius).
            ;; After bezier-as this segment has many intermediate points.
            ;; Find the last point where Y hasn't changed from start.
            y0 (second (first all-pts))
            y-last (second (peek all-pts))
            y-range (Math/abs (- y-last y0))
            y-thresh (max 0.01 (* 0.005 y-range))
            base-idx (loop [i 0]
                       (cond
                         (>= i n-all) (dec n-all)
                         (> (Math/abs (- (second (nth all-pts i)) y0)) y-thresh)
                         (max 0 (dec i))
                         :else (recur (inc i))))
            ;; Profile starts from base-idx
            pts (subvec all-pts base-idx)
            n (count pts)]
        (if (< n 2)
          (shape-fn shape-or-fn (fn [s _t] s))
          (let [;; Compute cumulative arc lengths
                cum-dists
                (loop [i 1 acc [0.0]]
                  (if (>= i n)
                    acc
                    (let [[x1 y1] (nth pts (dec i))
                          [x2 y2] (nth pts i)
                          dx (- x2 x1) dy (- y2 y1)
                          d (+ (peek acc) (Math/sqrt (+ (* dx dx) (* dy dy))))]
                      (recur (inc i) (conj acc d)))))
                total-dist (peek cum-dists)
                ;; Normalize to [0, 1]
                ts (if (> total-dist 0.0001)
                     (mapv #(/ % total-dist) cum-dists)
                     (mapv (fn [i] (/ i (max 1 (dec n)))) (range n)))
                ;; Base X: the base radius (X at profile start)
                base-x (ffirst pts)
                ;; Build scale table: [[t scale] ...]
                scale-table (if (> (Math/abs base-x) 0.0001)
                              (mapv (fn [t [x _]] [t (/ x base-x)]) ts pts)
                              [[0 1] [1 1]])]
            (shape-fn shape-or-fn
                      (fn [s t]
                        (let [sc (interpolate-table scale-table t)]
                          (xform/scale s sc))))))))))

;; ============================================================
;; Shell shape-fn (variable-thickness hollow extrusion)
;; ============================================================

(defn- voronoi-hash
  "Deterministic pseudo-random 2D hash based on cell coordinates and seed.
   Returns [x y] in [0,1)×[0,1) — the jittered cell center."
  [ix iy seed]
  (let [h1 (bit-xor (* (+ ix 37) 73856093) (* (+ iy 19) 19349663) (* (+ seed 7) 83492791))
        h2 (bit-xor (* (+ ix 53) 49979693) (* (+ iy 31) 67867967) (* (+ seed 13) 29986577))
        fract (fn [x] (- x (Math/floor x)))]
    [(fract (* (Math/sin h1) 43758.5453))
     (fract (* (Math/sin h2) 22578.1459))]))

(defn- style->thickness-fn
  "Convert a :style keyword + options to a thickness function (fn [a t] → 0..1)."
  [style opts]
  (case style
    :solid (fn [_a _t] 1.0)

    :lattice
    (let [{:keys [openings rows shift]
           :or {openings 8 rows 12 shift 0.5}} opts]
      (fn [a t]
        (let [row (* t rows)
              row-idx (int row)
              phase (* row-idx shift (/ (* 2 Math/PI) openings))
              circ (Math/sin (+ (* a openings) phase))
              longit (Math/sin (* row Math/PI))]
          (max 0 (min circ longit)))))

    :checkerboard
    (let [{:keys [cols rows] :or {cols 8 rows 8}} opts]
      (fn [a t]
        (let [u (/ (+ a Math/PI) (* 2 Math/PI))
              col (mod (int (* u cols)) cols)
              row (mod (int (min (* t rows) (dec rows))) rows)]
          (if (zero? (mod (+ row col) 2)) 1.0 0.0))))

    :weave
    (let [{:keys [strands frequency width]
           :or {strands 6 frequency 8 width 0.3}} opts]
      (fn [a t]
        (let [u (* (/ (+ a Math/PI) (* 2 Math/PI)) strands)
              v (* t frequency)
              col (int (Math/floor u))
              row (int (Math/floor v))
              fu (- u (Math/floor u))
              fv (- v (Math/floor v))
              on-warp? (< (Math/abs (- fu 0.5)) width)
              on-weft? (< (Math/abs (- fv 0.5)) width)
              warp-over? (zero? (mod (+ row col) 2))]
          (cond
            (and on-warp? on-weft?) (if warp-over? 1.0 0.0)
            on-warp? 1.0
            on-weft? 1.0
            :else 0.0))))

    :voronoi
    ;; Wall stripe along Voronoi cell edges. Returns 1 inside the wall and 0
    ;; outside. :margin (default 0.05) specifies the fraction of t at start
    ;; and end where the wall is forced solid (1.0), producing clean closed
    ;; edges instead of jagged voronoi cuts.
    (let [{:keys [cells rows seed wall-width margin]
           :or {cells 6 rows 6 seed 42 wall-width 0.3 margin 0.05}} opts
          half-wall (* 0.5 wall-width)
          margin (or margin 0.05)]
      (fn [a t]
        (if (or (<= t margin) (>= t (- 1.0 margin)))
          1.0
          (let [u (* (/ (+ a Math/PI) (* 2 Math/PI)) cells)
                v (* t rows)
                iu (int (Math/floor u))
                iv (int (Math/floor v))
                [d1 d2]
                (reduce
                 (fn [[best1 best2] [di dj]]
                   (let [ci (+ iu di)
                         cj (+ iv dj)
                         ci-wrapped (mod ci cells)
                         [jx jy] (voronoi-hash ci-wrapped cj seed)
                         cx (+ ci jx)
                         cy (+ cj jy)
                         du (- u cx)
                         dv (- v cy)
                         dist (Math/sqrt (+ (* du du) (* dv dv)))]
                     (cond
                       (< dist best1) [dist best1]
                       (< dist best2) [best1 dist]
                       :else [best1 best2])))
                 [js/Infinity js/Infinity]
                 [[-1 -1] [-1 0] [-1 1]
                  [0 -1]  [0 0]  [0 1]
                  [1 -1]  [1 0]  [1 1]])
                edge-dist (- d2 d1)]
            (if (< edge-dist half-wall) 1.0 0.0)))))

    ;; Unknown style
    (throw (js/Error. (str "shell: unknown :style " style
                           ". Valid styles: :solid :lattice :checkerboard :weave :voronoi")))))

(defn ^:export shell
  "Variable-thickness hollow shell with optional patterned walls and caps.

   Wall pattern via :style or :fn:
   (shell shape :thickness 2 :style :solid)                        ; Solid walls (default)
   (shell shape :thickness 2 :style :voronoi :cells 8 :rows 6)    ; Voronoi openings
   (shell shape :thickness 2 :style :lattice :openings 8 :rows 12); Grid openings
   (shell shape :thickness 2 :style :checkerboard :cols 8 :rows 8); Checkerboard
   (shell shape :thickness 2 :style :weave :strands 6 :frequency 8); Woven pattern
   (shell shape :thickness 2 :fn (fn [a t] ...))                   ; Custom function

   :voronoi extra options:
     :wall-width  width of the wall stripe in (u, v) cell units (default 0.3)
   The :voronoi cliff is binary: openings have hard pixelated edges along the
   ring/segment grid. To smooth them, post-process the resulting mesh with
   (mesh-smooth m :sharp-angle 90 :refine 2) — Manifold's tangent-based
   smoother + subdivision rounds off the staircase while preserving any
   intentionally sharp design corners.

   Caps at the ends:
   :cap-top N                                          ; Solid cap of thickness N
   :cap-top {:thickness N :style :voronoi :cells 10 :wall 1}  ; Patterned cap
   :cap-bottom N                                       ; Solid cap at start

   Composes with other shape-fns:
   (-> (circle 20 64) (shell :thickness 3 :style :voronoi :cells 8 :rows 6) (tapered :to 0.5))"
  [shape-or-fn & {:keys [thickness threshold style cap-top cap-bottom]
                  :or {thickness 2 threshold 0.05}
                  :as opts}]
  (let [thickness-fn (or (:fn opts)
                         (style->thickness-fn (or style :solid) opts))]
    (shape-fn shape-or-fn
              (fn [s t]
                (let [center (shape-centroid s)
                      pts (:points s)
                      values (mapv (fn [p]
                                     (let [a (Math/atan2 (- (second p) (second center))
                                                         (- (first p) (first center)))
                                           v (thickness-fn a t)]
                                       (if (< v threshold) 0.0 (max 0.0 (min 1.0 v)))))
                                   pts)]
                  (cond-> (assoc s
                                 :shell-mode true
                                 :shell-thickness thickness
                                 :shell-values values)
                    cap-top    (assoc :shell-cap-top cap-top)
                    cap-bottom (assoc :shell-cap-bottom cap-bottom)))))))

;; ============================================================
;; Woven shell (thickness + radial offset for true over/under)
;; ============================================================

(defn- woven-combine-crossing
  "At a crossing, compute wall that encompasses both threads.
   Returns {:thickness v :offset o} where v may exceed 1.0."
  [off-a prof-a off-b prof-b half-t]
  (let [;; Each thread wall spans [offset - half-t*prof, offset + half-t*prof]
        top-a (+ off-a (* half-t prof-a))
        bot-a (- off-a (* half-t prof-a))
        top-b (+ off-b (* half-t prof-b))
        bot-b (- off-b (* half-t prof-b))
        ;; Union: outermost to innermost
        outer (max top-a top-b)
        inner (min bot-a bot-b)
        center (* 0.5 (+ outer inner))
        half-span (* 0.5 (- outer inner))]
    {:thickness (if (pos? half-t) (/ half-span half-t) 1.0)
     :offset center}))

(defn- woven-shell-diagonal
  "Built-in diagonal weave pattern for woven-shell."
  [strands width lift half-t]
  (fn [a t]
    (let [u (/ (+ a Math/PI) (* 2 Math/PI))
          d1 (+ (* u strands) (* t strands))
          d2 (- (* u strands) (* t strands))
          fd1 (- (mod d1 1) 0.5)
          fd2 (- (mod d2 1) 0.5)
          on-d1? (< (Math/abs fd1) width)
          on-d2? (< (Math/abs fd2) width)
          prof (fn [fd w] (Math/cos (* (/ fd w) Math/PI 0.5)))
          col (int (Math/floor d1))
          row (int (Math/floor d2))
          off-d1 (* lift (Math/cos (* col Math/PI))
                    (Math/sin (* d2 Math/PI)))
          off-d2 (* (- lift) (Math/cos (* row Math/PI))
                    (Math/sin (* d1 Math/PI)))]
      (cond
        (and on-d1? on-d2?)
        (woven-combine-crossing off-d1 (prof fd1 width)
                                off-d2 (prof fd2 width) half-t)
        on-d1? {:thickness (prof fd1 width) :offset off-d1}
        on-d2? {:thickness (prof fd2 width) :offset off-d2}
        :else {:thickness 0 :offset 0}))))

(defn- woven-shell-orthogonal
  "Built-in orthogonal weave pattern for woven-shell.
   warp = longitudinal threads (along t), weft = circumferential (along u).
   Each direction has its own count and width."
  [warp weft warp-width weft-width lift half-t]
  (fn [a t]
    (let [u (/ (+ a Math/PI) (* 2 Math/PI))
          wu (* u warp)              ;; warp coordinate (circumferential position)
          wv (* t weft)              ;; weft coordinate (longitudinal position)
          fwu (- (mod wu 1) 0.5)    ;; fractional pos within warp cell
          fwv (- (mod wv 1) 0.5)    ;; fractional pos within weft cell
          on-warp? (< (Math/abs fwu) warp-width)
          on-weft? (< (Math/abs fwv) weft-width)
          prof (fn [fd w] (Math/cos (* (/ fd w) Math/PI 0.5)))
          col (int (Math/floor wu))
          row (int (Math/floor wv))
          ;; Warp threads undulate as they cross weft lines
          off-warp (* lift (Math/cos (* col Math/PI))
                      (Math/sin (* wv Math/PI)))
          ;; Weft threads undulate as they cross warp lines (opposite sign)
          off-weft (* (- lift) (Math/cos (* row Math/PI))
                      (Math/sin (* wu Math/PI)))]
      (cond
        (and on-warp? on-weft?)
        (woven-combine-crossing off-warp (prof fwu warp-width)
                                off-weft (prof fwv weft-width) half-t)
        on-warp? {:thickness (prof fwu warp-width) :offset off-warp}
        on-weft? {:thickness (prof fwv weft-width) :offset off-weft}
        :else {:thickness 0 :offset 0}))))

(defn ^:export woven-shell
  "Shell with radial offset for true over/under woven effect.
   Unlike `shell` (thickness only), this shifts the wall center radially
   so threads can pass in front of / behind each other at crossings.

   Diagonal weave (default):
   (woven-shell (circle 20 128) :thickness 3 :strands 8)
   (woven-shell (circle 20 128) :thickness 3 :strands 8 :width 0.15 :lift 1.5)

   Orthogonal weave (basket/wicker):
   (woven-shell (circle 20 128) :thickness 3
     :mode :orthogonal :warp 8 :weft 40
     :warp-width 0.2 :weft-width 0.08)

   Custom fn returning {:thickness 0..1, :offset number}:
   (woven-shell (circle 20 128) :thickness 3
     :fn (fn [a t] {:thickness 0.8 :offset (* 0.5 (sin (* a 4)))}))

   Composes with other shape-fns:
   (-> (circle 20 128) (woven-shell :thickness 3 :strands 6) (tapered :to 0.5))"
  [shape-or-fn & {:keys [thickness threshold strands width lift
                         warp weft warp-width weft-width cap-top cap-bottom]
                  :or {thickness 2 threshold 0.05 strands 8 width 0.12}
                  :as opts}]
  (let [custom-fn (:fn opts)
        mode (or (:mode opts) :diagonal)
        lift (or lift (* 0.5 thickness))
        half-t (* 0.5 thickness)
        weave-fn (or custom-fn
                     (case mode
                       :diagonal (woven-shell-diagonal strands width lift half-t)
                       :orthogonal (woven-shell-orthogonal
                                    (or warp 8) (or weft 30)
                                    (or warp-width 0.2) (or weft-width 0.1)
                                    lift half-t)))]
    (shape-fn shape-or-fn
              (fn [s t]
                (let [center (shape-centroid s)
                      pts (:points s)
                      results (mapv (fn [p]
                                      (let [a (Math/atan2 (- (second p) (second center))
                                                          (- (first p) (first center)))]
                                        (weave-fn a t)))
                                    pts)
                      values (mapv (fn [{:keys [thickness]}]
                                     (let [v thickness]
                                       (if (< v threshold) 0.0 (max 0.0 v))))
                                   results)
                      offsets (mapv :offset results)]
                  (cond-> (assoc s
                                 :shell-mode true
                                 :shell-thickness thickness
                                 :shell-values values
                                 :shell-offsets offsets)
                    cap-top    (assoc :shell-cap-top cap-top)
                    cap-bottom (assoc :shell-cap-bottom cap-bottom)))))))

;; ============================================================
;; Cap fillet (smooth edge transition at extrusion ends)
;; ============================================================

(defn ^:export capped
  "Add a fillet or chamfer transition at the start and/or end of an extrusion.
   Insets the 2D profile at the caps and transitions smoothly to the full shape.

   radius: fillet/chamfer distance (how far the edge is inset at the cap).
          Positive = shrink at caps, negative = expand at caps.

   Options:
   - :mode     :fillet (default) or :chamfer
   - :start    true/false — apply at t=0 (default true)
   - :end      true/false — apply at t=1 (default true)
   - :fraction number — fraction of path for transition (default 0.08)
   - :end-radius number — override radius at the end (default: same as radius)
   - :preserve-holes true/false — keep holes unchanged (default true)

   Usage:
     (loft (capped (rect 40 20) 3) (f 50))                ; fillet both caps
     (loft (capped (rect 40 20) 3 :mode :chamfer) (f 50)) ; chamfer both caps
     (loft (capped (rect 40 20) 3 :end false) (f 50))     ; fillet start only
     (loft (capped shape -7 :end-radius 1.5) (f 50))      ; expand at base, shrink at top

   Composes with other shape-fns:
     (-> (circle 20) (fluted :flutes 8 :depth 2) (capped 3))

   Negative radius expands the shape at the caps (useful for reinforcement fillets).
   With shapes that have holes, :preserve-holes true (default) keeps holes unchanged
   so only the outer boundary is affected."
  [shape-or-fn radius & {:keys [mode start end fraction end-radius preserve-holes]
                         :or {mode :fillet start true end true
                              preserve-holes true}}]
  (let [ease-fn (case mode
                  :fillet  (fn [u r]
                             (if (neg? r)
                               (Math/sin (* u (/ Math/PI 2)))
                               (Math/sqrt (- (* 2 u) (* u u)))))
                  :chamfer (fn [u _r] u))
        start-radius radius
        end-radius (or end-radius radius)
        explicit-fraction fraction]
    (shape-fn shape-or-fn
              (fn [s t]
        ;; Auto-calculate fraction from path length when not explicitly set
                (let [fraction (or explicit-fraction
                                   (when *path-length*
                                     (let [max-r (max (Math/abs start-radius) (Math/abs (or end-radius start-radius)))
                                           ideal (/ max-r *path-length*)]
                               ;; Cap at 0.45 to leave room for the middle section
                                       (min 0.45 ideal)))
                                   0.08)
                      [in-transition? u active-radius]
                      (cond
                        (and start (< t fraction))
                        [true (ease-fn (/ t fraction) start-radius) start-radius]

                        (and end (> t (- 1 fraction)))
                        [true (ease-fn (/ (- 1 t) fraction) end-radius) end-radius]

                        :else [false 1.0 0])]
                  (if (or (not in-transition?) (>= u 0.999))
                    s
            ;; Scale shape toward centroid — preserves proportions (fillet radii etc.)
            ;; The radius parameter controls how much the nearest edge moves inward.
                    (let [inset-amount (* active-radius (- 1 u))
                          inradius (xform/shape-inradius s)
                          scale (if (> inradius 0.001)
                                  (max 0.001 (/ (- inradius inset-amount) inradius))
                                  1.0)
                          pts (:points s)
                          n (count pts)
                          cx (/ (reduce + (map first pts)) n)
                          cy (/ (reduce + (map second pts)) n)
                          scale-pt (fn [[x y]]
                                     [(+ cx (* scale (- x cx)))
                                      (+ cy (* scale (- y cy)))])
                          scaled-points (mapv scale-pt pts)
                          orig-holes (:holes s)]
                      (cond-> (assoc s :points scaled-points)
                        (and orig-holes (not preserve-holes))
                        (assoc :holes (mapv (fn [hole] (mapv scale-pt hole))
                                            orig-holes))))))))))
