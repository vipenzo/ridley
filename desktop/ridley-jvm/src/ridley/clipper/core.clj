(ns ridley.clipper.core
  "2D boolean operations and offset via JTS (Java Topology Suite).
   Drop-in replacement for the clipper2-js CLJS version.

   Provides shape-union, shape-difference, shape-intersection, shape-xor,
   shape-offset, shape-hull, shape-bridge, and pattern-tile."
  (:require [ridley.turtle.shape :as shape])
  (:import [org.locationtech.jts.geom
            GeometryFactory Coordinate Polygon LinearRing Geometry]
           [org.locationtech.jts.operation.buffer BufferParameters BufferOp]))

(def ^:private ^GeometryFactory gf (GeometryFactory.))

;; ============================================================
;; Coordinate conversion
;; ============================================================

(defn- points->coords
  "Convert [[x y] ...] to Coordinate array. Closes the ring."
  [points]
  (let [pts (if (= (first points) (last points))
              points
              (conj (vec points) (first points)))]
    (into-array Coordinate
      (mapv (fn [[x y]] (Coordinate. (double x) (double y))) pts))))

(defn- coords->points
  "Convert Coordinate array to [[x y] ...]. Removes closing duplicate."
  [^"[Lorg.locationtech.jts.geom.Coordinate;" coords]
  (let [pts (mapv (fn [^Coordinate c] [(.-x c) (.-y c)]) coords)]
    (if (and (>= (count pts) 2) (= (first pts) (last pts)))
      (pop pts)
      pts)))

(defn- shape->polygon
  "Convert a Ridley shape (with optional holes) to a JTS Polygon."
  ^Polygon [s]
  (let [outer-ring (.createLinearRing gf (points->coords (:points s)))
        hole-rings (when-let [holes (:holes s)]
                     (into-array LinearRing
                       (mapv #(.createLinearRing gf (points->coords %)) holes)))]
    (if hole-rings
      (.createPolygon gf outer-ring hole-rings)
      (.createPolygon gf outer-ring nil))))

;; ============================================================
;; Winding helpers
;; ============================================================

(defn- signed-area-2d
  "Signed area of 2D polygon. Positive = CCW, Negative = CW."
  [points]
  (let [n (count points)]
    (if (< n 3)
      0.0
      (/ (reduce
           (fn [sum i]
             (let [[x1 y1] (nth points i)
                   [x2 y2] (nth points (mod (inc i) n))]
               (+ sum (- (* x1 y2) (* x2 y1)))))
           0.0
           (range n))
         2.0))))

(defn- ensure-ccw [points]
  (if (neg? (signed-area-2d points))
    (vec (reverse points))
    points))

(defn- ensure-cw [points]
  (if (pos? (signed-area-2d points))
    (vec (reverse points))
    points))

;; ============================================================
;; Deduplication
;; ============================================================

(defn dedup-consecutive
  "Remove consecutive duplicate points."
  [points]
  (if (< (count points) 2)
    points
    (let [result (reduce (fn [acc pt]
                           (if (= pt (peek acc))
                             acc
                             (conj acc pt)))
                         [(first points)]
                         (rest points))]
      (if (= (peek result) (first result))
        (pop result)
        result))))

;; ============================================================
;; JTS result → Ridley shapes
;; ============================================================

(defn- polygon->shape
  "Convert a JTS Polygon to a Ridley shape."
  [^Polygon poly]
  (let [outer-pts (-> poly .getExteriorRing .getCoordinates coords->points
                      dedup-consecutive ensure-ccw)
        n-holes (.getNumInteriorRing poly)
        holes (when (pos? n-holes)
                (vec (for [i (range n-holes)]
                       (-> poly (.getInteriorRingN i) .getCoordinates coords->points
                           dedup-consecutive ensure-cw))))]
    (when (>= (count outer-pts) 3)
      (shape/make-shape outer-pts
                        (cond-> {:centered? true}
                          (seq holes) (assoc :holes holes))))))

(defn- geometry->shape
  "Convert a JTS Geometry result to the largest Ridley shape."
  [^Geometry geom]
  (let [n (.getNumGeometries geom)
        shapes (keep (fn [i]
                       (let [g (.getGeometryN geom i)]
                         (when (instance? Polygon g)
                           (polygon->shape g))))
                     (range n))]
    (when (seq shapes)
      ;; Return the largest by area
      (apply max-key #(Math/abs (signed-area-2d (:points %))) shapes))))

(defn- geometry->shapes
  "Convert a JTS Geometry result to a vector of Ridley shapes."
  [^Geometry geom]
  (let [n (.getNumGeometries geom)]
    (vec (keep (fn [i]
                 (let [g (.getGeometryN geom i)]
                   (when (instance? Polygon g)
                     (polygon->shape g))))
               (range n)))))

;; ============================================================
;; Boolean operations
;; ============================================================

(defn shape-union
  "Boolean union of two 2D shapes."
  [shape-a shape-b]
  (let [pa (shape->polygon shape-a)
        pb (shape->polygon shape-b)]
    (or (geometry->shape (.union pa pb))
        shape-a)))

(defn shape-difference
  "Boolean difference (A minus B)."
  [shape-a shape-b]
  (let [pa (shape->polygon shape-a)
        pb (shape->polygon shape-b)]
    (or (geometry->shape (.difference pa pb))
        shape-a)))

(defn shape-intersection
  "Boolean intersection of two 2D shapes."
  [shape-a shape-b]
  (let [pa (shape->polygon shape-a)
        pb (shape->polygon shape-b)]
    (geometry->shape (.intersection pa pb))))

(defn shape-xor
  "Boolean XOR of two 2D shapes. Returns a vector of shapes."
  [shape-a shape-b]
  (let [pa (shape->polygon shape-a)
        pb (shape->polygon shape-b)
        result (.symDifference pa pb)]
    (let [shapes (geometry->shapes result)]
      (if (seq shapes) shapes [shape-a]))))

;; ============================================================
;; Offset (buffer)
;; ============================================================

(def ^:private join-type-map
  {:round  BufferParameters/JOIN_ROUND
   :square BufferParameters/JOIN_BEVEL
   :miter  BufferParameters/JOIN_MITRE})

(defn shape-offset
  "Expand (positive delta) or contract (negative delta) a 2D shape.
   join-type: :round (default), :square, :miter"
  [shape-or-shapes delta & {:keys [join-type] :or {join-type :round}}]
  (if (and (vector? shape-or-shapes) (seq shape-or-shapes) (map? (first shape-or-shapes)))
    (mapv #(shape-offset % delta :join-type join-type) shape-or-shapes)
    (let [poly (shape->polygon shape-or-shapes)
          jt (get join-type-map join-type BufferParameters/JOIN_ROUND)
          params (doto (BufferParameters.)
                   (.setJoinStyle jt)
                   (.setQuadrantSegments 8))
          result (BufferOp/bufferOp poly (double delta) params)]
      (or (geometry->shape result)
          shape-or-shapes))))

;; ============================================================
;; Convex hull (pure math, no JTS needed)
;; ============================================================

(defn- cross-2d [[ox oy] [ax ay] [bx by]]
  (- (* (- ax ox) (- by oy))
     (* (- ay oy) (- bx ox))))

(defn- convex-hull-points [points]
  (let [pts (vec (sort-by (fn [[x y]] [x y]) points))
        n (count pts)]
    (if (<= n 2)
      pts
      (let [build (fn [pts]
                    (reduce
                      (fn [hull p]
                        (let [hull (loop [h hull]
                                     (if (and (>= (count h) 2)
                                              (<= (cross-2d (nth h (- (count h) 2))
                                                            (peek h) p) 0))
                                       (recur (pop h))
                                       h))]
                          (conj hull p)))
                      [] pts))
            lower (build pts)
            upper (build (rseq pts))]
        (vec (concat (butlast lower) (butlast upper)))))))

(defn shape-hull
  "Convex hull of N 2D shapes."
  [& shapes]
  (let [all-points (into [] (mapcat :points) shapes)]
    (when (>= (count all-points) 3)
      (shape/make-shape (convex-hull-points all-points)
                        {:centered? true}))))

;; ============================================================
;; Bridge (offset-union-unoffset)
;; ============================================================

(defn shape-bridge
  "Connect N shapes by offset-union-unoffset."
  [& args]
  (let [shapes (vec (take-while map? args))
        opts (apply hash-map (drop-while map? args))
        radius (or (:radius opts) 1)
        join-type (or (:join-type opts) :round)]
    (if (< (count shapes) 2)
      (first shapes)
      (let [expanded (keep #(shape-offset % radius :join-type join-type) shapes)
            merged (reduce shape-union (first expanded) (rest expanded))]
        (when merged
          (shape-offset merged (- radius) :join-type join-type))))))

;; ============================================================
;; Point-in-polygon
;; ============================================================

(defn point-in-polygon?
  "Ray casting test: is point [px py] inside polygon?"
  [[px py] polygon]
  (let [n (count polygon)]
    (loop [i 0 j (dec n) inside false]
      (if (>= i n)
        inside
        (let [[xi yi] (nth polygon i)
              [xj yj] (nth polygon j)
              intersects? (and (not= (> yi py) (> yj py))
                               (< px (+ xi (* (/ (- xj xi) (- yj yi))
                                              (- py yi)))))]
          (recur (inc i) i (if intersects? (not inside) inside)))))))

;; ============================================================
;; Pattern tiling
;; ============================================================

(defn- bounding-box [points]
  (reduce (fn [[xn yn xx yx] [x y]]
            [(min xn x) (min yn y) (max xx x) (max yx y)])
          [Double/MAX_VALUE Double/MAX_VALUE
           (- Double/MAX_VALUE) (- Double/MAX_VALUE)]
          points))

(defn- translate-shape-2d [s dx dy]
  (let [translated-pts (mapv (fn [[x y]] [(+ x dx) (+ y dy)]) (:points s))]
    (shape/make-shape translated-pts {:centered? true})))

(defn pattern-tile
  "Tile a pattern across a target shape, then subtract.
   :spacing [sx sy] — tile period (default: pattern bbox size)
   :inset — shrink each pattern copy (default 0)"
  [target-shape pattern & {:keys [spacing inset] :or {inset 0}}]
  (let [patterns (if (and (vector? pattern) (seq pattern) (map? (first pattern)))
                   pattern [pattern])
        all-pat-pts (into [] (mapcat :points) patterns)
        [pxmin pymin pxmax pymax] (bounding-box all-pat-pts)
        pw (- pxmax pxmin) ph (- pymax pymin)
        [sx sy] (or spacing [pw ph])
        [txmin tymin txmax tymax] (bounding-box (:points target-shape))
        nx (int (Math/ceil (/ (- txmax txmin) sx)))
        ny (int (Math/ceil (/ (- tymax tymin) sy)))
        tcx (* 0.5 (+ txmin txmax))
        tcy (* 0.5 (+ tymin tymax))
        x0 (- tcx (* 0.5 nx sx))
        y0 (- tcy (* 0.5 ny sy))
        ;; Build union of all tiles using JTS directly for efficiency
        tile-geom (reduce
                    (fn [^Geometry acc tile]
                      (if acc (.union acc (shape->polygon tile))
                        (shape->polygon tile)))
                    nil
                    (for [ix (range (inc nx))
                          iy (range (inc ny))
                          pat patterns]
                      (let [dx (+ x0 (* ix sx))
                            dy (+ y0 (* iy sy))
                            shifted (translate-shape-2d pat dx dy)]
                        (if (pos? inset)
                          (shape-offset shifted (- inset))
                          shifted))))
        target-poly (shape->polygon target-shape)]
    (if tile-geom
      (or (geometry->shape (.difference target-poly tile-geom))
          target-shape)
      target-shape)))
