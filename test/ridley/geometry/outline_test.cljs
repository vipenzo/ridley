(ns ridley.geometry.outline-test
  "A closed outline that crosses itself breaks the cap triangulation, and the
   damage is reported three steps later as holes in a mesh. These tests pin the
   check that catches it where it can still be seen: on the curve."
  (:require [cljs.test :refer [deftest is testing]]
            [ridley.geometry.outline :as o]))

(defn- near? [[ax ay] [bx by] tol]
  (and (< (Math/abs (- ax bx)) tol) (< (Math/abs (- ay by)) tol)))

(deftest a-simple-square-does-not-cross-itself
  (testing "and in particular the SEAM is not mistaken for a crossing

   The first and last segments of a closed ring share a node by construction. A
   check that forgot that would call every closed outline broken, which is worse
   than not checking."
    (is (empty? (o/self-intersections [[0 0] [10 0] [10 10] [0 10]])))
    (is (not (o/self-intersects? [[0 0] [10 0] [10 10] [0 10]])))))

(deftest a-bowtie-crosses-once-in-the-middle
  (let [xs (o/self-intersections [[0 0] [10 10] [10 0] [0 10]])]
    (is (= 1 (count xs)))
    (is (near? (first xs) [5 5] 1e-6)
        (str "the crossing is where the two diagonals meet, got " (first xs)))))

(deftest the-crossing-can-be-ON-the-closing-segment
  (testing "the seam is the join the eye does not check, so it is the one that matters

   A spiral: the outline winds inward, and the run home from the last node cuts
   back across a turn it already made. Open, these points are clean — the fault
   exists only because the outline closes, which is exactly the case a check on
   the drawn segments alone would miss."
    (let [pts [[0 0] [10 0] [10 10] [0 10] [0 2] [8 2] [8 8]]
          xs (o/self-intersections pts true)]
      (is (= 1 (count xs)))
      (is (near? (first xs) [2 2] 1e-6)
          (str "…and it is placed, at " (first xs)))
      (is (empty? (o/self-intersections pts false))
          "the same points, left open, do not cross at all"))))

(deftest running-back-along-a-previous-stretch-counts
  (testing "a collinear overlap kills the triangulator exactly like a transversal one

   Reporting only transversal crossings would miss an outline that comes back
   along a stretch it already walked — no X to see, same broken cap.

   NOT covered, deliberately: a fold-back between two ADJACENT segments (out and
   straight back through the same node). That is a zero-area spike rather than a
   crossing, every pair of adjacent segments touches by construction, and telling
   the two apart belongs to a check about spikes. Warning about it here would put
   noise on every sharp corner."
    (is (seq (o/self-intersections [[0 0] [10 0] [10 5] [2 5] [2 0] [8 0]] false))
        "the last stretch runs back along the first one")))

(deftest a-touch-at-a-shared-node-is-not-a-crossing
  (testing "adjacency and tangency must not be reported, or the warning is noise"
    ;; a spike: out and back through the SAME point, but as neighbouring segments
    (is (empty? (o/self-intersections [[0 0] [10 0] [10 10] [0 10] [0 5]] false)))))

(deftest too-few-points-cannot-cross
  (is (empty? (o/self-intersections [[0 0] [10 0]])))
  (is (empty? (o/self-intersections [[0 0] [10 0] [5 5]]))
      "a triangle is the smallest closed outline and it never crosses"))
