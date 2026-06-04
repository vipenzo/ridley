(ns ridley.geometry.cone-flip-test
  "Temporary sanity check for the cone parameter flip (r1=near/start, r2=far).
   Verifies that cone-mesh places r2 on the -half-h cap and r1 on the +half-h
   cap, so that swapping r1<->r2 mirrors the frustum end-for-end."
  (:require [clojure.test :refer [deftest is]]
            [ridley.geometry.primitives :as p]))

(defn- ring-radius [v]
  (Math/round (Math/hypot (nth v 0) (nth v 2))))

(deftest cone-flip-radius-placement
  (let [vs (:vertices (p/cone-mesh 10 0 20 8))
        near (mapv ring-radius (subvec vs 0 8))   ; first block = -half-h cap -> r2
        far  (mapv ring-radius (subvec vs 8 16))] ; second block = +half-h cap -> r1
    ;; (cone-mesh 10 0 ...): r2=0 on -half-h cap, r1=10 on +half-h cap
    (is (every? zero? near) "near (-half-h) cap should carry r2 (=0)")
    (is (every? #(= 10 %) far) "far (+half-h) cap should carry r1 (=10)")
    (is (= -10.0 (double (nth (first vs) 1))))
    (is (= 10.0 (double (nth (nth vs 8) 1))))))
