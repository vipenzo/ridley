(ns ridley.photogrammetry.fuse-test
  "The rigid motion that fuses two shooting sessions.

   Synthetic on purpose: a KNOWN motion is applied to a set of marks, and the
   fit has to give it back. Real photos test the clicks; this tests the
   arithmetic, and it is the arithmetic that must not be plausible-but-wrong —
   a fused frame that is quietly 2 mm out looks exactly like a good one until
   an extrusion misses the object.

   The floor gate of the brief lives here too, in its synthetic form: identical
   marks must fuse to the identity, or nothing downstream can be trusted."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.math :as m]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.fuse :as fuse]))

(defn- approx= [a b tol] (< (js/Math.abs (- a b)) tol))

(def ^:private known-rt
  "A motion with all six degrees of freedom used: a rotation that is not about
   an axis of the frame, and a translation with no zero component."
  {:R (cam/rodrigues [0.31 -0.87 0.45]) :t [12.5 -30.25 7.75]})

(defn- anchor
  "One named twin: a point (and optionally a normal) in session B's frame, and
   the same physical thing in session A's frame, obtained by applying `rt`."
  ([nm pos] (anchor nm pos nil known-rt))
  ([nm pos dir] (anchor nm pos dir known-rt))
  ([nm pos dir rt]
   (cond-> {:name nm
            :from-pos pos
            :to-pos (fuse/transform-point rt pos)}
     dir (assoc :from-dir dir :to-dir (fuse/transform-dir rt dir)))))

(defn- rt-error
  "How far the fitted motion is from the known one, measured where it matters:
   on a probe point far from the anchors, in mm."
  [fit probe]
  (la/v-norm (la/v-sub (fuse/transform-point fit probe)
                       (fuse/transform-point known-rt probe))))

(deftest three-points-recover-the-motion-exactly
  (let [fit (fuse/fit-rigid [(anchor :a [0 0 0])
                             (anchor :b [40 2 1])
                             (anchor :c [3 35 -2])])]
    (is (nil? (:error fit)) (:error fit))
    (is (approx= 0.0 (:rms-mm fit) 1e-6) "exact input, exact fit")
    (testing "and it is the SAME motion, not just one that fits the anchors"
      (is (approx= 0.0 (rt-error fit [80 -60 55]) 1e-5)
          "a probe point 100 mm away lands where the known motion puts it"))))

(deftest two-plane-marks-are-enough-because-they-carry-normals
  (let [fit (fuse/fit-rigid [(anchor :piano-1 [0 0 0] [0 0 1])
                             (anchor :piano-2 [45 3 2] [1 0 0])])]
    (is (nil? (:error fit)) (:error fit))
    (is (approx= 0.0 (rt-error fit [70 -50 40]) 1e-4)
        "two marks with non-parallel normals determine all six degrees of freedom")))

(deftest two-bare-points-do-not-determine-the-rotation
  (let [fit (fuse/fit-rigid [(anchor :a [0 0 0]) (anchor :b [45 3 2])])]
    (is (some? (:error fit))
        "the roll about the line joining them is free — refusing is the only honest answer")
    (is (re-find #"terzo mark|allineati" (:error fit)))))

(deftest one-mark-is-refused-by-name
  (let [fit (fuse/fit-rigid [(anchor :a [0 0 0] [0 0 1])])]
    (is (re-find #"almeno DUE" (:error fit)))))

(deftest collinear-marks-are-refused
  (let [fit (fuse/fit-rigid [(anchor :a [0 0 0]) (anchor :b [20 0 0]) (anchor :c [50 0 0])])]
    (is (some? (:error fit)) "three points on a line leave the roll about it free")))

(deftest marks-too-close-together-are-refused
  (let [fit (fuse/fit-rigid [(anchor :a [0 0 0]) (anchor :b [1.5 0.5 0]) (anchor :c [0 1.2 0.4])])]
    (is (re-find #"troppo vicini" (:error fit))
        "a 1.6 mm baseline on a 100 mm object multiplies click noise")))

(deftest the-floor-gate-identical-sessions-fuse-to-the-identity
  ;; The brief's gate 1, in synthetic form: the same marks in both frames.
  (let [same (fn [nm p d] {:name nm :from-pos p :to-pos p :from-dir d :to-dir d})
        fit (fuse/fit-rigid [(same :a [0 0 0] [0 0 1])
                             (same :b [40 1 2] [1 0 0])
                             (same :c [2 38 -3] [0 1 0])])]
    (is (nil? (:error fit)))
    (is (approx= 0.0 (:rms-mm fit) 1e-9))
    (is (approx= 0.0 (la/v-norm (:t fit)) 1e-6) "no translation")
    (is (approx= 0.0 (la/v-norm (:rvec fit)) 1e-6) "no rotation")))

(deftest noise-shows-up-in-the-numbers-instead-of-being-hidden
  (let [jitter (fn [a dx] (update a :to-pos #(m/v+ % dx)))
        fit (fuse/fit-rigid [(jitter (anchor :a [0 0 0]) [0.1 -0.05 0.08])
                             (jitter (anchor :b [40 2 1]) [-0.06 0.09 -0.11])
                             (jitter (anchor :c [3 35 -2]) [0.04 0.07 -0.03])])]
    (is (nil? (:error fit)))
    (is (> (:rms-mm fit) 0.02) "the residual reports the disagreement")
    (is (< (:rms-mm fit) 0.5) "and does not amplify it")
    (is (= 3 (count (:per-anchor fit))) "every anchor gets its own line")))

(deftest a-wrong-twin-is-pointed-at-not-averaged-away
  ;; A mark that is not really the same physical point in both sessions — the
  ;; mistake a user makes by naming two different corners alike.
  (let [bad (update (anchor :sbagliato [10 10 10]) :to-pos #(m/v+ % [6 -4 5]))
        fit (fuse/fit-rigid [(anchor :a [0 0 0])
                             (anchor :b [40 2 1])
                             (anchor :c [3 35 -2])
                             (anchor :d [-2 4 30])
                             bad])
        worst (fuse/worst-anchor (:per-anchor fit))]
    (is (nil? (:error fit)))
    (is (= :sbagliato (:name worst))
        "the odd one out is named, so it can be re-clicked or renamed")))

(deftest transported-poses-keep-their-frame
  (let [fit (fuse/fit-rigid [(anchor :a [0 0 0] [0 0 1])
                             (anchor :b [40 2 1] [1 0 0])
                             (anchor :c [3 35 -2] [0 1 0])])
        pose {:position [5 6 7] :heading [0 0 1] :up [0 1 0]}
        moved (fuse/transform-pose fit pose)]
    (testing "heading and up rotate, and stay unit and perpendicular"
      (is (approx= 1.0 (la/v-norm (:heading moved)) 1e-9))
      (is (approx= 1.0 (la/v-norm (:up moved)) 1e-9))
      (is (approx= 0.0 (m/dot (:heading moved) (:up moved)) 1e-9)
          "a transported mark is still a usable frame, not just a point"))
    (testing "position goes where the motion says"
      (is (approx= 0.0 (la/v-norm (la/v-sub (:position moved)
                                            (fuse/transform-point known-rt [5 6 7])))
                   1e-5)))))
