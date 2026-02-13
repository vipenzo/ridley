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
   Returns {:x [min max] :y [min max] :z [min max]}."
  [mesh]
  (let [verts (:vertices mesh)]
    (reduce (fn [acc v]
              (-> acc
                  (update-in [:x 0] min (nth v 0))
                  (update-in [:x 1] max (nth v 0))
                  (update-in [:y 0] min (nth v 1))
                  (update-in [:y 1] max (nth v 1))
                  (update-in [:z 0] min (nth v 2))
                  (update-in [:z 1] max (nth v 2))))
            {:x [js/Number.POSITIVE_INFINITY js/Number.NEGATIVE_INFINITY]
             :y [js/Number.POSITIVE_INFINITY js/Number.NEGATIVE_INFINITY]
             :z [js/Number.POSITIVE_INFINITY js/Number.NEGATIVE_INFINITY]}
            verts)))

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
      (when (> z-range 0)
        (dotimes [i n]
          (let [v (aget data i)]
            (aset data i (if (js/isFinite v)
                           (/ (- v z-min) z-range)
                           0)))))
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
