(ns ridley.photogrammetry.box-fit-test
  "Correctness tests for the proxy-fit machinery.

   These validate the pieces (rotation round-trip, projection, visibility,
   noiseless recovery, gauge freedom). The accuracy sweeps that decide
   whether the approach is viable live in accuracy-study-test."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.synth :as synth]))

(defn- close? [a b tol] (< (Math/abs (- a b)) tol))

(defn- vclose? [a b tol] (every? true? (map #(close? %1 %2 tol) a b)))

;; ---------------------------------------------------------------------------
;; Linear algebra

(deftest linalg-solve
  (testing "Gaussian elimination solves a known system"
    (let [a [[2.0 1.0 -1.0] [-3.0 -1.0 2.0] [-2.0 1.0 2.0]]
          b [8.0 -11.0 -3.0]
          x (la/solve a b)]
      (is (vclose? x [2.0 3.0 -1.0] 1e-9))))
  (testing "singular system returns nil"
    (is (nil? (la/solve [[1.0 2.0] [2.0 4.0]] [1.0 2.0])))))

(deftest eigen-symmetric-known
  (testing "eigenvalues of a diagonal matrix are its diagonal"
    (let [ev (la/eigen-symmetric [[3.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 2.0]])]
      (is (vclose? ev [3.0 2.0 1.0] 1e-9))))
  (testing "eigenvalues of a known symmetric matrix"
    ;; [[2 1][1 2]] has eigenvalues 3 and 1
    (let [ev (la/eigen-symmetric [[2.0 1.0] [1.0 2.0]])]
      (is (vclose? ev [3.0 1.0] 1e-9)))))

;; ---------------------------------------------------------------------------
;; Camera

(deftest rodrigues-round-trip
  (testing "rvec -> matrix -> rvec is the identity"
    (doseq [rv [[0.1 0.2 0.3] [1.2 -0.4 0.9] [0.0 0.0 0.0] [2.9 0.1 0.05]]]
      (let [back (cam/rot-mat->rodrigues (cam/rodrigues rv))]
        (is (vclose? back rv 1e-7) (str "round trip failed for " rv)))))
  (testing "rotation matrices are orthonormal with det +1"
    (let [m (cam/rodrigues [0.7 -0.3 1.1])
          mt (la/transpose m)
          p (la/mat*mat m mt)]
      (is (vclose? (flatten p) (flatten (la/identity-mat 3)) 1e-9)))))

(deftest projection-basics
  (testing "a point on the optical axis projects to the principal point"
    (let [k (cam/intrinsics-from-fov 60.0 1000 800)
          pose (cam/look-at-pose [0.0 0.0 100.0] [0.0 0.0 0.0] [0.0 1.0 0.0])
          p (cam/project k pose [0.0 0.0 0.0])]
      (is (vclose? p [500.0 400.0] 1e-6))))
  (testing "a point behind the camera does not project"
    (let [k (cam/intrinsics-from-fov 60.0 1000 800)
          pose (cam/look-at-pose [0.0 0.0 100.0] [0.0 0.0 0.0] [0.0 1.0 0.0])]
      (is (nil? (cam/project k pose [0.0 0.0 200.0])))))
  (testing "camera-center recovers the eye position"
    (let [eye [120.0 -80.0 60.0]
          pose (cam/look-at-pose eye [0.0 0.0 0.0] [0.0 0.0 1.0])]
      (is (vclose? (cam/camera-center pose) eye 1e-7)))))

;; ---------------------------------------------------------------------------
;; Box geometry

(deftest box-topology
  (testing "a box has 12 edges"
    (is (= 12 (count bf/edges))))
  (testing "corners match the requested extents"
    (let [pts (bf/corners [60.0 25.0 10.0])
          xs (map first pts)]
      (is (close? (- (apply max xs) (apply min xs)) 60.0 1e-9))))
  (testing "a generic viewpoint sees 9 of the 12 edges"
    ;; A convex box from a corner-ish direction shows three faces and thus
    ;; nine edges; this is the standard result and a good guard against a
    ;; back-face-culling sign error.
    (let [pose (synth/viewpoint 35.0 25.0 300.0)]
      (is (= 9 (count (bf/visible-edges [60.0 25.0 10.0] pose))))))
  (testing "a perfectly face-on viewpoint sees only 4 edges"
    ;; Degenerate on purpose: with the camera exactly on an axis the four side
    ;; faces are exactly edge-on and strictly back-facing, so a single face
    ;; shows. Such a view carries NO depth information — it is the
    ;; configuration the coverage guidance has to steer the user away from.
    (let [pose (synth/viewpoint 0.0 0.0 300.0)
          n (count (bf/visible-edges [60.0 25.0 10.0] pose))]
      (is (= 4 n) (str "expected 4 visible edges face-on, got " n))))
  (testing "a slight tilt off the axis already reveals a second face"
    (let [pose (synth/viewpoint 5.0 3.0 300.0)
          n (count (bf/visible-edges [60.0 25.0 10.0] pose))]
      (is (= 9 n) (str "expected 9 visible edges when off-axis, got " n)))))

(deftest visible-corners-hides-the-far-vertex
  ;; edit-acquire's PnP only offers the user the corners actually visible on the
  ;; part (never a vertex hidden behind it). A generic three-face view shows
  ;; seven of the eight corners; a face-on view shows four.
  (testing "a generic viewpoint sees 7 of the 8 corners (one far vertex hidden)"
    (let [pose (synth/viewpoint 35.0 25.0 300.0)
          vis (bf/visible-corners [60.0 25.0 10.0] pose)]
      (is (= 7 (count vis)) (str "expected 7 visible corners, got " (sort vis)))))
  (testing "a face-on viewpoint sees only the 4 corners of the one visible face"
    (let [pose (synth/viewpoint 0.0 0.0 300.0)]
      (is (= 4 (count (bf/visible-corners [60.0 25.0 10.0] pose))))))
  (testing "every visible corner lies on at least one visible edge"
    (let [dims [60.0 25.0 10.0]
          pose (synth/viewpoint 42.0 18.0 300.0)
          vis (bf/visible-corners dims pose)
          edge-corners (set (mapcat #(nth bf/edges %) (bf/visible-edges dims pose)))]
      (is (= vis edge-corners)
          "the visible-corner set must equal the corners of the visible edges"))))

;; ---------------------------------------------------------------------------
;; The fit

(def true-dims [60.0 25.0 10.0])

(defn- three-views []
  [(synth/viewpoint 30.0 25.0 250.0)
   (synth/viewpoint -40.0 20.0 250.0)
   (synth/viewpoint 80.0 40.0 250.0)])

(defn- intrinsics-for [n]
  (vec (repeat n (cam/intrinsics-from-fov 69.4 4032 3024))))

(deftest noiseless-recovery
  (testing "with zero noise the solver recovers the true dimensions exactly"
    (let [r (synth/rng 42)
          poses (three-views)
          ks (intrinsics-for 3)
          obs (synth/observations true-dims poses ks 0.0 r)
          init-poses (mapv #(synth/perturb-pose % 4.0 8.0 r) poses)
          init-dims (synth/perturb-dims true-dims 0.15 r)
          res (bf/fit obs ks init-dims init-poses
                      {:sigma-px 1.0
                       :scale-constraint {:axis 0 :value 60.0 :sigma 0.02}
                       :lm {:max-iterations 300}})]
      (is (:converged? res))
      (is (vclose? (:dims res) true-dims 0.01)
          (str "recovered " (:dims res) " from " init-dims)))))

(deftest scale-is-a-gauge-freedom
  (testing "without a caliper constraint J^T J is singular by exactly one"
    (let [r (synth/rng 7)
          poses (three-views)
          ks (intrinsics-for 3)
          obs (synth/observations true-dims poses ks 0.0 r)
          res (bf/fit obs ks true-dims poses {:sigma-px 1.0 :lm {:max-iterations 5}})
          ev (bf/spectrum res)
          largest (first ev)
          ;; count eigenvalues that are numerically zero relative to the largest
          n-null (count (filter #(< (/ (Math/abs %) largest) 1e-10) ev))]
      (is (= 1 n-null)
          (str "expected exactly one null direction (overall scale), got "
               n-null " — spectrum " (vec (take 3 (reverse ev)))))))
  (testing "adding the caliper constraint removes the null direction"
    (let [r (synth/rng 7)
          poses (three-views)
          ks (intrinsics-for 3)
          obs (synth/observations true-dims poses ks 0.0 r)
          res (bf/fit obs ks true-dims poses
                      {:sigma-px 1.0
                       :scale-constraint {:axis 0 :value 60.0 :sigma 0.02}
                       :lm {:max-iterations 5}})
          ev (bf/spectrum res)
          largest (first ev)
          n-null (count (filter #(< (/ (Math/abs %) largest) 1e-10) ev))]
      (is (= 0 n-null)
          (str "caliper should fix scale; still " n-null " null directions")))))

(deftest edge-observations-are-perpendicular-only
  (testing "sliding a projected edge along its own direction costs nothing"
    ;; The aperture problem, asserted: a point moved ALONG an observed line
    ;; has the same residual, whereas moving it across the line does not.
    (let [line (bf/line-through [100.0 100.0] [200.0 140.0])
          d1 (bf/point-line-distance line [100.0 100.0])
          ;; a point further along the same line
          d2 (bf/point-line-distance line [300.0 180.0])
          ;; a point displaced off the line
          d3 (bf/point-line-distance line [100.0 110.0])]
      (is (close? d1 0.0 1e-9))
      (is (close? d2 0.0 1e-9))
      (is (> (Math/abs d3) 1.0)))))
