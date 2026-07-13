(ns ridley.geometry.cut-candidates-test
  "Pure tests for cut-candidate geometry (dev-docs/brief-cut-candidates.md, Part 2):
   coplanar-face STEP detection with exact |ΔA| salience, and NECK detection on a
   sampled profile. No WASM — the section-area profile sampling itself lives in
   ridley.manifold.core behind the WASM-skip idiom (see dev-docs/code-issues.md
   'I test WASM Manifold skippano tutti in Node/CI')."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.geometry.cut-candidates :as cc]
            [ridley.geometry.primitives :as prim]
            [ridley.test-helpers :as h]))

(deftest vertex-offsets-and-range
  (testing "offsets along +Z from origin span the box's half-heights"
    (let [box (prim/box-mesh 40 40 10)          ; z ∈ [-5, 5]
          os (cc/vertex-offsets (:vertices box) [0 0 1] [0 0 0])]
      (is (h/approx= -5.0 (reduce min os) 1e-9))
      (is (h/approx= 5.0 (reduce max os) 1e-9))
      (is (= [-5.0 5.0] (cc/offset-range (:vertices box) [0 0 1] [0 0 0])))))
  (testing "empty mesh → nil range"
    (is (nil? (cc/offset-range [] [0 0 1] [0 0 0])))))

(deftest offset->pose-moves-only-along-heading
  (let [p (cc/offset->pose 7.0 [0 0 1] [0 1 0] [1 2 3])]
    (is (h/vec-approx= [1 2 10] (:position p) 1e-9) "position shifted +7 along Z only")
    (is (= [0 0 1] (:heading p)))
    (is (= [0 1 0] (:up p)))))

(deftest step-candidates-box-two-caps
  (testing "a box swept along Z has exactly two steps — its top and bottom caps —
            each with salience = the box's XY section area (|ΔA|), summed over the
            two coplanar triangles of each cap"
    (let [box (prim/box-mesh 40 30 10)          ; XY section = 1200
          steps (cc/step-candidates box [0 0 1] [0 0 0] {})
          by-off (sort-by :offset steps)]
      (is (= 2 (count steps)) "two caps, not the four ⊥ side faces")
      (is (h/approx= -5.0 (:offset (first by-off)) 1e-6))
      (is (h/approx= 5.0 (:offset (second by-off)) 1e-6))
      (is (h/approx= 1200.0 (:salience (first by-off)) 1e-6) "bottom cap |ΔA| = 40·30")
      (is (h/approx= 1200.0 (:salience (second by-off)) 1e-6) "top cap |ΔA| = 40·30")))
  (testing "swept along X the two caps are the ±X faces, salience = Y·Z section"
    (let [box (prim/box-mesh 40 30 10)          ; YZ section = 300
          steps (cc/step-candidates box [1 0 0] [0 0 0] {})]
      (is (= 2 (count steps)))
      (is (every? #(h/approx= 300.0 (:salience %) 1e-6) steps)))))

(deftest step-candidates-off-axis-none
  (testing "a diagonal heading grazes no face flush → no exact steps"
    (let [box (prim/box-mesh 40 30 10)
          h (let [d (js/Math.sqrt 3)] [(/ 1 d) (/ 1 d) (/ 1 d)])]
      (is (empty? (cc/step-candidates box h [0 0 0] {:angle-tol 1.0}))))))

(deftest profile-minima-finds-the-waist
  (testing "a dumbbell profile (high–low–high) yields one neck at the waist, depth =
            bell − waist; a monotone step yields none"
    (let [dumbbell (map-indexed (fn [i a] {:offset (* i 1.0) :area a})
                                [100 100 90 60 40 60 90 100 100])
          necks (cc/profile-minima dumbbell 1.0)]
      (is (= 1 (count necks)))
      (is (h/approx= 4.0 (:offset (first necks)) 1e-9) "waist at the index-4 minimum")
      (is (h/approx= 20.0 (:salience (first necks)) 1e-9) "depth = min(60,60) − 40"))
    (let [step (map-indexed (fn [i a] {:offset (* i 1.0) :area a})
                            [100 100 100 40 40 40])]
      (is (empty? (cc/profile-minima step 1.0)) "a step-down is not a valley")))
  (testing "a flat-bottomed valley collapses to its middle sample"
    (let [flat (map-indexed (fn [i a] {:offset (* i 1.0) :area a})
                            [100 50 50 50 100])]
      (is (= 1 (count (cc/profile-minima flat 1.0))))
      (is (h/approx= 2.0 (:offset (first (cc/profile-minima flat 1.0))) 1e-9))))
  (testing "sub-threshold dips are dropped"
    (let [shallow (map-indexed (fn [i a] {:offset (* i 1.0) :area a})
                               [100 99.5 100])]
      (is (empty? (cc/profile-minima shallow 1.0))))))
