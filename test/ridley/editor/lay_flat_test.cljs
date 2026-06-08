(ns ridley.editor.lay-flat-test
  "Tests for lay-flat accepting a path directly: it resolves the path's
   (mark …) at the mesh's creation-pose and lays that plane flat — the
   print-face workflow without a separate attach-path step."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.impl :as impl]
            [ridley.geometry.primitives :as prims]
            [ridley.turtle.core :as t]))

(defn- box []
  (assoc (prims/box-mesh 30 20 8)
         :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}))

(defn- size [m]
  (let [vs (:vertices m)
        extent (fn [i] (- (apply max (map #(nth % i) vs))
                          (apply min (map #(nth % i) vs))))]
    (mapv #(js/Math.round (extent %)) [0 1 2])))

(deftest lay-flat-path-single-mark
  (testing "a path with one mark lays that face flat (heading = face normal)"
    (let [b (box)
          ;; mark at the creation-pose: heading +X → a side face
          out (impl/lay-flat-impl b (t/make-path [{:cmd :mark :args [:pf]}]))]
      (is (not= (:vertices b) (:vertices out)) "geometry is reoriented")
      (is (= [8 20 30] (size out))
          "the +X face is laid down: the 30-long axis moves to Z, 8-thick to X")
      (is (= {:position [0 0 0] :heading [1 0 0] :up [0 0 1]} (:creation-pose out))
          "creation-pose reset to identity"))))

(deftest lay-flat-path-named-mark
  (testing "a 3rd arg picks one mark from a multi-mark path"
    (is (some? (impl/lay-flat-impl (box)
                                   (t/make-path [{:cmd :mark :args [:a]} {:cmd :f :args [5]}
                                                 {:cmd :mark :args [:b]}])
                                   :a)))))

(deftest lay-flat-path-errors
  (testing "clear errors for ambiguous or empty mark sets"
    (is (thrown? js/Error
                 (impl/lay-flat-impl (box)
                                     (t/make-path [{:cmd :mark :args [:a]} {:cmd :f :args [5]}
                                                   {:cmd :mark :args [:b]}])))
        "several marks, no name → throws")
    (is (thrown? js/Error
                 (impl/lay-flat-impl (box) (t/make-path [{:cmd :f :args [5]}])))
        "no mark → throws")))
