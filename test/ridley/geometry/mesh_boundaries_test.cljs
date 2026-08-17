(ns ridley.geometry.mesh-boundaries-test
  "`mesh-diagnose` says WHERE a mesh is open, not only how much.

   Six open edges is one hexagonal hole or two triangular ones, and those are
   different diseases: two identical holes facing each other on parallel caps
   accuse the OUTLINE (the same polygon triangulated twice, failing twice), while
   one hole on a side wall accuses something else. A count cannot be looked at;
   a place can."
  (:require [cljs.test :refer [deftest is testing]]
            [ridley.geometry.mesh-utils :as mu]
            [ridley.geometry.primitives :as prims]
            [clojure.set]))

(defn- drop-faces
  "The mesh without the faces at `idxs` — a hole of exactly that shape."
  [mesh idxs]
  (let [drop? (set idxs)]
    (assoc mesh :faces (vec (keep-indexed (fn [i f] (when-not (drop? i) f))
                                          (:faces mesh))))))

(defn- dist [a b]
  (Math/sqrt (reduce + 0.0 (map #(let [d (- (nth a %) (nth b %))] (* d d)) [0 1 2]))))

(deftest a-closed-mesh-reports-no-boundaries
  (let [d (mu/mesh-diagnose (prims/box-mesh 10 10 10))]
    (is (:is-watertight? d))
    (is (nil? (:boundaries d))
        "nothing to point at, so nothing is printed — the key is absent, not empty")))

(deftest one-missing-triangle-is-one-hole-named-and-placed
  (let [box (prims/box-mesh 10 10 10)
        [a b c] (first (:faces box))
        centre (mapv (fn [i] (/ (+ (nth (nth (:vertices box) a) i)
                                   (nth (nth (:vertices box) b) i)
                                   (nth (nth (:vertices box) c) i))
                                3.0))
                     [0 1 2])
        d (mu/mesh-diagnose (drop-faces box [0]))]
    (is (= 3 (:open-edges d)))
    (is (= 1 (count (:boundaries d))) "three open edges around ONE triangle")
    (let [h (first (:boundaries d))]
      (is (= 3 (:edges h)))
      (is (< (dist (:centre h) centre) 1e-9)
          "and it is placed where the missing triangle was")
      (is (pos? (:size-mm h))))))

(deftest two-missing-triangles-are-TWO-holes-not-one-big-one
  (testing "the distinction the count cannot make

   This is the shape of the real case (2026-08-17): an extruded outline whose two
   flat caps each lost a triangle, reported as `open-edges 6` with a Euler
   characteristic of 0. Six edges could have been one hexagonal gap; it was two
   triangles, and the fact that they sat at the SAME place on the two parallel
   caps is what accused the outline rather than the extrusion."
    (let [box (prims/box-mesh 10 10 10)
          faces (:faces box)
          ;; two faces that share no vertex, so the holes are genuinely separate
          j (first (keep-indexed
                    (fn [i f] (when (and (pos? i) (empty? (clojure.set/intersection
                                                           (set f) (set (first faces)))))
                                i))
                    faces))
          d (mu/mesh-diagnose (drop-faces box [0 j]))]
      (is (= 6 (:open-edges d)))
      (is (= 2 (count (:boundaries d)))
          "six open edges, two holes — the count alone could not have said so")
      (is (every? #(= 3 (:edges %)) (:boundaries d)))
      (is (> (dist (:centre (first (:boundaries d)))
                   (:centre (second (:boundaries d))))
             1.0)
          "and they are reported apart, which is what makes them two"))))

(deftest a-hole-of-several-triangles-is-still-one-boundary
  (testing "adjacent missing faces make ONE bigger hole, not several"
    (let [box (prims/box-mesh 10 10 10)
          faces (:faces box)
          ;; face 0 and whichever face shares an edge with it
          share (first (keep-indexed
                        (fn [i f] (when (and (pos? i)
                                             (= 2 (count (clojure.set/intersection
                                                          (set f) (set (first faces))))))
                                    i))
                        faces))
          d (mu/mesh-diagnose (drop-faces box [0 share]))]
      (is (= 1 (count (:boundaries d)))
          "two adjacent triangles removed leave a single quadrilateral gap")
      (is (= 4 (:edges (first (:boundaries d))))
          "and its rim is four edges, not six — the shared edge closed up"))))
