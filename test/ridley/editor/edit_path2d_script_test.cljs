(ns ridley.editor.edit-path2d-script-test
  "edit-path-2d must let the script PROCEED with the same result as confirming:
   request! returns the value consumed by a surrounding path-to-shape/embroid/loft.
   That value must be a fully-traceable path-2d (the tessellated seed), not the
   node reconstruction (high-level :bezier-to that ensure-path-2d can't trace →
   '< 2 waypoints'). Regression test for the embroid(edit-path-2d) failure."
  (:require [cljs.test :refer [deftest is]]
            [ridley.editor.edit-path :as ep]
            [ridley.editor.sci-harness :as h]
            [ridley.turtle.shape :as shape]))

(def ^:private bezier-body
  "(move-to [0 -0.27]) (bezier-to [0 10.37 -23.31] [0 1.64 -9.73] [0 6.94 -19.14] :local) (bezier-to [0 9.12 5.25] [0 0 1.53] [0 3.55 15.61] :local)")

(deftest edit-path-2d-returns-traceable-seed
  (let [seed   (:result (h/eval-dsl (str "(path-2d " bezier-body ")")))
        result (ep/request! seed)
        seed-wps   (count (shape/ensure-path-2d seed))
        result-wps (count (shape/ensure-path-2d result))]
    (is (= :2d (:species result)) "edit-path-2d result is a :2d path")
    (is (>= result-wps 2)
        (str "edit-path-2d result must be traceable (>= 2 waypoints); got " result-wps))
    ;; same result as confirming = same as the equivalent path-2d
    (is (= seed-wps result-wps)
        "edit-path-2d result traces to the same waypoints as its path-2d seed")))

(deftest edit-path-2d-straight-still-works
  ;; non-bezier 2D edit-path also proceeds (was already fine; guard no regression).
  (let [seed   (:result (h/eval-dsl "(path-2d (move-to [0 0]) (f 20) (tv 90) (f 20))"))
        result (ep/request! seed)]
    (is (= :2d (:species result)))
    (is (>= (count (shape/ensure-path-2d result)) 2))))
