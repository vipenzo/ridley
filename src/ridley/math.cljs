(ns ridley.math
  "Shared vector utilities for geometry and turtle operations.
   Centralizing here avoids divergence between turtle and geometry code."
  (:require [clojure.core :as core]))

(defn v+
  "Vector addition."
  [[x1 y1 z1] [x2 y2 z2]]
  [(+ x1 x2) (+ y1 y2) (+ z1 z2)])

(defn v-
  "Vector subtraction."
  [[x1 y1 z1] [x2 y2 z2]]
  [(- x1 x2) (- y1 y2) (- z1 z2)])

(defn v*
  "Scalar multiplication."
  [[x y z] s]
  [(* x s) (* y s) (* z s)])

(defn dot
  "Dot product."
  [[x1 y1 z1] [x2 y2 z2]]
  (+ (* x1 x2) (* y1 y2) (* z1 z2)))

(defn cross
  "Cross product."
  [[x1 y1 z1] [x2 y2 z2]]
  [(- (* y1 z2) (* z1 y2))
   (- (* z1 x2) (* x1 z2))
   (- (* x1 y2) (* y1 x2))])

(defn magnitude
  "Vector length."
  [[x y z]]
  (Math/sqrt (+ (* x x) (* y y) (* z z))))

(defn normalize
  "Return unit vector; zero vector unchanged."
  [v]
  (let [m (magnitude v)]
    (if (zero? m)
      v
      (v* v (/ 1 m)))))

(defn rotate-point-around-axis
  "Rotate point v around axis by angle (radians) using Rodrigues' formula.
   Preserves vector magnitude - use for position vectors."
  [v axis angle]
  (let [k (normalize axis)
        cos-a (Math/cos angle)
        sin-a (Math/sin angle)
        term1 (v* v cos-a)
        term2 (v* (cross k v) sin-a)
        term3 (v* k (* (dot k v) (- 1 cos-a)))]
    (v+ (v+ term1 term2) term3)))

(defn rotate-around-axis
  "Rotate direction vector v around axis by angle (radians) using Rodrigues' formula.
   Result is normalized - use for direction vectors."
  [v axis angle]
  (normalize (rotate-point-around-axis v axis angle)))
