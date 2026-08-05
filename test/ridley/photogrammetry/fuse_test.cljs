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
(defn- v= [a b] (and (= (count a) (count b)) (every? #(approx= (first %) (second %) 1e-9) (map vector a b))))

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

(deftest three-planes-with-independent-normals-determine-everything
  (let [fit (fuse/fit-rigid [(anchor :testa [0 0 0] [0 0 1])
                             (anchor :fianco [45 3 2] [1 0 0])
                             (anchor :becco [3 40 -2] [0 1 0])])]
    (is (nil? (:error fit)) (:error fit))
    (is (= 3 (:planes fit)) "believed as planes, not as points")
    (is (approx= 0.0 (rt-error fit [70 -50 40]) 1e-4))))

(deftest sliding-a-mark-INSIDE-its-plane-changes-nothing
  ;; THE property this design exists for (Vincenzo 2026-08-05: a plane is easy to
  ;; find again across sessions, a specific point on it is not). The two sessions
  ;; put the origin of each mark somewhere else on the same physical plane — as
  ;; they always will, since the origin is the centroid of wherever the user
  ;; happened to click — and the recovered motion must be the SAME.
  (let [planes [[:testa [0 0 0] [0 0 1] [12 -7 0]]      ; slide vector ⟂ the normal
                [:fianco [45 3 2] [1 0 0] [0 9 -6]]
                [:becco [3 40 -2] [0 1 0] [-8 0 11]]]
        clean (fuse/fit-rigid (mapv (fn [[nm p d _]] (anchor nm p d)) planes))
        slid (fuse/fit-rigid (mapv (fn [[nm p d s]]
                                     ;; the twin's origin sits elsewhere on the plane
                                     (update (anchor nm p d) :to-pos
                                             #(m/v+ % (fuse/transform-dir known-rt s))))
                                   planes))]
    (is (nil? (:error slid)) (:error slid))
    (is (approx= 0.0 (:rms-mm slid) 1e-6)
        "sliding within the plane is not an error, so it costs nothing")
    (is (approx= 0.0 (rt-error slid [70 -50 40]) 1e-4)
        "and the motion is the same one the un-slid anchors gave")
    (is (approx= (rt-error clean [70 -50 40]) (rt-error slid [70 -50 40]) 1e-6))))

(deftest two-planes-leave-the-slide-along-their-intersection-free
  (let [fit (fuse/fit-rigid [(anchor :testa [0 0 0] [0 0 1])
                             (anchor :fianco [45 3 2] [1 0 0])])]
    (is (some? (:error fit))
        "two planes fix the rotation and two of three translations — refusing is honest")
    (is (re-find #"TERZO piano|intersezione" (:error fit)))))

(deftest two-planes-plus-one-real-point-are-enough
  ;; Nobody enumerated this case: it works because the guard is the RANK of the
  ;; system, not a checklist.
  (let [fit (fuse/fit-rigid [(anchor :testa [0 0 0] [0 0 1])
                             (anchor :fianco [45 3 2] [1 0 0])
                             (assoc (anchor :spigolo [2 38 -4] [0 1 0]) :point? true)])]
    (is (nil? (:error fit)) (:error fit))
    (is (= 2 (:planes fit)))
    (is (= 1 (:points fit)))
    (is (approx= 0.0 (rt-error fit [70 -50 40]) 1e-4))))

(deftest parallel-planes-do-not-determine-the-rotation
  (let [fit (fuse/fit-rigid [(anchor :sopra [0 0 0] [0 0 1])
                             (anchor :sotto [0 0 -20] [0 0 -1])
                             (anchor :mezzo [10 5 -10] [0 0 1])])]
    (is (some? (:error fit))
        "three planes whose normals share one direction pin only that direction")))

(deftest two-bare-points-do-not-determine-the-rotation
  (let [fit (fuse/fit-rigid [(anchor :a [0 0 0]) (anchor :b [45 3 2])])]
    (is (some? (:error fit))
        "the roll about the line joining them is free — refusing is the only honest answer")))

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

(def ^:private sessions
  "Two labelled sessions, as fuse/declared-anchors wants them: [[label marks] …].
   Note the auto-generated names collide across sessions — `:piano-1` exists in
   both and means different zones. That collision is exactly what the declared
   form is for."
  [[:A {:piano-1 {:position [0 0 0] :heading [0 0 1] :up [0 1 0]}
        :piano-2 {:position [40 1 2] :heading [1 0 0] :up [0 0 1]}}]
   [:B {:piano-1 {:position [5 5 5] :heading [1 0 0] :up [0 0 1]}
        :piano-2 {:position [9 9 9] :heading [0 0 1] :up [0 1 0]}}]])

(deftest declared-pairs-say-what-name-equality-cannot
  (testing "the pairs are read as written — crossed names included"
    (let [[anchors errs] (fuse/declared-anchors sessions
                                                [[:A/piano-1 :B/piano-2]   ; crossed on purpose
                                                 [:A/piano-2 :B/piano-1]]
                                                :A :B)]
      (is (empty? errs))
      (is (= 2 (count anchors)))
      (testing "each anchor is named after its SOURCE side, label included"
        (is (= [:B/piano-2 :B/piano-1] (mapv :name anchors))))
      (testing "and carries the right two poses"
        (is (v= [9 9 9] (:from-pos (first anchors))) "B's :piano-2 is the one moving")
        (is (v= [0 0 0] (:to-pos (first anchors))) "onto A's :piano-1"))))

  (testing "a reference that names no session is refused by name"
    (let [[_ errs] (fuse/declared-anchors sessions [[:piano-1 :B/piano-1]] :A :B)]
      (is (= 1 (count errs)))
      (is (re-find #"non dice a quale sessione" (first errs)))))

  (testing "an unknown label is refused by name"
    (let [[_ errs] (fuse/declared-anchors sessions [[:A/piano-1 :Z/piano-1]] :A :B)]
      (is (re-find #"etichetta :Z" (first errs)))))

  (testing "a mark that does not exist is refused by name"
    (let [[_ errs] (fuse/declared-anchors sessions [[:A/piano-1 :B/manca]] :A :B)]
      (is (re-find #"non esiste" (first errs)))))

  (testing "a pair that does not mention this session is simply not its anchor"
    (let [[anchors errs] (fuse/declared-anchors
                          (conj sessions [:C {:piano-1 {:position [1 1 1] :heading [0 1 0] :up [0 0 1]}}])
                          [[:A/piano-1 :C/piano-1]] :A :B)]
      (is (empty? errs))
      (is (empty? anchors) "B is not in that pair, so B gets no anchor from it"))))

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
