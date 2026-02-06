(ns ridley.test-helpers
  "Shared test utilities for Ridley test suite."
  (:require [ridley.turtle.core :as t]))

(def epsilon 1e-6)

(defn approx=
  "Approximate equality for numbers."
  ([a b] (approx= a b epsilon))
  ([a b eps] (< (Math/abs (- a b)) eps)))

(defn vec-approx=
  "Approximate equality for 3D vectors."
  ([[x1 y1 z1] [x2 y2 z2]] (vec-approx= [x1 y1 z1] [x2 y2 z2] epsilon))
  ([[x1 y1 z1] [x2 y2 z2] eps]
   (and (approx= x1 x2 eps) (approx= y1 y2 eps) (approx= z1 z2 eps))))

(defn mesh-bounding-box
  "Calculate bounding box of a mesh.
   Returns {:min [x y z] :max [x y z] :size [w h d]}"
  [mesh]
  (when-let [vertices (seq (:vertices mesh))]
    (let [xs (map first vertices)
          ys (map second vertices)
          zs (map #(nth % 2) vertices)]
      {:min [(apply min xs) (apply min ys) (apply min zs)]
       :max [(apply max xs) (apply max ys) (apply max zs)]
       :size [(- (apply max xs) (apply min xs))
              (- (apply max ys) (apply min ys))
              (- (apply max zs) (apply min zs))]})))

(defn edge-face-count
  "Count how many faces each edge belongs to."
  [mesh]
  (reduce (fn [acc [a b c]]
            (-> acc
                (update (vec (sort [a b])) (fnil inc 0))
                (update (vec (sort [b c])) (fnil inc 0))
                (update (vec (sort [a c])) (fnil inc 0))))
          {}
          (:faces mesh)))

(defn watertight?
  "Check if mesh is watertight (every edge shared by exactly 2 faces)."
  [mesh]
  (let [counts (vals (edge-face-count mesh))]
    (and (seq counts)
         (every? #(= 2 %) counts))))

(defn face-normal
  "Calculate normal of a triangular face."
  [mesh face-idx]
  (let [verts (:vertices mesh)
        [a b c] (nth (:faces mesh) face-idx)
        v0 (nth verts a)
        v1 (nth verts b)
        v2 (nth verts c)
        e1 (t/v- v1 v0)
        e2 (t/v- v2 v0)]
    (t/normalize (t/cross e1 e2))))

(defn mesh-centroid
  "Calculate centroid of all mesh vertices."
  [mesh]
  (let [verts (:vertices mesh)
        n (count verts)]
    (when (pos? n)
      (t/v* (reduce t/v+ verts) (/ 1.0 n)))))

(defn face-centroid
  "Calculate centroid of a face."
  [mesh face-idx]
  (let [verts (:vertices mesh)
        face (nth (:faces mesh) face-idx)
        face-verts (mapv #(nth verts %) face)
        n (count face-verts)]
    (when (pos? n)
      (t/v* (reduce t/v+ face-verts) (/ 1.0 n)))))

(defn all-normals-outward?
  "Check that all face normals point away from mesh centroid."
  [mesh]
  (let [centroid (mesh-centroid mesh)
        n-faces (count (:faces mesh))]
    (every?
      (fn [idx]
        (let [fc (face-centroid mesh idx)
              normal (face-normal mesh idx)
              to-outside (t/v- fc centroid)]
          (pos? (t/dot normal to-outside))))
      (range n-faces))))
