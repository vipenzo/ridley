(ns ridley.math-test
  "pose-around-axis, added for edit-acquire's gate prototype (dev-docs/
   brief-param-acq-v1.md) — orbits a turtle pose around an EXTERNAL pivot,
   unlike turtle/f, turtle/th, turtle/tv (which move/rotate a pose in its own
   frame). Used both to pre-seed a turntable photo's camera pose from the
   previous one, and to apply a gizmo drag's inverse onto the camera."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.test-helpers :refer [vec-approx=]]
            [ridley.math :as m]))

(deftest pose-around-axis-quarter-turn-about-origin
  (testing "90 degrees around world Z, pivot at the origin: position orbits,
            heading/up rotate in place, exactly like rotate-point-around-axis/
            rotate-around-axis applied directly — pivot at the origin is the
            degenerate case where orbiting and rotating-in-place coincide"
    (let [pose {:position [1 0 0] :heading [0 1 0] :up [0 0 1]}
          result (m/pose-around-axis pose [0 0 0] [0 0 1] (/ js/Math.PI 2))]
      (is (vec-approx= [0 1 0] (:position result)))
      (is (vec-approx= [-1 0 0] (:heading result)))
      (is (vec-approx= [0 0 1] (:up result))))))

(deftest pose-around-axis-orbits-around-external-pivot
  (testing "pivot away from the position: heading/up rotate in place (same as
            the origin-pivot case) but position orbits the PIVOT, not itself —
            a camera 10 units east of a subject, swung 90 degrees around a
            vertical axis through the subject, ends up 10 units north of it"
    (let [pose {:position [10 0 0] :heading [-1 0 0] :up [0 0 1]}
          result (m/pose-around-axis pose [0 0 0] [0 0 1] (/ js/Math.PI 2))]
      (is (vec-approx= [0 10 0] (:position result)))
      (is (vec-approx= [0 -1 0] (:heading result)))
      (is (vec-approx= [0 0 1] (:up result))))))

(deftest pose-around-axis-full-turn-is-identity
  (testing "360 degrees returns (approximately) the original pose"
    (let [pose {:position [3 4 5] :heading [0 1 0] :up [0 0 1]}
          result (m/pose-around-axis pose [1 1 1] [0 0 1] (* 2 js/Math.PI))]
      (is (vec-approx= (:position pose) (:position result)))
      (is (vec-approx= (:heading pose) (:heading result)))
      (is (vec-approx= (:up pose) (:up result))))))

(deftest orthogonalize-up-already-perpendicular-is-unchanged
  (testing "when up is already exactly perpendicular to heading, Gram-Schmidt
            is a no-op (up to normalization)"
    (let [result (m/orthogonalize-up [1 0 0] [0 0 1])]
      (is (vec-approx= [0 0 1] result)))))

(deftest orthogonalize-up-corrects-a-non-perpendicular-hint
  (testing "found live 2026-07-22 (edit-acquire's default-vantage-pose):
            heading=(normalize [1 -1 1]), up=[0 0 1] have dot ~0.577, nowhere
            near perpendicular — orthogonalize-up must return a UNIT vector
            exactly perpendicular to heading, not merely a re-normalization
            of the original (broken) up"
    (let [heading (m/normalize [1 -1 1])
          result (m/orthogonalize-up heading [0 0 1])]
      (is (< (Math/abs (m/dot heading result)) 1e-9))
      (is (< (Math/abs (- 1.0 (m/magnitude result))) 1e-9)))))
