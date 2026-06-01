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
            [ridley.turtle.shape :as shape]
            [ridley.turtle.extrusion :as extrusion]))

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
  "Rocky/irregular displacement via layered sinusoids varying both around the
   profile and along the path. Each octave doubles the frequency and scales
   amplitude by :gain, producing fBm-style asperities.
   Distinct from `fluted` (single regular ridge pattern) and `noisy` (smooth
   value noise): `rugged` keeps the angular character of sin waves layered at
   multiple scales — useful for rocky, bark, or crystalline surfaces.
   (rugged (circle 15) :amplitude 2 :frequency 6 :octaves 3)
   (rugged (circle 15) :amplitude 2 :octaves 4 :gain 0.6 :seed 7)"
  [shape-or-fn & {:keys [amplitude frequency octaves gain seed]
                  :or {amplitude 1 frequency 6 octaves 3 gain 0.5 seed 0}}]
  (shape-fn shape-or-fn
            (fn [s t]
              (displace-radial s
                               (fn [p]
                                 (let [a (angle p)]
                                   (loop [i 0
                                          freq (double frequency)
                                          amp 1.0
                                          total 0.0
                                          max-amp 0.0]
                                     (if (>= i octaves)
                                       (* amplitude (/ total (max max-amp 1e-9)))
                                       (let [phase (+ seed (* i 2.3956))]
                                         (recur (inc i)
                                                (* freq 2.0)
                                                (* amp gain)
                                                (+ total
                                                   (* amp 0.5
                                                      (+ (Math/sin (+ (* a freq) phase))
                                                         (Math/sin (+ (* t freq) phase 1.7)))))
                                                (+ max-amp amp)))))))))))

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
   If point counts differ, both are resampled to the max count.
   Shape-b is angularly aligned to shape-a so corresponding vertices follow
   the shortest path (avoids twisted/bowtie morphs between e.g. rect and circle).
   (morphed (rect 20 20) (circle 15 32))
   (morphed (star 5 20 8) (circle 15 32))"
  [shape-a shape-b]
  (let [n-a (count (:points shape-a))
        n-b (count (:points shape-b))
        [ra rb] (if (= n-a n-b)
                  [shape-a shape-b]
                  (let [n (max n-a n-b)]
                    [(xform/resample shape-a n) (xform/resample shape-b n)]))
        rb-aligned (xform/align-to-shape ra rb)]
    (shape-fn ra
              (fn [s t]
                (xform/morph s rb-aligned t)))))

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
   :resolution - heightmap grid size (default (default-segments 2))
   :profile    - :round or :flat (default :round)
   :thickness  - for :flat profile, ribbon thickness (default: radius * 0.5)"
  [& {:keys [threads spacing radius lift resolution profile thickness]
      :or {threads 4, spacing 5, radius 2, profile :round}}]
  (let [resolution (or resolution (extrusion/default-segments 2))
        lift (or lift radius)
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

(defn- rasterize-faces-normalized
  "Rasterize faces (max-z point-sampling) onto a w×h grid over [x-min y-min
   x-max y-max], then normalize finite cells to [0, 1] (binary 1/0 when the
   z-range is 0). Background cells become 0. Returns {:data :z-min :z-max}."
  [verts faces w h x-min y-min x-max y-max]
  (let [n (* w h)
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
    ;; Normalize to [0, 1], skipping -Infinity (background) cells
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
            (aset data i (if (js/isFinite v) (/ (- v z-min) z-range) 0))))
        ;; z-range = 0: binary heightmap (finite → 1, -Inf → 0)
        (dotimes [i n]
          (let [v (aget data i)]
            (aset data i (if (js/isFinite v) 1.0 0)))))
      {:data data :z-min z-min :z-max z-max})))

(defn- box-downsample
  "Average a (w*ss)×(h*ss) Float32Array into a w×h grid: each output cell is
   the mean of its ss×ss source block. Turns the hard 0/1 edges of a binary
   raster into fractional coverage (anti-aliasing), so the relief reads as a
   smooth bevel instead of a per-cell staircase when sampled on a loft."
  [src w h ss]
  (let [sw (* w ss)
        out (js/Float32Array. (* w h))
        inv (/ 1.0 (* ss ss))]
    (dotimes [oy h]
      (dotimes [ox w]
        (let [sx0 (* ox ss)
              sy0 (* oy ss)
              acc (loop [dy 0 a 0.0]
                    (if (>= dy ss)
                      a
                      (recur (inc dy)
                             (loop [dx 0 a2 a]
                               (if (>= dx ss)
                                 a2
                                 (recur (inc dx)
                                        (+ a2 (aget src (+ sx0 dx
                                                           (* (+ sy0 dy) sw))))))))))]
          (aset out (+ ox (* oy w)) (* acc inv)))))
    out))

(defn- box-blur
  "Separable box blur of a w×h Float32Array with independent cell radii rx, ry
   (edges clamped — each output cell averages only in-bounds neighbours). Widens
   the edge ramp so a coarse loft resolves it as a gradient bevel rather than a
   per-cell step. Radii in CELLS; mesh-to-heightmap derives them from a
   world-unit :blur so the smoothing is isotropic in real space (and thus wider,
   in cells, along an over-resolved axis — exactly where the comb is worst)."
  [src w h rx ry]
  (if (and (<= rx 0) (<= ry 0))
    src
    (let [tmp (js/Float32Array. (* w h))
          out (js/Float32Array. (* w h))]
      ;; horizontal pass
      (dotimes [y h]
        (dotimes [x w]
          (let [x0 (max 0 (- x rx))
                x1 (min (dec w) (+ x rx))]
            (loop [xx x0 acc 0.0 c 0]
              (if (> xx x1)
                (aset tmp (+ x (* y w)) (/ acc c))
                (recur (inc xx) (+ acc (aget src (+ xx (* y w)))) (inc c)))))))
      ;; vertical pass
      (dotimes [y h]
        (dotimes [x w]
          (let [y0 (max 0 (- y ry))
                y1 (min (dec h) (+ y ry))]
            (loop [yy y0 acc 0.0 c 0]
              (if (> yy y1)
                (aset out (+ x (* y w)) (/ acc c))
                (recur (inc yy) (+ acc (aget tmp (+ x (* yy w)))) (inc c)))))))
      out)))

(defn ^:export mesh-to-heightmap
  "Convert a mesh to a heightmap by rasterizing max-z onto a 2D grid.
   :resolution defaults to (default-segments 2) — twice the global curve
   resolution, because heightmaps are 2D grids and benefit from finer sampling.

   :supersample (default 1 = off) rasterizes the grid at this factor in each
   axis and box-downsamples it, anti-aliasing the edges. Crucial for binary
   reliefs (e.g. text): without it the hard 0/1 edge snaps to whole cells and
   shows a comb/staircase once wrapped on a loft; 3–4 yields smooth letters
   without needing a denser loft or grid.

   :blur (default 0 = off) widens the edge ramp by box-blurring the grid with a
   radius given in WORLD units (same units as the bounds). Supersampling makes
   the edge position sub-cell accurate; blur makes the ramp wide enough that a
   coarse loft resolves it as a smooth bevel instead of a comb. The world-unit
   radius is converted to per-axis cell radii, so it stays isotropic in real
   space even on a non-square footprint.
   (mesh-to-heightmap mesh)
   (mesh-to-heightmap mesh :resolution 256)
   (mesh-to-heightmap mesh :resolution 256 :supersample 3 :blur 0.4)
   (mesh-to-heightmap mesh :resolution 128 :bounds [x0 y0 x1 y1])
   (mesh-to-heightmap mesh :resolution 128 :offset-x 0 :offset-y 0 :length-x 10 :length-y 10)"
  [mesh & {:keys [resolution bounds offset-x offset-y length-x length-y supersample blur]}]
  (let [resolution (or resolution (extrusion/default-segments 2))
        ss (max 1 (int (or supersample 1)))
        verts (:vertices mesh)
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
        ;; Rasterize at ss× resolution, then box-downsample for anti-aliasing.
        {:keys [data z-min z-max]}
        (rasterize-faces-normalized verts faces (* w ss) (* h ss)
                                    x-min y-min x-max y-max)
        downsampled (if (> ss 1) (box-downsample data w h ss) data)
        ;; Widen the edge ramp. The world-unit radius maps to per-axis cell
        ;; radii via the cell size, so it is isotropic in real space.
        out (if (and blur (pos? blur))
              (let [rx (Math/round (/ blur (/ (- x-max x-min) w)))
                    ry (Math/round (/ blur (/ (- y-max y-min) h)))]
                (box-blur downsampled w h rx ry))
              downsampled)]
    {:type :heightmap
     :data out
     :width w :height h
     :bounds [x-min y-min x-max y-max]
     :z-min z-min :z-max z-max}))

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

(defn- heightmap-phys-size
  "Physical [width height] footprint of a heightmap. Text heightmaps carry
   explicit :phys-width/:phys-height; others fall back to the bounds span,
   else 1×1 (treated as normalized)."
  [hm]
  (cond
    (and (:phys-width hm) (:phys-height hm))
    [(:phys-width hm) (:phys-height hm)]
    (:bounds hm)
    (let [[x0 y0 x1 y1] (:bounds hm)] [(- x1 x0) (- y1 y0)])
    :else [1 1]))

(defn ^:export heightmap
  "Wrap a heightmap onto a loft's walls as a radial relief.

   Two responsibilities are split: a producer (e.g. `text-heightmap`,
   `weave-heightmap`, `mesh-to-heightmap`) makes the heightmap; this shape-fn
   decides HOW it lands on the surface.

   Surface axes: `u` runs around the cross-section (the circumference) and `v`
   runs along the loft path (the height).

   Options:
   - :amplitude  relief height in world units (default 1.0)
   - :center     center sampled values around 0 → [-0.5,0.5] (default false)
   - :direction  :circumference (heightmap width wraps around — text reads
                 around the tube, default) or :height (heightmap width runs
                 along the path — text climbs the wall)
   - :fit        :physical (honor the heightmap's real size), :stretch (fill
                 the whole surface, the classic behavior), or :auto (default:
                 physical when the heightmap knows its size — i.e. from
                 `text-heightmap` — else stretch)
   - :scale      multiply the heightmap's physical size (physical mode, default 1)
   - :surface-width   circumference override (physical; default = perimeter of
                      the base shape)
   - :surface-height  path length override (physical; default = the loft's own
                      length, taken from *path-length*)
   - :tile-x :tile-y  copies across reading / height (integer, or :fill to pack
                      as many whole copies as the surface holds, snapping the
                      cell so it tiles seamlessly). Default 1.
   - :offset-x :offset-y  shift the placement, as a fraction of the surface
                          (default centers a single copy)

   (heightmap (circle 20 128) weave :amplitude 2 :tile-x 4 :tile-y 3)  ; stretch
   (heightmap (circle 10 256) txt  :amplitude 1.5 :center true)        ; physical"
  [shape-or-fn hm & {:keys [amplitude center direction fit scale
                            surface-width surface-height
                            tile-x tile-y offset-x offset-y]
                     :or {amplitude 1.0 center false direction :circumference
                          fit :auto scale 1.0 tile-x 1 tile-y 1}}]
  (let [base      (if (shape-fn? shape-or-fn) (:base (meta shape-or-fn)) shape-or-fn)
        physical? (case fit
                    :physical true
                    :stretch  false
                    (boolean (:phys-width hm)))     ; :auto
        [pw ph]   (heightmap-phys-size hm)
        cell-w    (* pw scale)
        cell-h    (* ph scale)
        bg        (if center -0.5 0.0)]             ; flat background relief level
    (displaced
     shape-or-fn
     (fn [p t]
       (let [u-circ (/ (+ (angle p) Math/PI) (* 2 Math/PI))]   ; [0,1) around
         (if-not physical?
           ;; --- stretch mode: one (tiled) heightmap fills the whole surface ---
           (let [ox (or offset-x 0) oy (or offset-y 0)
                 tx (if (= tile-x :fill) 1 tile-x)
                 ty (if (= tile-y :fill) 1 tile-y)
                 [u v] (if (= direction :height)
                         [(+ ox (* tx t)) (+ oy (* ty u-circ))]
                         [(+ ox (* tx u-circ)) (+ oy (* ty t))])
                 s (sample-heightmap hm u v)]
             (* amplitude (if center (- s 0.5) s)))
           ;; --- physical mode: honor the heightmap's real-world size ---
           (let [C (or surface-width (when base (shape/shape-perimeter base)) 1.0)
                 H (or surface-height *path-length* 1.0)
                 ;; reading axis spans A, glyph-height axis spans B
                 [A B] (if (= direction :height) [H C] [C H])
                 ;; reflect the reading axis so text is NOT mirrored when read
                 ;; from outside the surface
                 read-pos   (if (= direction :height) (* t H) (* (- 1 u-circ) C))
                 height-pos (if (= direction :height) (* u-circ C) (* t H))
                 nx (if (= tile-x :fill) (max 1 (Math/round (/ A cell-w))) tile-x)
                 ny (if (= tile-y :fill) (max 1 (Math/round (/ B cell-h))) tile-y)
                 cw (if (= tile-x :fill) (/ A nx) cell-w)
                 ch (if (= tile-y :fill) (/ B ny) cell-h)
                 ;; center the tiled block by default, then shift by offset
                 a0 (+ (* 0.5 (- A (* nx cw))) (* (or offset-x 0) A))
                 b0 (+ (* 0.5 (- B (* ny ch))) (* (or offset-y 0) B))
                 hu (/ (- read-pos a0) cw)          ; position in copies
                 hv (/ (- height-pos b0) ch)]
             (if (and (>= hu 0) (< hu nx) (>= hv 0) (< hv ny))
               (let [s (sample-heightmap hm (mod hu 1) (mod hv 1))]
                 (* amplitude (if center (- s 0.5) s)))
               ;; outside the placed copies → flat background relief
               (* amplitude bg)))))))))

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

(defn- voronoi-edge-dist
  "Distance from (a, t) to the nearest Voronoi cell EDGE, as d2 - d1 (second
   nearest seed distance minus nearest). ~0 on a cell boundary, larger inside a
   cell. Shared by the :voronoi thickness fn and its marching field fn."
  [a t cells rows seed]
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
          [1 -1]  [1 0]  [1 1]])]
    (- d2 d1)))

(defn- smoothstep
  "Hermite smoothstep: 0 below e0, 1 above e1, smooth (C1) in between."
  [e0 e1 x]
  (if (<= e1 e0)
    (if (< x e0) 0.0 1.0)
    (let [t (-> (/ (- x e0) (- e1 e0)) (max 0.0) (min 1.0))]
      (* t t (- 3.0 (* 2.0 t))))))

(defn- style->thickness-fn
  "Convert a :style keyword + options to a thickness function (fn [a t] → 0..1)."
  [style opts]
  (case style
    :solid (fn [_a _t] 1.0)

    :lattice
    ;; Wall where min(circ, longit) > 0. :softness > 0 (default 0) rescales the
    ;; SIGNED field min(circ, longit) so the wall boundary lands at 0.5 with a
    ;; continuous ramp on both sides — letting the isocontour build cut openings
    ;; smoothly. Without it the clamped field is flat 0 in the openings, so the
    ;; cut would snap to grid vertices (staircase). See :voronoi / shell.
    (let [{:keys [openings rows shift softness]
           :or {openings 8 rows 12 shift 0.5 softness 0}} opts
          s2 (* 2.0 (max 1e-6 softness))]
      (fn [a t]
        (let [row (* t rows)
              row-idx (int row)
              phase (* row-idx shift (/ (* 2 Math/PI) openings))
              circ (Math/sin (+ (* a openings) phase))
              longit (Math/sin (* row Math/PI))
              g (min circ longit)]
          (if (<= softness 0)
            (max 0 g)
            (-> (+ 0.5 (/ g s2)) (max 0.0) (min 1.0))))))

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
    ;;
    ;; :softness (default 0 = hard binary cut, original behavior) ramps the
    ;; wall→opening transition over a band of width (softness * half-wall) in
    ;; edge-dist units instead of cliffing at edge-dist = half-wall. Because a
    ;; vertex with value 0 collapses outer+inner onto the base ring (zero
    ;; thickness), a graded value makes the wall feather to a thin lip at the
    ;; opening rather than dropping off a grid-locked jagged cliff — the
    ;; shell analogue of text relief's :edge-softness. The look is soft/organic
    ;; (not crisp-curved openings — that would need an isocontour cut).
    (let [{:keys [cells rows seed wall-width margin softness]
           :or {cells 6 rows 6 seed 42 wall-width 0.3 margin 0.05 softness 0}} opts
          half-wall (* 0.5 wall-width)
          margin (or margin 0.05)
          band (* (max 0.0 softness) half-wall)
          ;; centre the ramp on half-wall so the stripe keeps ~its width
          e0 (- half-wall (* 0.5 band))
          e1 (+ half-wall (* 0.5 band))]
      (fn [a t]
        (if (or (<= t margin) (>= t (- 1.0 margin)))
          1.0
          (let [edge-dist (voronoi-edge-dist a t cells rows seed)]
            (if (<= band 0)
              (if (< edge-dist half-wall) 1.0 0.0)
              ;; 1 inside the stripe (small edge-dist), feather to 0 outside
              (- 1.0 (smoothstep e0 e1 edge-dist)))))))

    ;; Unknown style
    (throw (js/Error. (str "shell: unknown :style " style
                           ". Valid styles: :solid :lattice :checkerboard :weave :voronoi")))))

(defn- style->field-fn
  "Continuous, UNCLAMPED marching field for smooth (isocontour) shells — a
   near-linear function of the underlying signal whose 0.5 level is the wall
   edge. Decoupled from the thickness value: the thickness uses a clamped,
   softened ramp (for the tapered lip), but that ramp SATURATES near the
   boundary, so at low resolution its iso crossing snaps toward edge midpoints.
   This field stays linear through the boundary, so the crossing lands on the
   true edge at any resolution. nil for styles with no continuous boundary."
  [style opts]
  (case style
    :voronoi
    (let [{:keys [cells rows seed wall-width margin]
           :or {cells 6 rows 6 seed 42 wall-width 0.3 margin 0.05}} opts
          half-wall (* 0.5 wall-width)
          margin (or margin 0.05)]
      (fn [a t]
        (if (or (<= t margin) (>= t (- 1.0 margin)))
          1.5                                   ; forced solid, well above level
          (+ 0.5 (- half-wall (voronoi-edge-dist a t cells rows seed))))))

    :lattice
    (let [{:keys [openings rows shift]
           :or {openings 8 rows 12 shift 0.5}} opts]
      (fn [a t]
        (let [row (* t rows)
              row-idx (int row)
              phase (* row-idx shift (/ (* 2 Math/PI) openings))
              circ (Math/sin (+ (* a openings) phase))
              longit (Math/sin (* row Math/PI))]
          (+ 0.5 (min circ longit)))))

    nil))

(defn ^:export shell
  "Variable-thickness hollow shell with optional patterned walls and caps.

   Wall pattern via :style or :fn:
   (shell shape :thickness 2 :style :solid)                        ; Solid walls (default)
   (shell shape :thickness 2 :style :voronoi :cells 8 :rows 6)    ; Voronoi openings
   (shell shape :thickness 2 :style :lattice :openings 8 :rows 12); Grid openings
   (shell shape :thickness 2 :style :checkerboard :cols 8 :rows 8); Checkerboard
   (shell shape :thickness 2 :style :weave :strands 6 :frequency 8); Woven pattern
   (shell shape :thickness 2 :fn (fn [a t] ...))                   ; Custom function

   Add :invert? true to swap solid/empty (e.g. turn :lattice bricks into a
   shell with brick-shaped openings, or :voronoi wireframe into solid cells).

   :voronoi extra options:
     :wall-width  width of the wall stripe in (u, v) cell units (default 0.3)

   :softness (:voronoi and :lattice, default 0)
     0 = hard binary cut: openings are carved by dropping whole grid triangles,
       so their edges staircase along the ring/segment grid (raising resolution
       only shrinks the teeth).
     >0 = ISOCONTOUR cut: a continuous field feeds a marching-triangles build
       that slices each boundary triangle exactly along the wall→opening
       iso-line, at sub-grid positions. Openings come out smooth (a low-poly
       curve FOLLOWING the boundary, not a grid staircase) at LOW resolution —
       far cheaper than cranking segments — and the variable wall thickness adds
       a graceful tapered lip. ~0.4–0.8 works well; the result stays
       watertight/manifold (vertices welded along the cut). This is the shell
       analogue of text relief's :edge-softness.
   :softness gives soft, organic openings. For a *different* look — crisp walls
   with rounded staircase — keep :softness 0 and post-process with
   (mesh-smooth m :sharp-angle 90 :refine 2).

   Caps at the ends:
   :cap-top N                                          ; Solid cap of thickness N
   :cap-top {:thickness N :style :voronoi :cells 10 :wall 1}  ; Patterned cap
   :cap-bottom N                                       ; Solid cap at start

   Composes with other shape-fns:
   (-> (circle 20 64) (shell :thickness 3 :style :voronoi :cells 8 :rows 6) (tapered :to 0.5))"
  [shape-or-fn & {:keys [thickness threshold style cap-top cap-bottom invert?]
                  :or {thickness 2 threshold 0.05}
                  :as opts}]
  (let [base-fn (or (:fn opts)
                    (style->thickness-fn (or style :solid) opts))
        thickness-fn (if invert?
                       (fn [a t] (- 1.0 (base-fn a t)))
                       base-fn)
        ;; With a continuous field (:voronoi or :lattice with :softness > 0) we
        ;; can cut openings along the iso-line instead of dropping whole grid
        ;; triangles, giving smooth opening outlines at low resolution. The
        ;; continuous values flow through unchanged; only the mesh-build path
        ;; changes (see loft).
        smooth? (and (contains? #{:voronoi :lattice} style)
                     (pos? (or (:softness opts) 0)))
        ;; Marching field, DECOUPLED from the thickness value: the thickness
        ;; ramp is clamped/softened (tapered lip) and so saturates near the
        ;; boundary, snapping the iso cut toward edge midpoints at low res; this
        ;; unclamped field stays linear through the edge, so the cut is accurate
        ;; at any resolution. invert? mirrors the field about the 0.5 level too.
        field-fn (when smooth?
                   (let [f (style->field-fn style opts)]
                     (if invert? (fn [a t] (- 1.0 (f a t))) f)))]
    (shape-fn shape-or-fn
              (fn [s t]
                (let [center (shape-centroid s)
                      pts (:points s)
                      ang (fn [p] (Math/atan2 (- (second p) (second center))
                                              (- (first p) (first center))))
                      values (mapv (fn [p]
                                     (let [v (thickness-fn (ang p) t)]
                                       (if (< v threshold) 0.0 (max 0.0 (min 1.0 v)))))
                                   pts)]
                  (cond-> (assoc s
                                 :shell-mode true
                                 :shell-thickness thickness
                                 :shell-values values)
                    ;; :lattice cuts just above 0.5: its longit=0 band-boundary
                    ;; rings sit at field exactly 0.5, which would make the iso
                    ;; cut land on grid vertices (degenerate caps/flaps). Nudging
                    ;; the level off that plateau keeps every crossing sub-grid.
                    smooth?    (assoc :shell-smooth true
                                      :shell-level (if (= style :lattice) 0.55 0.5)
                                      :shell-field (mapv #(field-fn (ang %) t) pts))
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
