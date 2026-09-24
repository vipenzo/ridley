(ns ridley.voronoi.spread-points-test
  "spread-points / shape-area / shape-offset-all (dev-docs/brief-joint-layout.md
   Parte 0). Pure 2D: clipper2-js and d3-delaunay both run in Node, so nothing
   here is WASM-gated."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.clipper.core :as clipper]
            [ridley.voronoi.core :as voronoi]
            [ridley.turtle.shape :as shape]))

(defn- rect-pts [w h] [[(- w) (- h)] [w (- h)] [w h] [(- w) h]])
(defn- circle-pts [r n]
  (vec (for [i (range n)] [(* r (Math/cos (* i (/ (* 2 Math/PI) n))))
                            (* r (Math/sin (* i (/ (* 2 Math/PI) n))))])))
(defn- inside? [[x y] shape]
  (and (clipper/point-in-polygon? [x y] (:points shape))
       (not-any? #(clipper/point-in-polygon? [x y] %) (:holes shape))))

(def ^:private rect-60x20 (shape/make-shape (rect-pts 30 10) {:centered? true}))
(def ^:private ring
  "Annulus 12..20 (8 wide): an inset of 1 leaves 13..19. (A 2-wide ring inset by
   1 vanishes entirely — the hole grows as the rim shrinks.)"
  (shape/make-shape (circle-pts 20 64) {:centered? true :holes [(vec (reverse (circle-pts 12 64)))]}))
;; a C: 20 wide (x −10..10), 60 tall, with x>−5, |y|<10 removed (5-wide spine on the left)
(def ^:private c-shape
  (shape/make-shape [[-10 -30] [10 -30] [10 -10] [-5 -10] [-5 10] [10 10] [10 30] [-10 30]]
                    {:centered? true}))

(deftest shape-area-rect-and-ring
  (is (< (Math/abs (- 1200 (clipper/shape-area rect-60x20))) 1e-6))
  (is (< (Math/abs (- (* Math/PI (- (* 20 20) (* 12 12))) (clipper/shape-area ring))) 3)
      "64-gon ring ≈ annulus area"))

(deftest shape-offset-all-splits-a-dumbbell-and-erases-a-needle
  (testing "a 2-wide neck vanishes at inset 2, leaving the two lobes as separate regions"
    (let [dumbbell (shape/make-shape [[-30 -10] [-10 -10] [-10 -1] [10 -1] [10 -10] [30 -10]
                                      [30 10] [10 10] [10 1] [-10 1] [-10 10] [-30 10]]
                                     {:centered? true})
          lobes (clipper/shape-offset-all dumbbell -2)]
      (is (= 2 (count lobes)))
      (is (every? #(> (clipper/shape-area %) 200) lobes))))
  (testing "a 6-wide strip is erased by inset 5"
    (is (= [] (clipper/shape-offset-all (shape/make-shape (rect-pts 20 3) {:centered? true}) -5))))
  (testing "a ring keeps its hole and yields ONE region"
    (let [r (clipper/shape-offset-all ring -1)]
      (is (= 1 (count r)))
      (is (= 1 (count (:holes (first r))))))))

(deftest spread-points-inside-deterministic-and-spread
  (let [a (voronoi/spread-points rect-60x20 6)
        b (voronoi/spread-points rect-60x20 6)
        c (voronoi/spread-points rect-60x20 6 :seed 3)]
    (is (= 6 (count a)))
    (is (every? #(inside? % rect-60x20) a))
    (is (= a b) "same seed → same points")
    (is (not= a c) "another seed → other points")
    (testing "no two points closer than 8 (60×20 with 6 points ≈ 10 apart)"
      (is (every? (fn [[p q]] (> (Math/sqrt (+ (Math/pow (- (p 0) (q 0)) 2) (Math/pow (- (p 1) (q 1)) 2))) 8))
                  (for [p a q a :when (not= p q)] [p q]))))))

(deftest spread-points-respects-holes-and-concavity
  (testing "ring: every point in the material, none in the hole"
    (is (every? #(inside? % ring) (voronoi/spread-points ring 6))))
  (testing "C with one point: the centroid is in the void, the point is not"
    (let [[p] (voronoi/spread-points c-shape 1)]
      (is (inside? p c-shape))))
  (testing "empty / degenerate input"
    (is (= [] (voronoi/spread-points nil 3)))
    (is (= [] (voronoi/spread-points rect-60x20 0)))))

(deftest deepest-point-of-a-c-is-in-an-arm
  (let [[x y] (voronoi/deepest-point c-shape)]
    (is (inside? [x y] c-shape))
    (is (> (Math/abs y) 10) "the arms are 20 deep, the spine only 5")))
