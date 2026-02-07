;; NOTE: These test the LEGACY geometry operations module (path-to-points based).
;; The current extrusion engine is in turtle/extrusion.cljs, tested by
;; turtle/extrusion_test.cljs and turtle/internals_test.cljs.
(ns ridley.geometry.operations-test
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.geometry.operations :as ops]))

;; Simple square path in XY plane, closed
(def square-path
  {:type :path
   :segments [{:type :line :from [0 0 0] :to [1 0 0]}
              {:type :line :from [1 0 0] :to [1 1 0]}
              {:type :line :from [1 1 0] :to [0 1 0]}
              {:type :line :from [0 1 0] :to [0 0 0]}]})

(deftest extrude-square-produces-caps-and-sane-counts
  (testing "Extrude closed path creates caps and expected counts"
    (let [mesh (ops/extrude square-path [0 0 1] 1)
          n (count (:vertices mesh))]
      ;; path has 5 points (first == last), so 10 vertices expected
      (is (= 10 n) "5 base + 5 top vertices")
      ;; faces: side = 2*n/2 = n, caps add 2*(points-2) = 6, total 16
      (is (= 16 (count (:faces mesh))) "Side + cap faces count matches implementation")
      (is (= :extrude (:primitive mesh)) "Primitive tag preserved"))))

(deftest revolve-full-revolution-no-caps
  (testing "Full 360° revolve omits caps and yields expected face count"
    (let [mesh (ops/revolve square-path [0 1 0] 360 24)
          points 5   ; path includes closing point
          rings 24
          expected-faces (* rings (dec points) 2)] ; 2 triangles per quad
      (is (= (* rings points) (count (:vertices mesh))))
      (is (= expected-faces (count (:faces mesh)))))))

(deftest extrude-degenerate-path-yields-empty-mesh
  (testing "Extruding a path with <2 points returns empty mesh"
    (let [mesh (ops/extrude {:type :path :segments []} [0 0 1] 1)]
      (is (empty? (:vertices mesh)))
      (is (empty? (:faces mesh))))))
