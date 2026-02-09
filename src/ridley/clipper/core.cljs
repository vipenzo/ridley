(ns ridley.clipper.core
  "Wrapper for Clipper2: boolean operations and offset on 2D shapes.

   Provides shape-union, shape-difference, shape-intersection, shape-xor,
   and shape-offset for combining/modifying 2D shapes before extrusion.

   Uses clipper2-js (pure TypeScript port of Clipper2).
   Internally scales coordinates by 1000 for integer precision."
  (:require ["clipper2-js" :as c2]
            [ridley.turtle.shape :as shape]))

;; Clipper2 works with integer coordinates internally.
;; Scale factor: multiply before sending to Clipper, divide on return.
(def ^:private SCALE 1000)

;; --- Static method wrappers (preserve `this` binding) ---

(defn- c-intersect [subject clip fill-rule]
  (.call (.-Intersect c2/Clipper) c2/Clipper subject clip fill-rule))

(defn- c-union [subject clip fill-rule]
  (.call (.-Union c2/Clipper) c2/Clipper subject clip fill-rule))

(defn- c-difference [subject clip fill-rule]
  (.call (.-Difference c2/Clipper) c2/Clipper subject clip fill-rule))

(defn- c-xor [subject clip fill-rule]
  (.call (.-Xor c2/Clipper) c2/Clipper subject clip fill-rule))

(defn- c-inflate-paths [paths delta join-type end-type]
  (.call (.-InflatePaths c2/Clipper) c2/Clipper paths delta join-type end-type))

;; --- Coordinate conversion ---

(defn- point->clipper
  "Convert a Ridley [x y] point to a Clipper Point64 (scaled)."
  [[x y]]
  (c2/Point64. (Math/round (* x SCALE))
               (Math/round (* y SCALE))))

(defn- clipper->point
  "Convert a Clipper IPoint64 to a Ridley [x y] point (unscaled)."
  [pt]
  [(/ (.-x pt) SCALE)
   (/ (.-y pt) SCALE)])

(defn- points->clipper-path
  "Convert a vector of [x y] points to a Clipper Path64."
  [points]
  (let [path (c2/Path64.)]
    (doseq [pt points]
      (.push path (point->clipper pt)))
    path))

(defn- clipper-path->points
  "Convert a Clipper Path64 to a vector of [x y] points."
  [path]
  (vec (for [i (range (.-length path))]
         (clipper->point (aget path i)))))

(defn- shape->clipper-paths
  "Convert a Ridley shape (with optional holes) to Clipper Paths64.
   Returns a Paths64 containing [outer-path hole1 hole2 ...]."
  [shape]
  (let [paths (c2/Paths64.)]
    (.push paths (points->clipper-path (:points shape)))
    (when-let [holes (:holes shape)]
      (doseq [hole holes]
        (.push paths (points->clipper-path hole))))
    paths))

;; --- Winding helpers ---

(defn- signed-area-2d
  "Signed area of 2D polygon. Positive = CCW, Negative = CW."
  [points]
  (let [n (count points)]
    (if (< n 3)
      0
      (/ (reduce
          (fn [sum i]
            (let [[x1 y1] (nth points i)
                  [x2 y2] (nth points (mod (inc i) n))]
              (+ sum (- (* x1 y2) (* x2 y1)))))
          0
          (range n))
         2))))

(defn- ensure-ccw
  "Ensure points are CCW (positive signed area)."
  [points]
  (if (neg? (signed-area-2d points))
    (vec (reverse points))
    points))

(defn- ensure-cw
  "Ensure points are CW (negative signed area)."
  [points]
  (if (pos? (signed-area-2d points))
    (vec (reverse points))
    points))

;; --- Result classification ---

(defn- paths-result->shape
  "Convert Clipper result Paths64 to a Ridley shape.
   Classifies paths by area: positive area = outer (CCW), negative = hole (CW).
   Returns the largest outer with all holes, or nil if empty."
  [result]
  (when (pos? (.-length result))
    (let [path-data (vec (for [i (range (.-length result))]
                           (let [path (aget result i)
                                 pts (clipper-path->points path)
                                 area (signed-area-2d pts)]
                             {:points pts :area area})))
          outers (filterv #(pos? (:area %)) path-data)
          holes (filterv #(neg? (:area %)) path-data)]
      (when (seq outers)
        (let [largest (apply max-key :area outers)
              hole-pts (mapv #(ensure-cw (:points %)) holes)]
          (shape/make-shape (ensure-ccw (:points largest))
                            (cond-> {:centered? true}
                              (seq hole-pts) (assoc :holes hole-pts))))))))

;; --- Boolean operations ---

(defn- clipper-boolean
  "Execute a Clipper2 boolean operation.
   Returns a Ridley shape (outer + holes) or nil."
  [op-fn shape-a shape-b]
  (let [subject-paths (shape->clipper-paths shape-a)
        clip-paths (shape->clipper-paths shape-b)
        result (op-fn subject-paths clip-paths c2/FillRule.NonZero)]
    (paths-result->shape result)))

(defn ^:export shape-union
  "Boolean union of two 2D shapes. Returns the combined shape."
  [shape-a shape-b]
  (or (clipper-boolean c-union shape-a shape-b)
      shape-a))

(defn ^:export shape-difference
  "Boolean difference of two 2D shapes (A minus B).
   Returns shape-a with shape-b cut out."
  [shape-a shape-b]
  (or (clipper-boolean c-difference shape-a shape-b)
      shape-a))

(defn ^:export shape-intersection
  "Boolean intersection of two 2D shapes.
   Returns the overlapping region."
  [shape-a shape-b]
  (clipper-boolean c-intersect shape-a shape-b))

(defn ^:export shape-xor
  "Boolean XOR of two 2D shapes.
   Returns the non-overlapping regions."
  [shape-a shape-b]
  (clipper-boolean c-xor shape-a shape-b))

;; --- Offset ---

(def ^:private join-type-map
  {:round  c2/JoinType.Round
   :square c2/JoinType.Square
   :miter  c2/JoinType.Miter})

(defn ^:export shape-offset
  "Expand (positive delta) or contract (negative delta) a 2D shape.
   join-type: :round (default), :square, :miter"
  [shape delta & {:keys [join-type] :or {join-type :round}}]
  (let [paths (shape->clipper-paths shape)
        jt (get join-type-map join-type c2/JoinType.Round)
        scaled-delta (* delta SCALE)
        result (c-inflate-paths paths scaled-delta jt c2/EndType.Polygon)]
    (paths-result->shape result)))
