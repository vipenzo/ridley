(ns ridley.manifold.boolean-test
  "Tests for boolean operation metadata preservation.
   Boolean ops (union, difference, intersection, hull) should inherit
   creation-pose and material from the first mesh argument.

   NOTE: Actual Manifold WASM tests require browser environment.
   Tests here verify set-creation-pose (pure) and document boolean
   expectations for when Manifold is available."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.turtle.core :as t]
            [ridley.manifold.core :as manifold]))

;; ── set-creation-pose (pure, always testable) ───────────────

(deftest set-creation-pose-uses-current-turtle-pose
  (testing "set-creation-pose stamps current turtle position on mesh"
    (let [state (-> (t/make-turtle) (t/f 10) (t/th 90))
          mesh {:type :mesh :vertices [[0 0 0]] :faces []}
          result (t/set-creation-pose state mesh)]
      (is (= (:position state) (get-in result [:creation-pose :position]))
          "Position should match turtle")
      (is (= (:heading state) (get-in result [:creation-pose :heading]))
          "Heading should match turtle")
      (is (= (:up state) (get-in result [:creation-pose :up]))
          "Up should match turtle"))))

(deftest set-creation-pose-overwrites-existing
  (testing "set-creation-pose replaces any existing creation-pose"
    (let [state (t/make-turtle)
          mesh {:type :mesh
                :vertices [[0 0 0]]
                :faces []
                :creation-pose {:position [99 99 99]
                                :heading [0 0 1]
                                :up [1 0 0]}}
          result (t/set-creation-pose state mesh)]
      (is (= [0 0 0] (get-in result [:creation-pose :position]))
          "Should overwrite old position")
      (is (= [1 0 0] (get-in result [:creation-pose :heading]))
          "Should overwrite old heading"))))

(deftest set-creation-pose-preserves-mesh-data
  (testing "set-creation-pose doesn't alter mesh geometry"
    (let [state (t/make-turtle)
          verts [[0 0 0] [1 0 0] [0 1 0]]
          faces [[0 1 2]]
          mesh {:type :mesh :vertices verts :faces faces :material {:color 0xff0000}}
          result (t/set-creation-pose state mesh)]
      (is (= verts (:vertices result)) "Vertices preserved")
      (is (= faces (:faces result)) "Faces preserved")
      (is (= {:color 0xff0000} (:material result)) "Material preserved"))))

;; ── Boolean ops creation-pose preservation ──────────────────
;; These tests require Manifold WASM (browser environment).
;; They skip gracefully in node/CI where Manifold is not loaded.

(defn- manifold-available? []
  (manifold/initialized?))

(def ^:private sample-pose
  {:position [10 20 30] :heading [0 1 0] :up [0 0 1]})

(def ^:private box-a
  "A simple cube mesh (8 vertices, 12 triangle faces) for boolean tests."
  {:type :mesh
   :vertices [[-5 -5 -5] [5 -5 -5] [5 5 -5] [-5 5 -5]
              [-5 -5  5] [5 -5  5] [5 5  5] [-5 5  5]]
   :faces [[0 2 1] [0 3 2]   ;; bottom
           [4 5 6] [4 6 7]   ;; top
           [0 1 5] [0 5 4]   ;; front
           [2 3 7] [2 7 6]   ;; back
           [1 2 6] [1 6 5]   ;; right
           [0 4 7] [0 7 3]]  ;; left
   :creation-pose sample-pose
   :material {:color 0xff0000}})

(def ^:private box-b
  "An overlapping box for boolean operations."
  {:type :mesh
   :vertices [[0 -5 -5] [10 -5 -5] [10 5 -5] [0 5 -5]
              [0 -5  5] [10 -5  5] [10 5  5] [0 5  5]]
   :faces [[0 2 1] [0 3 2]
           [4 5 6] [4 6 7]
           [0 1 5] [0 5 4]
           [2 3 7] [2 7 6]
           [1 2 6] [1 6 5]
           [0 4 7] [0 7 3]]})

(deftest union-preserves-creation-pose
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "Union inherits creation-pose from first argument"
      (let [result (manifold/union box-a box-b)]
        (is (= sample-pose (:creation-pose result)))
        (is (= {:color 0xff0000} (:material result)))))))

(deftest difference-preserves-creation-pose
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "Difference inherits creation-pose from base mesh"
      (let [result (manifold/difference box-a box-b)]
        (is (= sample-pose (:creation-pose result)))
        (is (= {:color 0xff0000} (:material result)))))))

(deftest intersection-preserves-creation-pose
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "Intersection inherits creation-pose from first argument"
      (let [result (manifold/intersection box-a box-b)]
        (is (= sample-pose (:creation-pose result)))
        (is (= {:color 0xff0000} (:material result)))))))

(deftest hull-preserves-creation-pose
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "Hull inherits creation-pose from first mesh"
      (let [result (manifold/hull box-a box-b)]
        (is (= sample-pose (:creation-pose result)))
        (is (= {:color 0xff0000} (:material result)))))))
