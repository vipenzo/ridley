(ns ridley.editor.operations-test
  "Tests for editor.operations: pure extrude/loft/revolve over single shapes
   AND vector-of-shapes inputs (e.g. text-shape output).

   Key invariant: extrude on a vector of shapes returns a SINGLE mesh
   (combined geometry), not a vector of meshes. This keeps downstream
   boolean ops working without the caller needing to wrap with concat-meshes."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.operations :as ops]
            [ridley.turtle.core :as t]
            [ridley.turtle.shape :as shape]))

(defn- mesh? [x]
  (and (map? x) (= :mesh (:type x)) (vector? (:vertices x)) (vector? (:faces x))))

(deftest extrude-single-shape-returns-mesh
  (testing "extrude on a single shape returns one mesh"
    (let [s (shape/circle-shape 5 8)
          path (t/make-path [{:cmd :f :args [10]}])
          result (ops/pure-extrude-path s path)]
      (is (mesh? result) "Single shape → single mesh"))))

(deftest extrude-vector-of-shapes-returns-single-mesh
  (testing "extrude on a vector of shapes returns ONE combined mesh, not a vector"
    (let [s1 (shape/circle-shape 5 8)
          s2 (shape/rect-shape 4 4)
          path (t/make-path [{:cmd :f :args [10]}])
          result (ops/pure-extrude-path [s1 s2] path)]
      (is (mesh? result)
          (str "Vector of shapes → single mesh, got: "
               (cond (vector? result) (str "vector of " (count result))
                     (nil? result) "nil"
                     :else (pr-str (type result)))))
      ;; Combined mesh has vertices from both shapes
      (is (>= (count (:vertices result)) 16)
          "Combined mesh should have vertices from both extrusions"))))

(deftest extrude-empty-vector-returns-nil
  (testing "extrude on empty vector → nil (no shapes, no mesh)"
    (let [path (t/make-path [{:cmd :f :args [10]}])
          result (ops/pure-extrude-path [] path)]
      (is (nil? result)))))

(deftest extrude-closed-vector-of-shapes-returns-single-mesh
  (testing "extrude-closed on a vector of shapes also combines into one mesh"
    (let [s1 (shape/circle-shape 3 8)
          s2 (shape/rect-shape 2 2)
          path (t/make-path [{:cmd :f :args [5]}
                             {:cmd :th :args [120]}
                             {:cmd :f :args [5]}
                             {:cmd :th :args [120]}
                             {:cmd :f :args [5]}])
          result (ops/implicit-extrude-closed-path [s1 s2] path)]
      (is (mesh? result)
          "Vector input → single mesh (not vector of meshes)"))))
