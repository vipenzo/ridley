(ns ridley.turtle.transform
  "2D shape transformations for loft operations.

   All functions work on shapes: {:type :shape :points [[x y]...] :centered? bool}
   Transform functions take a shape and return a new shape with transformed points."
  (:require [ridley.turtle.shape :as shape :refer [shape?]]))

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
   (assert-shape shape "scale")
   (shape/scale-shape shape factor))
  ([shape fx fy]
   (assert-shape shape "scale")
   (shape/scale-shape shape fx fy)))

(defn rotate
  "Rotate a shape by angle (degrees) around origin."
  [shape angle-deg]
  (assert-shape shape "rotate")
  (let [angle (/ (* angle-deg Math/PI) 180)
        cos-a (Math/cos angle)
        sin-a (Math/sin angle)
        rotate-fn (fn [[x y]]
                    [(- (* x cos-a) (* y sin-a))
                     (+ (* x sin-a) (* y cos-a))])
        rotated (mapv rotate-fn (:points shape))
        new-holes (when (:holes shape)
                    (mapv (fn [hole] (mapv rotate-fn hole)) (:holes shape)))]
    (cond-> (assoc shape :points rotated)
      new-holes (assoc :holes new-holes))))

(defn translate
  "Translate a shape by [dx dy]."
  [shape dx dy]
  (assert-shape shape "translate")
  (let [translate-fn (fn [[x y]] [(+ x dx) (+ y dy)])
        translated (mapv translate-fn (:points shape))
        new-holes (when (:holes shape)
                    (mapv (fn [hole] (mapv translate-fn hole)) (:holes shape)))]
    (cond-> (assoc shape :points translated)
      new-holes (assoc :holes new-holes))))

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
        n-b (count points-b)
        lerp-fn (fn [[ax ay] [bx by]]
                  [(+ ax (* t (- bx ax)))
                   (+ ay (* t (- by ay)))])]
    (if (not= n-a n-b)
      shape-a
      (let [interpolated (mapv lerp-fn points-a points-b)
            ;; Interpolate holes if both shapes have matching holes
            holes-a (:holes shape-a)
            holes-b (:holes shape-b)
            new-holes (when (and holes-a holes-b (= (count holes-a) (count holes-b)))
                        (mapv (fn [ha hb]
                                (if (= (count ha) (count hb))
                                  (mapv lerp-fn ha hb)
                                  ha))
                              holes-a holes-b))]
        (cond-> (assoc shape-a :points interpolated)
          new-holes (assoc :holes new-holes))))))

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

(defn- sample-at-perimeter-fractions
  "Walk a closed contour and sample points at given perimeter fractions (0-1).
   Returns a vector of [x y] points, one per fraction."
  [points fractions]
  (let [n-orig (count points)
        segments (map vector points (concat (rest points) [(first points)]))
        lengths (mapv (fn [[p1 p2]] (segment-length p1 p2)) segments)
        total-length (reduce + lengths)
        ;; Build cumulative distance array for efficient lookup
        cum-lengths (vec (reductions + 0 lengths))]
    (mapv (fn [frac]
            (let [target (* frac total-length)
                  ;; Binary-search-like: find segment containing target distance
                  seg-idx (loop [i 0]
                            (if (or (>= i n-orig)
                                    (> (nth cum-lengths (inc i)) target))
                              (min i (dec n-orig))
                              (recur (inc i))))
                  seg-start (nth cum-lengths seg-idx)
                  seg-len (nth lengths seg-idx)
                  [p1 p2] (nth segments seg-idx)
                  local-t (if (> seg-len 0)
                            (/ (- target seg-start) seg-len)
                            0)]
              (interpolate-point p1 p2 (min 1.0 (max 0.0 local-t)))))
          fractions)))

(defn perimeter-fractions
  "Compute the perimeter fraction (0-1) of each vertex in a closed shape.
   Returns a vector of fractions, one per point."
  [shape]
  (assert-shape shape "perimeter-fractions")
  (let [points (:points shape)
        n (count points)
        segments (map vector points (concat (rest points) [(first points)]))
        lengths (mapv (fn [[p1 p2]] (segment-length p1 p2)) segments)
        total-length (reduce + lengths)
        cum-lengths (vec (reductions + 0 lengths))]
    (if (zero? total-length)
      (vec (repeat n 0))
      (mapv #(/ % total-length) (subvec cum-lengths 0 n)))))

(defn resample-matched
  "Resample target-shape so each point corresponds to the same perimeter
   fraction as the reference-shape's points. Both shapes must be closed
   contours. The reference-shape's point distribution is preserved."
  [reference-shape target-shape]
  (assert-shape reference-shape "resample-matched (reference)")
  (assert-shape target-shape "resample-matched (target)")
  (let [fracs (perimeter-fractions reference-shape)
        ;; Find the starting point on target that best matches reference's first point angle
        ref-angle (Math/atan2 (second (first (:points reference-shape)))
                              (first (first (:points reference-shape))))
        tgt-points (:points target-shape)
        n-tgt (count tgt-points)
        best-idx (reduce
                  (fn [best i]
                    (let [p (nth tgt-points i)
                          a (Math/atan2 (second p) (first p))
                          diff-curr (Math/abs (let [d (- a ref-angle)]
                                                (cond (> d Math/PI) (- d (* 2 Math/PI))
                                                      (< d (- Math/PI)) (+ d (* 2 Math/PI))
                                                      :else d)))
                          p-best (nth tgt-points best)
                          a-best (Math/atan2 (second p-best) (first p-best))
                          diff-best (Math/abs (let [d (- a-best ref-angle)]
                                                (cond (> d Math/PI) (- d (* 2 Math/PI))
                                                      (< d (- Math/PI)) (+ d (* 2 Math/PI))
                                                      :else d)))]
                      (if (< diff-curr diff-best) i best)))
                  0
                  (range n-tgt))
        ;; Rotate target points to align starting position
        rotated-pts (vec (concat (drop best-idx tgt-points) (take best-idx tgt-points)))
        rotated-shape (assoc target-shape :points rotated-pts)
        ;; Sample at the reference's perimeter fractions
        new-points (sample-at-perimeter-fractions (:points rotated-shape) fracs)]
    (assoc target-shape :points new-points)))

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

;; ============================================================
;; Point alignment for morphing
;; ============================================================

(defn- point-angle
  "Calculate the angle (radians) of a 2D point from origin."
  [[x y]]
  (Math/atan2 y x))

(defn- angle-diff
  "Calculate the smallest difference between two angles (radians)."
  [a1 a2]
  (let [diff (- a2 a1)
        ;; Normalize to [-π, π]
        normalized (cond
                     (> diff Math/PI) (- diff (* 2 Math/PI))
                     (< diff (- Math/PI)) (+ diff (* 2 Math/PI))
                     :else diff)]
    (Math/abs normalized)))

(defn align-to-shape
  "Rotate shape2's point array so its starting point aligns angularly
   with shape1's starting point. This helps create smoother morphs
   between shapes with different topologies (e.g., rect to circle).

   Both shapes must have the same number of points and be centered."
  [shape1 shape2]
  (assert-shape shape1 "align-to-shape")
  (assert-shape shape2 "align-to-shape")
  (let [pts1 (:points shape1)
        pts2 (:points shape2)
        n (count pts2)]
    (if (or (zero? n) (not= (count pts1) n))
      shape2  ;; Can't align if different point counts
      (let [;; Find the angle of shape1's first point
            target-angle (point-angle (first pts1))
            ;; Find which point in shape2 is closest to that angle
            best-idx (reduce
                      (fn [best-idx idx]
                        (let [angle (point-angle (nth pts2 idx))
                              curr-diff (angle-diff target-angle angle)
                              best-diff (angle-diff target-angle (point-angle (nth pts2 best-idx)))]
                          (if (< curr-diff best-diff) idx best-idx)))
                      0
                      (range n))
            ;; Rotate the point array to start from best-idx
            rotated-pts (vec (concat (drop best-idx pts2) (take best-idx pts2)))]
        (assoc shape2 :points rotated-pts)))))
