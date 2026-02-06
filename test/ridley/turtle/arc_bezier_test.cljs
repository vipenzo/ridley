(ns ridley.turtle.arc-bezier-test
  "Tests for arc and bezier path generation."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.turtle.core :as t]
            [ridley.turtle.shape :as shape]
            [ridley.test-helpers :as h]))

;; ═══════════════════════════════════════════════════════════
;; Arc Horizontal Commands
;; ═══════════════════════════════════════════════════════════

(deftest arc-h-quarter-circle
  (testing "arc-h 90 degrees turns heading 90 degrees and moves correctly"
    (let [turtle (-> (t/make-turtle)
                     (t/arc-h 10 90))
          ;; Quarter circle radius 10, heading should now be +Y
          ;; Position should be approximately [10, 10, 0]
          pos (:position turtle)]
      (is (h/approx= (first pos) 10 0.5) "X should be ~radius")
      (is (h/approx= (second pos) 10 0.5) "Y should be ~radius")
      (is (h/approx= (nth pos 2) 0 0.1) "Z should be 0")
      ;; Heading should be approximately +Y
      (is (h/approx= (first (:heading turtle)) 0 0.1) "Heading X ~ 0")
      (is (h/approx= (second (:heading turtle)) 1 0.1) "Heading Y ~ 1"))))

(deftest arc-h-full-circle
  (testing "arc-h 360 degrees returns to start"
    (let [turtle (-> (t/make-turtle)
                     (t/arc-h 15 360))
          pos (:position turtle)]
      (is (h/vec-approx= pos [0 0 0] 0.5) "Full circle should return near origin")
      (is (h/vec-approx= (:heading turtle) [1 0 0] 0.1) "Heading should return to +X"))))

(deftest arc-h-negative
  (testing "arc-h with negative angle turns right"
    (let [turtle (-> (t/make-turtle)
                     (t/arc-h 10 -90))
          pos (:position turtle)]
      ;; Should turn right: end at [10, -10, 0] heading -Y
      (is (h/approx= (first pos) 10 0.5) "X should be ~radius")
      (is (h/approx= (second pos) -10 0.5) "Y should be ~-radius"))))

(deftest arc-h-semicircle
  (testing "arc-h 180 degrees creates semicircle"
    (let [turtle (-> (t/make-turtle)
                     (t/arc-h 10 180))
          pos (:position turtle)]
      ;; Semicircle: end at [0, 20, 0] facing -X
      (is (h/approx= (first pos) 0 0.5) "X should return near 0")
      (is (h/approx= (second pos) 20 0.5) "Y should be 2*radius")
      (is (h/approx= (first (:heading turtle)) -1 0.1) "Should face -X"))))

;; ═══════════════════════════════════════════════════════════
;; Arc Vertical Commands
;; ═══════════════════════════════════════════════════════════

(deftest arc-v-quarter-circle
  (testing "arc-v 90 degrees pitches up and moves correctly"
    (let [turtle (-> (t/make-turtle)
                     (t/arc-v 10 90))
          pos (:position turtle)]
      ;; Should pitch up: end at approximately [10, 0, 10]
      (is (h/approx= (first pos) 10 0.5) "X should be ~radius")
      (is (h/approx= (nth pos 2) 10 0.5) "Z should be ~radius"))))

(deftest arc-v-negative
  (testing "arc-v with negative angle pitches down"
    (let [turtle (-> (t/make-turtle)
                     (t/arc-v 10 -90))
          pos (:position turtle)]
      ;; Should pitch down: end at [10, 0, -10]
      (is (h/approx= (first pos) 10 0.5) "X should be ~radius")
      (is (h/approx= (nth pos 2) -10 0.5) "Z should be ~-radius"))))

(deftest arc-v-semicircle
  (testing "arc-v 180 degrees creates vertical semicircle"
    (let [turtle (-> (t/make-turtle)
                     (t/arc-v 10 180))
          pos (:position turtle)]
      ;; Semicircle up: end at [0, 0, 20] facing -X
      (is (h/approx= (first pos) 0 0.5) "X should return near 0")
      (is (h/approx= (nth pos 2) 20 0.5) "Z should be 2*radius"))))

;; ═══════════════════════════════════════════════════════════
;; Arc with Custom Steps
;; ═══════════════════════════════════════════════════════════

(deftest arc-h-custom-steps
  (testing "arc-h with custom steps parameter"
    (let [turtle (-> (t/make-turtle)
                     (t/arc-h 10 90 :steps 4))
          pos (:position turtle)]
      ;; Should still end at approximately the same position
      (is (h/approx= (first pos) 10 1) "X should be ~radius")
      (is (h/approx= (second pos) 10 1) "Y should be ~radius"))))

(deftest arc-v-custom-steps
  (testing "arc-v with custom steps parameter"
    (let [turtle (-> (t/make-turtle)
                     (t/arc-v 10 90 :steps 4))
          pos (:position turtle)]
      (is (h/approx= (first pos) 10 1) "X should be ~radius")
      (is (h/approx= (nth pos 2) 10 1) "Z should be ~radius"))))

;; ═══════════════════════════════════════════════════════════
;; Bezier Commands
;; ═══════════════════════════════════════════════════════════

(deftest bezier-to-basic-test
  (testing "Bezier-to moves turtle to target position"
    (let [turtle (-> (t/make-turtle)
                     (t/bezier-to [50 30 0]))
          pos (:position turtle)]
      ;; Should end at approximately [50, 30, 0]
      (is (h/approx= (first pos) 50 0.1) "X should be 50")
      (is (h/approx= (second pos) 30 0.1) "Y should be 30"))))

(deftest bezier-to-3d-test
  (testing "Bezier-to works in 3D"
    (let [turtle (-> (t/make-turtle)
                     (t/bezier-to [30 20 15]))
          pos (:position turtle)]
      (is (h/approx= (first pos) 30 0.1))
      (is (h/approx= (second pos) 20 0.1))
      (is (h/approx= (nth pos 2) 15 0.1)))))

(deftest bezier-to-with-control-points
  (testing "Bezier-to with explicit control points"
    (let [turtle (-> (t/make-turtle)
                     (t/bezier-to [40 0 0] [10 20 0] [30 20 0]))
          pos (:position turtle)]
      ;; Should end at target
      (is (h/approx= (first pos) 40 0.1) "X should be 40")
      (is (h/approx= (second pos) 0 0.1) "Y should be 0"))))

(deftest bezier-to-with-single-control-point
  (testing "Bezier-to with single control point (quadratic)"
    (let [turtle (-> (t/make-turtle)
                     (t/bezier-to [40 0 0] [20 20 0]))
          pos (:position turtle)]
      ;; Should end at target
      (is (h/approx= (first pos) 40 0.1) "X should be 40")
      (is (h/approx= (second pos) 0 0.1) "Y should be 0"))))

(deftest bezier-to-with-steps
  (testing "Bezier-to with custom steps"
    (let [turtle (-> (t/make-turtle)
                     (t/bezier-to [50 0 0] :steps 32))
          pos (:position turtle)]
      (is (h/approx= (first pos) 50 0.1) "X should be 50"))))

;; ═══════════════════════════════════════════════════════════
;; Bezier Determinism
;; ═══════════════════════════════════════════════════════════

(deftest bezier-deterministic
  (testing "Same bezier inputs produce same output"
    (let [run-bezier (fn []
                       (:position (-> (t/make-turtle)
                                      (t/bezier-to [50 30 10]))))
          pos1 (run-bezier)
          pos2 (run-bezier)]
      (is (h/vec-approx= pos1 pos2) "Bezier should be deterministic"))))

;; ═══════════════════════════════════════════════════════════
;; Arc and Bezier Move Turtle
;; ═══════════════════════════════════════════════════════════

(deftest arc-h-moves-turtle
  (testing "arc-h moves turtle from origin"
    (let [turtle (-> (t/make-turtle)
                     (t/arc-h 20 90))
          pos (:position turtle)]
      (is (not (h/vec-approx= pos [0 0 0] 0.1)) "Turtle should have moved"))))

(deftest arc-v-moves-turtle
  (testing "arc-v moves turtle from origin"
    (let [turtle (-> (t/make-turtle)
                     (t/arc-v 20 90))
          pos (:position turtle)]
      (is (not (h/vec-approx= pos [0 0 0] 0.1)) "Turtle should have moved"))))

(deftest bezier-moves-turtle
  (testing "bezier-to moves turtle from origin"
    (let [turtle (-> (t/make-turtle)
                     (t/bezier-to [50 30 0]))
          pos (:position turtle)]
      (is (not (h/vec-approx= pos [0 0 0] 0.1)) "Turtle should have moved"))))

;; ═══════════════════════════════════════════════════════════
;; Arc Extrusion Integration
;; ═══════════════════════════════════════════════════════════

(deftest arc-like-extrusion-test
  (testing "Extrusion along arc-like path (simulated with small segments)"
    (let [rect (shape/rect-shape 4 4)
          ;; Simulate arc with 6 segments turning 15 degrees each = 90 total
          path (t/make-path [{:cmd :f :args [5]} {:cmd :th :args [15]}
                             {:cmd :f :args [5]} {:cmd :th :args [15]}
                             {:cmd :f :args [5]} {:cmd :th :args [15]}
                             {:cmd :f :args [5]} {:cmd :th :args [15]}
                             {:cmd :f :args [5]} {:cmd :th :args [15]}
                             {:cmd :f :args [5]} {:cmd :th :args [15]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path rect path))
          mesh (last (:meshes turtle))]
      (is (some? mesh) "Arc-like extrusion should create mesh")
      (is (pos? (count (:faces mesh))) "Should have faces"))))

;; ═══════════════════════════════════════════════════════════
;; Compute Bezier Control Points (Pure Function)
;; ═══════════════════════════════════════════════════════════

(deftest compute-bezier-control-points-straight-line
  (testing "Control points for straight line"
    (let [p0 [0 0 0]
          h0 [1 0 0]
          p3 [30 0 0]
          h1 [1 0 0]
          [c1 c2] (t/compute-bezier-control-points p0 h0 p3 h1 0.33 nil)]
      ;; For a straight line with same heading, control points should be along the line
      (is (> (first c1) 0) "c1 should be ahead of p0")
      (is (< (first c2) 30) "c2 should be behind p3")
      ;; Both should be on X axis (or very close)
      (is (h/approx= (second c1) 0 0.1) "c1 Y should be ~0")
      (is (h/approx= (second c2) 0 0.1) "c2 Y should be ~0"))))

(deftest compute-bezier-control-points-curved
  (testing "Control points for 90 degree turn"
    (let [p0 [0 0 0]
          h0 [1 0 0]  ;; heading +X
          p3 [30 30 0]
          h1 [0 1 0]  ;; heading +Y at end
          [c1 c2] (t/compute-bezier-control-points p0 h0 p3 h1 0.33 nil)]
      ;; c1 should be along initial heading (+X from p0)
      (is (> (first c1) 0) "c1 should extend in +X")
      ;; c2 should be along reverse of final heading (-Y from p3)
      (is (< (second c2) 30) "c2 should extend in -Y from p3"))))

;; ═══════════════════════════════════════════════════════════
;; Compound Arc Paths
;; ═══════════════════════════════════════════════════════════

(deftest compound-arc-path
  (testing "Multiple arcs create smooth curve"
    (let [turtle (-> (t/make-turtle)
                     (t/arc-h 10 45)
                     (t/arc-h 10 45)
                     (t/arc-h 10 45)
                     (t/arc-h 10 45))  ;; Total 180 degrees
          pos (:position turtle)]
      ;; Should be similar to single 180 arc with same radius
      (is (h/approx= (first pos) 0 2) "X should return near 0")
      (is (h/approx= (second pos) 20 2) "Y should be ~2*radius"))))

(deftest mixed-arc-directions
  (testing "Mixed horizontal and vertical arcs"
    (let [turtle (-> (t/make-turtle)
                     (t/arc-h 10 90)
                     (t/arc-v 10 90))
          pos (:position turtle)]
      ;; After arc-h 90: at [10, 10, 0] heading +Y
      ;; After arc-v 90: pitched up 90 from +Y heading
      (is (> (nth pos 2) 5) "Should have climbed in Z"))))

;; ═══════════════════════════════════════════════════════════
;; bezier-as Direct Usage Tests
;; These tests verify that bezier-as works correctly when called directly.
;;
;; KNOWN BUG: rec-bezier-as* in repl.cljs records :set-heading commands
;; with ABSOLUTE headings. When the recorded path is replayed on a turtle
;; not in the default pose, the absolute headings point in wrong directions.
;; This bug affects (path (bezier-as ...)) used with loft/extrude/follow.
;; A test for this would need to go in repl_test.cljs.
;; ═══════════════════════════════════════════════════════════

(deftest bezier-as-direct-usage-test
  (testing "bezier-as direct call works regardless of turtle orientation"
    ;; NOTE: This tests direct bezier-as usage (not recording).
    ;; Direct usage works correctly; the bug is in the recording layer.
    (let [simple-path (t/make-path [{:cmd :f :args [20]}
                                     {:cmd :th :args [90]}
                                     {:cmd :f :args [20]}])
          ;; Run the path with bezier-as from default pose
          default-turtle (t/make-turtle)
          result-default (t/bezier-as default-turtle simple-path)
          ;; Run the SAME path with bezier-as from rotated pose
          rotated-turtle (-> (t/make-turtle) (t/th 90))
          result-rotated (t/bezier-as rotated-turtle simple-path)
          ;; The DISPLACEMENT (end - start)
          disp-default (t/v- (:position result-default) (:position default-turtle))
          disp-rotated (t/v- (:position result-rotated) (:position rotated-turtle))]

      ;; Both displacements should have the same LENGTH
      (is (h/approx= (t/magnitude disp-default) (t/magnitude disp-rotated) 0.1)
          "Path displacement magnitude should be same regardless of starting pose")

      ;; Displacements should differ (rotated turtle -> rotated path)
      (is (not (h/vec-approx= disp-default disp-rotated 0.1))
          "Displacements should differ when turtle is rotated")

      ;; default disp [dx, dy, dz] rotated 90 deg CCW around Z -> [-dy, dx, dz]
      (let [[dx dy dz] disp-default
            expected-rotated [(- dy) dx dz]]
        (is (h/vec-approx= expected-rotated disp-rotated 1.0)
            "Rotated turtle should produce rotated displacement"))))

  (testing "run-path and bezier-as agree on final position"
    (let [simple-path (t/make-path [{:cmd :f :args [20]}
                                     {:cmd :th :args [90]}
                                     {:cmd :f :args [20]}])
          ;; Start from a non-default pose
          start-turtle (-> (t/make-turtle) (t/f 50) (t/th 45))
          ;; run-path: applies path commands directly
          followed (t/run-path start-turtle simple-path)
          ;; bezier-as: converts path to bezier, then applies
          beziered (t/bezier-as start-turtle simple-path)
          follow-pos (:position followed)
          bezier-pos (:position beziered)
          dist (t/magnitude (t/v- follow-pos bezier-pos))]
      ;; Bezier approximation shouldn't deviate by more than ~20% of path length
      (is (< dist 8)
          (str "run-path and bezier-as should agree, disagreement: " dist " units")))))
