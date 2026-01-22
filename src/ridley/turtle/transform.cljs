(ns ridley.turtle.transform
  "2D shape transformations for loft operations.

   All functions work on shapes: {:type :shape :points [[x y]...] :centered? bool}
   Transform functions take a shape and return a new shape with transformed points."
  (:require [ridley.turtle.shape :refer [shape?]]))

(defn- assert-shape
  "Validate that x is a 2D shape, throw descriptive error if not."
  [x fn-name]
  (when-not (shape? x)
    (throw (js/Error. (str fn-name " expects a 2D shape, got "
                           (if (map? x)
                             (or (:type x) "map without :type")
                             (type x)))))))

;; ============================================================
;; Basic transformations
;; ============================================================

(defn scale
  "Scale a shape uniformly or non-uniformly.
   (scale shape factor)      - uniform scale
   (scale shape fx fy)       - non-uniform scale"
  ([shape factor]
   (scale shape factor factor))
  ([shape fx fy]
   (assert-shape shape "scale")
   (let [points (:points shape)
         scaled (mapv (fn [[x y]] [(* x fx) (* y fy)]) points)]
     (assoc shape :points scaled))))

(defn rotate
  "Rotate a shape by angle (degrees) around origin."
  [shape angle-deg]
  (assert-shape shape "rotate")
  (let [angle (/ (* angle-deg Math/PI) 180)
        cos-a (Math/cos angle)
        sin-a (Math/sin angle)
        points (:points shape)
        rotated (mapv (fn [[x y]]
                        [(- (* x cos-a) (* y sin-a))
                         (+ (* x sin-a) (* y cos-a))])
                      points)]
    (assoc shape :points rotated)))

(defn translate
  "Translate a shape by [dx dy]."
  [shape dx dy]
  (assert-shape shape "translate")
  (let [points (:points shape)
        translated (mapv (fn [[x y]] [(+ x dx) (+ y dy)]) points)]
    (assoc shape :points translated)))

;; ============================================================
;; Morphing / Interpolation
;; ============================================================

(defn morph
  "Linearly interpolate between two shapes.
   Both shapes must have the same number of points.
   t=0 gives shape-a, t=1 gives shape-b."
  [shape-a shape-b t]
  (assert-shape shape-a "morph")
  (assert-shape shape-b "morph")
  (let [points-a (:points shape-a)
        points-b (:points shape-b)
        n-a (count points-a)
        n-b (count points-b)]
    (if (not= n-a n-b)
      ;; Different point counts - return shape-a unchanged
      ;; (user should use resample first)
      shape-a
      ;; Interpolate each point
      (let [interpolated (mapv (fn [[ax ay] [bx by]]
                                 [(+ ax (* t (- bx ax)))
                                  (+ ay (* t (- by ay)))])
                               points-a points-b)]
        (assoc shape-a :points interpolated)))))

;; ============================================================
;; Resampling
;; ============================================================

(defn- segment-length
  "Calculate length of segment from p1 to p2."
  [[x1 y1] [x2 y2]]
  (Math/sqrt (+ (* (- x2 x1) (- x2 x1))
                (* (- y2 y1) (- y2 y1)))))

(defn- interpolate-point
  "Interpolate between two points."
  [[x1 y1] [x2 y2] t]
  [(+ x1 (* t (- x2 x1)))
   (+ y1 (* t (- y2 y1)))])

(defn resample
  "Resample a shape to have exactly n points.
   Points are distributed evenly along the perimeter."
  [shape n]
  (assert-shape shape "resample")
  (let [points (:points shape)
        n-orig (count points)
        ;; Calculate total perimeter (closed shape)
        segments (map vector points (concat (rest points) [(first points)]))
        lengths (mapv (fn [[p1 p2]] (segment-length p1 p2)) segments)
        total-length (reduce + lengths)
        ;; Distance between new points
        step (/ total-length n)
        ;; Walk along perimeter placing new points
        new-points
        (loop [result []
               seg-idx 0
               pos-in-seg 0.0
               accumulated 0.0
               target 0.0]
          (if (>= (count result) n)
            result
            (let [[p1 p2] (nth segments seg-idx)
                  seg-len (nth lengths seg-idx)
                  remaining-in-seg (- seg-len pos-in-seg)]
              (if (and (> seg-len 0) (<= (- target accumulated) (+ pos-in-seg remaining-in-seg)))
                ;; Point falls in this segment
                (let [t (/ (- target accumulated) seg-len)
                      new-point (interpolate-point p1 p2 t)]
                  (recur (conj result new-point)
                         seg-idx
                         (* t seg-len)
                         accumulated
                         (+ target step)))
                ;; Move to next segment
                (let [next-idx (mod (inc seg-idx) n-orig)]
                  (recur result
                         next-idx
                         0.0
                         (+ accumulated seg-len)
                         target))))))]
    (assoc shape :points (vec new-points))))
