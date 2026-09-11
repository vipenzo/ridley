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

(deftest three-planes-alone-are-ambiguous-however-clean-they-are
  ;; Not a precision problem and not a data problem: a half-turn about any one of
  ;; three normals carries the three faces back onto themselves, so three planes
  ;; admit four placements that fit identically. On a near-symmetric object they
  ;; are mirror images — which is exactly what Vincenzo saw, session by session.
  (let [fit (fuse/fit-rigid [(anchor :testa [0 0 0] [0 0 1])
                             (anchor :fianco [45 3 2] [1 0 0])
                             (anchor :becco [3 40 -2] [0 1 0])])]
    (is (some? (:error fit)) "must refuse rather than pick one of the four")
    (is (re-find #"placements|ASYMMETRIC" (:error fit)))))

(deftest sliding-a-mark-INSIDE-its-plane-changes-nothing
  ;; THE property this design exists for (Vincenzo 2026-08-05: a plane is easy to
  ;; find again across sessions, a specific point on it is not). The two sessions
  ;; put the origin of each mark somewhere else on the same physical plane — as
  ;; they always will, since the origin is the centroid of wherever the user
  ;; happened to click — and the recovered motion must be the SAME.
  (let [planes [[:testa [0 0 0] [0 0 1] [12 -7 0]]      ; slide vector ⟂ the normal
                [:fianco [45 3 2] [1 0 0] [0 9 -6]]
                [:becco [3 40 -2] [0 1 0] [-8 0 11]]]
        ;; plus the asymmetric anchor three planes always need (see
        ;; three-planes-alone-are-ambiguous): the point is what makes the answer
        ;; unique, the planes are what this test is about.
        pt (assoc (anchor :spigolo [12 -9 22] [0 1 0]) :point? true)
        clean (fuse/fit-rigid (conj (mapv (fn [[nm p d _]] (anchor nm p d)) planes) pt))
        slid (fuse/fit-rigid (conj (mapv (fn [[nm p d s]]
                                           ;; the twin's origin sits elsewhere on the plane
                                           (update (anchor nm p d) :to-pos
                                                   #(m/v+ % (fuse/transform-dir known-rt s))))
                                         planes)
                                   pt))]
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
    ;; the refusal must name a remedy that EXISTS. It used to send the user to a
    ;; point-mark gesture the app does not have; now the first thing it offers is
    ;; an edge, which is both reachable and the stronger anchor.
    (is (re-find #"intersection" (:error fit)))
    (is (re-find #"EDGE" (:error fit))
        (str "the reachable remedy should come first: " (:error fit)))))

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
    (is (re-find #"at least TWO" (:error fit)))))

(deftest collinear-marks-are-refused
  (let [fit (fuse/fit-rigid [(anchor :a [0 0 0]) (anchor :b [20 0 0]) (anchor :c [50 0 0])])]
    (is (some? (:error fit)) "three points on a line leave the roll about it free")))

(deftest marks-too-close-together-are-refused
  (let [fit (fuse/fit-rigid [(anchor :a [0 0 0]) (anchor :b [1.5 0.5 0]) (anchor :c [0 1.2 0.4])])]
    (is (re-find #"too close" (:error fit))
        "a 1.6 mm baseline on a 100 mm object multiplies click noise")))

(deftest the-floor-gate-identical-sessions-fuse-to-the-identity
  ;; The brief's gate 1, in synthetic form: the same marks in both frames.
  (let [same (fn [nm p d] {:name nm :from-pos p :to-pos p :from-dir d :to-dir d})
        fit (fuse/fit-rigid [(same :a [0 0 0] [0 0 1])
                             (same :b [40 1 2] [1 0 0])
                             (same :c [2 38 -3] [0 1 0])
                             (assoc (same :d [7 -9 21] [0 1 0]) :point? true)])]
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

;; --- Vincenzo's real sessions of the clip, 2026-08-06 -------------------------
;; Three poses on the plate, the same three zones marked in each. The first
;; fusion came back with normals 152° out, which looked like three wrong marks
;; and was not: a plane HAS NO SIDE, and the sign the stage gives a fitted normal
;; ('toward the cameras') is a coin toss on a turntable, where the cameras'
;; mean sits near the axis. Read as rays, his zones were irreconcilable — the
;; three normal triples did not even have the same handedness. Read as planes,
;; they agree to within two degrees.

(def ^:private clip-normals
  {:A {:flank [0.5817 -0.8132 0.0169] :head [-0.0199 0.0108 0.9997] :tip [0.9839 0.178 -0.0135]}
   :B {:flank [0.8993 0.0519 -0.4342] :head [-0.0479 0.9976 -0.0503] :tip [-0.0065 0.0393 0.9992]}
   :C {:flank [0.5611 0.8274 0.0244] :head [-0.1676 0.0201 0.9857] :tip [0.9705 -0.1744 0.1666]}})

(def ^:private clip-origins
  {:A {:flank [13.4631 16.2897 5.1921] :head [0.6277 1.9013 8.7112] :tip [-18.8248 -1.5046 9.7131]}
   :B {:flank [-12.7218 -0.6151 6.6052] :head [-3.4436 -3.9861 13.2627] :tip [-1.2977 -3.5465 40.7239]}
   :C {:flank [-14.2412 20.0022 7.7839] :head [7.1592 8.167 7.7172] :tip [19.814 4.2967 11.7558]}})

(defn- clip-anchors [to from]
  (mapv (fn [nm] {:name nm
                  :to-pos (get-in clip-origins [to nm]) :to-dir (get-in clip-normals [to nm])
                  :from-pos (get-in clip-origins [from nm]) :from-dir (get-in clip-normals [from nm])})
        [:flank :head :tip]))

(deftest vincenzos-three-planes-are-ambiguous-and-say-so
  ;; What he saw across nine photos, in one word: 'specchiato'. Three planes, read
  ;; as unsigned, admit several placements that fit identically, and on a piece
  ;; with a near mirror symmetry those placements ARE mirror images of each other.
  ;; The fusion used to pick one per session — a different one per session, which
  ;; is why B came out mirrored about :head and C about :tip.
  (let [fits (mapv #(fuse/fit-rigid (clip-anchors (first %) (second %)))
                   [[:A :B] [:A :C] [:B :C]])
        refused (filterv :error fits)]
    (is (seq refused)
        "at least one pair must own up to the ambiguity instead of picking a branch")
    (is (every? #(re-find #"placements|ASYMMETRIC" (:error %)) refused)
        "and say what would settle it")))

(deftest one-asymmetric-point-settles-the-mirror
  ;; The remedy the refusal names. A point is not mirror-symmetric with anything:
  ;; the rival placement puts it somewhere else, and the tie is over.
  (let [planes [(anchor :testa [0 0 0] [0 0 1])
                (anchor :fianco [45 3 2] [1 0 0])
                (anchor :becco [3 40 -2] [0 1 0])]
        with-point (conj planes (assoc (anchor :spigolo [12 -9 22] [0 1 0]) :point? true))
        ambiguous (fuse/fit-rigid planes)
        settled (fuse/fit-rigid with-point)]
    (is (some? (:error ambiguous)) "three planes alone: refused")
    (is (nil? (:error settled)) (:error settled))
    (is (approx= 0.0 (rt-error settled [70 -50 40]) 1e-3)
        "and the motion recovered is the known one")
    (is (true? (:distances-testify? settled))
        "with a point in it the millimetres finally mean something")))

(deftest zones-that-really-are-different-are-still-refused
  ;; The check that survives sign-agnosticity: the ACUTE angle between two faces
  ;; cannot change when the object moves. 20° against 60° is no flip.
  (let [anchors [{:name :a :from-pos [0 0 0] :to-pos [0 0 0]
                  :from-dir [0 0 1] :to-dir [0 0 1]}
                 {:name :b :from-pos [10 0 0] :to-pos [10 0 0]
                  :from-dir [0 (Math/sin 0.35) (Math/cos 0.35)]   ; 20° from :a
                  :to-dir [0 (Math/sin 1.05) (Math/cos 1.05)]}    ; 60° from :a
                 {:name :c :from-pos [0 10 0] :to-pos [0 10 0]
                  :from-dir [1 0 0] :to-dir [1 0 0]}]
        err (fuse/normal-consistency-error anchors)]
    (is (some? err))
    (is (re-find #":a|:b" err) "the offending pair is named")
    (is (= err (:error (fuse/fit-rigid anchors))))))

(deftest a-shared-name-is-the-declaration-by-default
  (let [a {:testa {:position [0 0 0] :heading [0 0 1] :up [0 1 0]}
           :becco {:position [40 1 2] :heading [1 0 0] :up [0 0 1]}
           :solo-di-a {:position [5 5 5] :heading [0 1 0] :up [0 0 1]}}
        b {:testa {:position [9 9 9] :heading [1 0 0] :up [0 0 1]}
           :becco {:position [1 2 3] :heading [0 1 0] :up [0 0 1]}
           :solo-di-b {:position [7 7 7] :heading [0 0 1] :up [0 1 0]}}
        anchors (fuse/shared-name-anchors a b)]
    (is (= [:becco :testa] (mapv :name anchors))
        "only the names present in BOTH — the ones the user wrote alike on purpose")
    (is (= [:becco :testa] (mapv :ref-name anchors))
        "and each carries the zone's name, which the fused value binds to the zone")
    (testing "the pose that moves is the other session's"
      (is (v= [1 2 3] (:from-pos (first anchors))))
      (is (v= [40 1 2] (:to-pos (first anchors)))))))

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
      (is (re-find #"does not say which session" (first errs)))))

  (testing "an unknown label is refused by name"
    (let [[_ errs] (fuse/declared-anchors sessions [[:A/piano-1 :Z/piano-1]] :A :B)]
      (is (re-find #"label :Z" (first errs)))))

  (testing "a mark that does not exist is refused by name"
    (let [[_ errs] (fuse/declared-anchors sessions [[:A/piano-1 :B/manca]] :A :B)]
      (is (re-find #"does not exist" (first errs)))))

  (testing "a row can name MORE than two sessions — one row per ZONE, not per couple"
    ;; With three sessions, [[:A/p1 :B/p1 :C/p1] …] says 'this zone, seen by all
    ;; three'. Each secondary session reads the same row against the reference,
    ;; so three zones cost three rows and not six (Vincenzo 2026-08-05).
    (let [ss (conj sessions [:C {:piano-7 {:position [1 1 1] :heading [0 1 0] :up [0 0 1]}}])
          rows [[:A/piano-1 :B/piano-2 :C/piano-7]]
          [for-b eb] (fuse/declared-anchors ss rows :A :B)
          [for-c ec] (fuse/declared-anchors ss rows :A :C)]
      (is (and (empty? eb) (empty? ec)))
      (is (= [:B/piano-2] (mapv :name for-b)))
      (is (= [:C/piano-7] (mapv :name for-c)) "the same row serves C too")
      (is (v= [1 1 1] (:from-pos (first for-c))))))

  (testing "a pair that does not mention this session is simply not its anchor"
    (let [[anchors errs] (fuse/declared-anchors
                          (conj sessions [:C {:piano-1 {:position [1 1 1] :heading [0 1 0] :up [0 0 1]}}])
                          [[:A/piano-1 :C/piano-1]] :A :B)]
      (is (empty? errs))
      (is (empty? anchors) "B is not in that pair, so B gets no anchor from it"))))

(deftest transported-poses-keep-their-frame
  (let [fit (fuse/fit-rigid [(anchor :a [0 0 0] [0 0 1])
                             (anchor :b [40 2 1] [1 0 0])
                             (anchor :c [3 35 -2] [0 1 0])
                             (assoc (anchor :d [9 -8 25] [0 1 0]) :point? true)])
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

(deftest the-culprit-is-named-instead-of-spread-over-everyone
  ;; Vincenzo's session B, 2026-08-06: rms 2.5 mm with every anchor looking
  ;; mediocre and none obviously guilty — because least squares does not leave a
  ;; wrong anchor sticking out, it shares the damage. Dropping :flank took it to
  ;; 0.07 mm, and :flank was the opposite of the two alike faces of the clip.
  (let [B {:flank {:position [-12.7218 -0.6151 6.6052] :heading [0.8993 0.0519 -0.4342]}
           :head {:position [-3.4436 -3.9861 13.2627] :heading [-0.0479 0.9976 -0.0503]}
           :tip {:position [-1.2977 -3.5465 40.7239] :heading [-0.0065 0.0393 0.9992]}
           :notch {:position [0.9154 -7.916 45.1577] :point? true}}
        A {:flank {:position [13.4631 16.2897 5.1921] :heading [0.5817 -0.8132 0.0169]}
           :head {:position [0.6277 1.9013 8.7112] :heading [-0.0199 0.0108 0.9997]}
           :tip {:position [-18.8248 -1.5046 9.7131] :heading [0.9839 0.178 -0.0135]}
           :notch {:position [-23.2685 -0.4632 14.5843] :point? true}}
        anchors (mapv (fn [nm] (cond-> {:name nm
                                        :to-pos (:position (nm A)) :from-pos (:position (nm B))
                                        :to-dir (:heading (nm A)) :from-dir (:heading (nm B))}
                                 (:point? (nm A)) (assoc :point? true)))
                      [:flank :head :tip :notch])
        fit (fuse/fit-rigid anchors)]
    (is (nil? (:error fit)) (:error fit))
    (is (> (:rms-mm fit) 1.0) "the fit as a whole is poor")
    (is (= :flank (:name (:suspect fit))) "and the one to blame is named")
    (is (< (:rms-without (:suspect fit)) 0.2) "because without it the rest agree")

    (testing "a fit that is already good accuses no one"
      (let [clean (fuse/fit-rigid (filterv #(not= :flank (:name %)) anchors))]
        (is (nil? (:suspect clean)))))

    (testing "and dropping the POINT is never taken as evidence"
      ;; three planes left: their millimetres are zero by construction, which
      ;; would frame the anchor that was doing the work
      (is (not= :notch (:name (:suspect fit)))))))

;; ---------------------------------------------------------------------------
;; Edges as anchors
;;
;; The case that forced them (Vincenzo, 2026-08-14): an object whose only two
;; flat zones are PARALLEL. Two parallel planes are one direction, no care makes
;; them two, and the remaining advice — a mark on a real point — named a gesture
;; that does not exist. Four measured edges per session sat in `:edges`, read by
;; nothing.

(defn- edge-anchor
  "One named twin EDGE: a point on the line and a direction along it, in session
   B's frame, plus the same line in A's frame."
  ([nm pos dir] (edge-anchor nm pos dir known-rt))
  ([nm pos dir rt]
   {:name nm :edge? true
    :from-pos pos :from-dir dir
    :to-pos (fuse/transform-point rt pos)
    :to-dir (fuse/transform-dir rt dir)}))

(deftest two-parallel-planes-and-ONE-edge-are-still-not-enough
  (testing "sliding along the edge is free, and the parallel planes do not mind

   Worth pinning because it is the plausible wrong answer, and I wrote it as a
   passing test before checking it. Count the freedoms: two parallel planes fix
   the normal (2 of the rotation) and the distance along it (1 of the
   translation) — the second plane repeats what the first said. One edge fixes
   the remaining spin and the translation ACROSS itself, but by construction says
   nothing about sliding ALONG itself. Six minus five is one, and it is real.

   The guard that catches it is the RANK of the fitted system, not a checklist —
   which is why it caught a case nobody had enumerated."
    (let [fit (fuse/fit-rigid [(anchor :sopra [0 0 0] [0 0 1])
                               (anchor :sotto [0 0 -20] [0 0 1])
                               (edge-anchor :spigolo [12 -4 -8] [0.8 0.6 0.0])])]
      (is (some? (:error fit))
          "refusing is honest: one degree of freedom is genuinely unconstrained"))))

(deftest two-parallel-planes-and-two-crossing-edges-are-enough
  (testing "the grinder's case, as it actually resolves

   An object whose only flat zones are parallel (Vincenzo, 2026-08-14). The
   planes contribute what they can; the second edge, not parallel to the first,
   closes the slide the first one left open."
    (let [fit (fuse/fit-rigid [(anchor :sopra [0 0 0] [0 0 1])
                               (anchor :sotto [0 0 -20] [0 0 1])
                               (edge-anchor :e1 [12 -4 -8] [0.8 0.6 0.0])
                               (edge-anchor :e2 [-6 9 -3] [0.5 -0.87 0.0])])]
      (is (nil? (:error fit)) (:error fit))
      (is (approx= 0.0 (rt-error fit [70 -50 40]) 1e-4)
          "and it is the SAME motion, checked 100mm away from the anchors"))))

(deftest two-edges-alone-determine-the-motion
  (testing "two non-parallel lines are six constraints between them"
    (let [fit (fuse/fit-rigid [(edge-anchor :a [0 0 0] [1 0 0])
                               (edge-anchor :b [5 20 -3] [0.1 0.9 0.2])])]
      (is (nil? (:error fit)) (:error fit))
      (is (approx= 0.0 (rt-error fit [80 -60 55]) 1e-4)))))

(deftest two-parallel-edges-are-refused
  (testing "parallel lines leave the roll about their common direction free"
    (let [fit (fuse/fit-rigid [(edge-anchor :a [0 0 0] [1 0 0])
                               (edge-anchor :b [0 15 6] [1 0 0])])]
      (is (some? (:error fit))
          "two rails say nothing about turning around them — refusing is honest"))))

(deftest painting-an-edge-from-either-end-is-the-same-edge
  (testing "neither the direction painted nor where you started is a claim

   An edge is declared by painting over it, and nothing decides which end you
   start from or which way you sweep. So a reversed direction, and a :position
   slid anywhere along the line, must give the SAME fit — the same freedom a
   plane mark's origin has within its plane."
    (let [base [(anchor :sopra [0 0 0] [0 0 1])
                (anchor :fianco [45 3 2] [1 0 0])
                (edge-anchor :spigolo [12 -4 -8] [0.8 0.6 0.0])]
          straight (fuse/fit-rigid base)
          ;; same line: direction reversed, and the point moved 30mm along it
          flipped (fuse/fit-rigid
                   (conj (vec (take 2 base))
                         (let [e (nth base 2)
                               d (m/normalize (:from-dir e))]
                           (assoc e
                                  :from-dir (mapv - d)
                                  :from-pos (mapv + (:from-pos e) (mapv #(* 30.0 %) d))))))]
      (is (nil? (:error flipped)) (:error flipped))
      (is (approx= (rt-error straight [70 -50 40]) (rt-error flipped [70 -50 40]) 1e-6)
          "the same line, described differently, is the same anchor"))))

(deftest a-mispaired-edge-is-caught-before-any-fit
  (testing "the angle between two edges cannot change under a rigid motion either

   The pre-check that catches a mis-paired PLANE has to cover edges for the same
   reason and by the same argument: angles are invariant, so a pair that reads
   80° in one session and 10° in the other is not the same pair of edges. Without
   this, the distance residuals can still be driven to zero and the answer looks
   perfect."
    (let [good (edge-anchor :a [0 0 0] [1 0 0])
          ;; declared to be the same edge, but its twin runs 80° off
          wrong (assoc (edge-anchor :b [5 20 -3] [0 1 0])
                       :from-dir [0.9 0.436 0.0])
          fit (fuse/fit-rigid [good wrong (anchor :piano [3 3 3] [0 0 1])])]
      (is (some? (:error fit)))
      (is (re-find #"same zones" (:error fit))
          (str "expected the mis-pairing to be named, got: " (:error fit))))))

(deftest an-edges-reported-residual-is-the-distance-between-the-LINES
  (testing "not between the two origins, which are wherever the painting started

   The report is what a user acts on, so measuring the wrong quantity there is
   not cosmetic. Edges were first reported through the point branch, and a
   perfectly matched pair came back at 12-20mm with an rms of 13mm — numbers that
   described only where two people began their strokes, under a fit that was
   sound (Vincenzo 2026-08-14). He read them as a bad fusion, which is exactly
   what they looked like.

   Here the two twins are the SAME line with their stored positions slid 40mm
   apart along it. The residual must be zero."
    (let [dir [0.6 0.8 0.0]
          slid (mapv #(* 40.0 %) dir)
          fit (fuse/fit-rigid
               [(anchor :sopra [0 0 0] [0 0 1])
                (anchor :fianco [45 3 2] [1 0 0])
                ;; same physical line; the 'from' side starts 40mm further along
                (let [e (edge-anchor :spigolo [10 -5 -6] dir)]
                  (assoc e :from-pos (mapv + (:from-pos e) slid)))])
          row (first (filter #(= :spigolo (:name %)) (:per-anchor fit)))]
      (is (nil? (:error fit)) (:error fit))
      (is (= :spigolo (:kind row)) "and it must be reported AS an edge")
      (is (< (:residual-mm row) 1e-6)
          (str "slid 40mm along its own line, the residual must stay 0, got "
               (:residual-mm row)))
      (is (< (:rms-mm fit) 1e-6)
          (str "and the rms is built from those, so it must not inherit the slide: "
               (:rms-mm fit))))))
