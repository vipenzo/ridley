(ns ridley.turtle.loft-caps-test
  "Regression tests for loft end-cap generation. A tapered loft of an
   off-centre profile (e.g. anything from path-to-shape, first vertex pinned to
   the rail) drifts the ring centroid sideways; the cap used to be projected
   onto that drift direction and came out open. build-sweep-mesh now caps onto
   each ring's own plane normal (Newell), which must stay watertight across
   grow/shrink tapers, twist, curved rails, concave and degenerate profiles."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.turtle.core :as t]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.shape-fn :as sfn]
            [ridley.turtle.loft :as loft]
            [ridley.test-helpers :as h]))

(defn- loft-mesh
  "Build a single-segment loft of a shape-fn along a straight path and return
   the (capped) mesh."
  [shape-fn-val path-cmds]
  (let [base (shape-fn-val 0)
        state (t/make-turtle)
        result (loft/loft-from-path state base (fn [_ s] (shape-fn-val s))
                                    (t/make-path path-cmds) 8)]
    (last (:meshes result))))

(def ^:private straight [{:cmd :f :args [5]}])

(def ^:private noncentered-tri
  (shape/make-shape [[0 0] [10 0] [5 8.66]] {:centered? false}))

(def ^:private noncentered-rect
  (shape/make-shape [[0 0] [10 0] [10 10] [0 10]] {:centered? false}))

(deftest tapered-noncentred-grow-is-watertight
  (testing "growing taper of an off-centre profile caps cleanly (the bug)"
    (is (h/watertight? (loft-mesh (sfn/tapered noncentered-tri :to 3) straight))
        "non-centred triangle grown ×3")
    (is (h/watertight? (loft-mesh (sfn/tapered noncentered-rect :to 3) straight))
        "non-centred rectangle grown ×3")
    (is (h/watertight? (loft-mesh (sfn/tapered noncentered-tri :to 2) straight))
        "non-centred triangle grown ×2")))

(deftest tapered-other-directions-still-watertight
  (testing "shrink, identity and centred tapers remain watertight"
    (is (h/watertight? (loft-mesh (sfn/tapered noncentered-tri :to 0.5) straight))
        "non-centred triangle shrunk")
    (is (h/watertight? (loft-mesh (sfn/tapered noncentered-tri :to 1) straight))
        "non-centred triangle, no scaling")
    (is (h/watertight? (loft-mesh (sfn/tapered (shape/circle-shape 10 8) :to 3) straight))
        "centred circle grown")))

(deftest loft-variations-watertight
  (testing "twist and concave profiles cap cleanly"
    (is (h/watertight? (loft-mesh (sfn/twisted noncentered-tri :angle 90) straight))
        "twisted off-centre triangle")
    (is (h/watertight? (loft-mesh (sfn/tapered (shape/star-shape 10 5 5) :to 2) straight))
        "concave star grown")))
