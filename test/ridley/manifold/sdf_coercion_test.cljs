(ns ridley.manifold.sdf-coercion-test
  "Pure-level tests for SDF→mesh coercion in mesh-only operations.
   See dev-docs/brief-sdf-mesh-coercion.md for the full census.

   None of these can reach the Rust geometry server or Manifold WASM in a
   Node test run, so they can't verify a *correct* materialized result.
   What they CAN verify: that an SDF operand is no longer silently
   discarded / returned unchanged. Before the fix, solidify/hull returned
   nil (Manifold-availability gate short-circuits before the SDF ever
   gets a chance) and concat-meshes contributed zero vertices/faces for
   the SDF operand. After the fix, coercion is attempted unconditionally
   up front, so a fake SDF node (which has no real geometry to
   materialize) surfaces as a thrown error from sdf/ensure-mesh's server
   round-trip, instead of disappearing without a trace."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.manifold.core :as manifold]))

(def ^:private fake-sdf
  "A syntactically valid SDF node (recognized by sdf/sdf-node?) that has
   no real geometry — good enough to prove routing without a server."
  {:op "sphere" :r 5.0})

(def ^:private box-a
  {:type :mesh
   :vertices [[-5 -5 -5] [5 -5 -5] [5 5 -5] [-5 5 -5]
              [-5 -5  5] [5 -5  5] [5 5  5] [-5 5  5]]
   :faces [[0 2 1] [0 3 2]
           [4 5 6] [4 6 7]
           [0 1 5] [0 5 4]
           [2 3 7] [2 7 6]
           [1 2 6] [1 6 5]
           [0 4 7] [0 7 3]]})

(def ^:private box-b
  {:type :mesh
   :vertices [[0 -5 -5] [10 -5 -5] [10 5 -5] [0 5 -5]
              [0 -5  5] [10 -5  5] [10 5  5] [0 5  5]]
   :faces [[0 2 1] [0 3 2]
           [4 5 6] [4 6 7]
           [0 1 5] [0 5 4]
           [2 3 7] [2 7 6]
           [1 2 6] [1 6 5]
           [0 4 7] [0 7 3]]})

(deftest solidify-does-not-silently-accept-sdf
  (testing "solidify attempts real SDF coercion instead of returning nil/unchanged"
    (is (thrown? js/Error (manifold/solidify fake-sdf)))))

(deftest hull-does-not-silently-drop-sdf-operand
  (testing "hull attempts SDF coercion instead of `keep`-dropping the SDF operand"
    (is (thrown? js/Error (manifold/hull fake-sdf box-a)))))

(deftest concat-meshes-does-not-silently-drop-sdf-operand
  (testing "concat-meshes attempts SDF coercion instead of contributing nil vertices/faces"
    (is (thrown? js/Error (manifold/concat-meshes fake-sdf box-a)))))

;; ── Non-regression: plain-mesh calls are unaffected ─────────────

(deftest concat-meshes-plain-meshes-unaffected
  (testing "concat-meshes on ordinary meshes still merges normally"
    (let [result (manifold/concat-meshes box-a box-b)]
      (is (= (+ (count (:vertices box-a)) (count (:vertices box-b)))
             (count (:vertices result))))
      (is (= (+ (count (:faces box-a)) (count (:faces box-b)))
             (count (:faces result)))))))

(deftest concat-meshes-vector-form-unaffected
  (testing "(concat-meshes [a b]) vector form still works"
    (let [result (manifold/concat-meshes [box-a box-b])]
      (is (= (+ (count (:vertices box-a)) (count (:vertices box-b)))
             (count (:vertices result)))))))

(deftest solidify-plain-mesh-unaffected
  (testing "solidify on an ordinary mesh takes the same path as before (Manifold-gated)"
    ;; Manifold WASM isn't initialized in Node — both before and after the
    ;; fix this returns nil here. The fix only changes SDF-input routing.
    (is (nil? (manifold/solidify box-a)))))
