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

(defn closest-point-on-line
  "Point on the infinite line through `line-origin` with direction `line-dir`
   closest to the infinite line through `ray-origin` with direction `ray-dir`
   (the standard skew-line closest-point problem — e.g. a pointer ray vs a gizmo
   axis). Returns nil if the two lines are parallel (within eps)."
  [ray-origin ray-dir line-origin line-dir]
  (let [rd (normalize ray-dir)
        ld (normalize line-dir)
        w0 (v- ray-origin line-origin)
        a (dot rd rd)
        b (dot rd ld)
        c (dot ld ld)
        d (dot rd w0)
        e (dot ld w0)
        denom (- (* a c) (* b b))]
    (when (> (Math/abs denom) 1e-9)
      (let [t-line (/ (- (* a e) (* b d)) denom)]
        (v+ line-origin (v* ld t-line))))))

(defn signed-angle-around-axis
  "Signed angle (radians) from `v-from` to `v-to`, measured around `axis` using the
   same right-hand-rule convention as rotate-around-axis/rotate-point-around-axis
   (rotating v-from by +this-angle around axis reproduces v-to's direction).
   v-from/v-to need not be unit length or already perpendicular to axis — both are
   projected onto the plane perpendicular to axis first."
  [v-from v-to axis]
  (let [k (normalize axis)
        proj (fn [v] (v- v (v* k (dot v k))))
        a (proj v-from)
        b (proj v-to)]
    (Math/atan2 (dot (cross a b) k) (dot a b))))
