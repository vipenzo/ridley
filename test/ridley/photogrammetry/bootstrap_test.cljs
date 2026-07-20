(ns ridley.photogrammetry.bootstrap-test
  "Does the labelling actually get decided by evidence, and does a mislabel
   actually set off the alarm?

   Both questions are load-bearing. If the hypothesis search cannot pick the
   right labelling, the operator is back to naming axes by hand. If the
   residual report cannot separate a mislabelled edge from a merely
   imprecise one, then a silent corruption stays silent — which is the exact
   failure this whole apparatus exists to prevent."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.turntable-fit :as tt]
            [ridley.photogrammetry.bootstrap :as boot]
            [ridley.photogrammetry.synth :as synth]))

(def true-dims [42.2 15.0 80.0])
(def focal-px (* 4032 (/ 48.0 36.0)))
(def distance 280.0)
(def elevation 30.0)
(def true-yaw 0.7)          ; the object's unknown orientation at theta = 0
(def true-axis-params {:phi 0.4 :psi 0.03 :a 3.0 :b -2.0})

(defn- intrinsics [] {:fx focal-px :fy focal-px :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})
(defn- fmt [x n] (.toFixed (js/Number. x) n))
(defn- deg->rad [d] (/ (* d Math/PI) 180.0))

(def bootstrap-thetas (mapv deg->rad [0 -30 -60 -90]))

(defn- true-axis []
  (tt/axis-from-params (:phi true-axis-params) (:psi true-axis-params)
                       (:a true-axis-params) (:b true-axis-params)))

(defn- base-pose [] (synth/viewpoint 0.0 elevation distance))

(defn- true-poses []
  (mapv #(tt/pose-at-angle (base-pose) (true-axis) (+ true-yaw %)) bootstrap-thetas))

(defn- simulate-clicks
  "What a careful operator produces: for each photo, each group's visible
   edges clicked left to right, with a couple of pixels of hand error."
  [sigma-px seed]
  (let [g (synth/rng seed)
        k (intrinsics)]
    (mapv (fn [pose]
            (into {}
                  (for [grp [:vertical :top :bottom]
                        :let [order (boot/predicted-order true-dims pose k grp)]
                        :when (seq order)]
                    [grp (mapv (fn [e]
                                 (let [{:keys [corners]} (bf/edge-geometry true-dims e)
                                       pa (cam/project k pose (first corners))
                                       pb (cam/project k pose (second corners))
                                       [nx ny _] (bf/line-through pa pb)
                                       j (fn [p] [(+ (first p) (* nx sigma-px (synth/gauss g)))
                                                  (+ (second p) (* ny sigma-px (synth/gauss g)))])]
                                   {:p1 (j pa) :p2 (j pb)}))
                               order)])))
          (true-poses))))

(defn- opts [picks]
  {:picks picks
   :thetas bootstrap-thetas
   :intrinsics (intrinsics)
   :dims true-dims
   :base (base-pose)
   :axis-params true-axis-params
   :sigma-px 1.0
   :scale-constraint {:axis 2 :value 80.0 :sigma 0.02}
   :lm {:max-iterations 120}})

;; ---------------------------------------------------------------------------

(deftest edge-groups-partition-the-box
  (testing "every edge belongs to exactly one clickable group"
    (is (= 12 (+ (count boot/vertical-edges) (count boot/top-edges)
                 (count boot/bottom-edges))))
    (is (= (set (range 12))
           (set (concat boot/vertical-edges boot/top-edges boot/bottom-edges))))
    (is (every? #(= :vertical (boot/group-of %)) boot/vertical-edges))
    (is (every? #(= :top (boot/group-of %)) boot/top-edges))
    (is (every? #(= :bottom (boot/group-of %)) boot/bottom-edges))))

(deftest predicted-order-is-left-to-right
  (testing "a generic view sees 2 or 3 verticals, ordered by image x"
    (let [pose (first (true-poses))
          k (intrinsics)
          order (boot/predicted-order true-dims pose k :vertical)
          xs (map (fn [e]
                    (let [{:keys [corners]} (bf/edge-geometry true-dims e)]
                      (first (cam/project k pose
                                          (mapv #(* 0.5 (+ %1 %2))
                                                (first corners) (second corners))))))
                  order)]
      (is (<= 2 (count order) 3) (str "saw " (count order) " verticals"))
      (is (apply < xs) "must be sorted by image x"))))

(deftest bootstrap-recovers-the-labelling
  (println "\n=== EXP 15 — bootstrap: is the labelling decided by evidence? ===")
  (println "4 photos, operator clicks groups left-to-right, 1px hand error.")
  (println (str "True yaw at theta=0: " (fmt true-yaw 3) " rad"))
  (let [picks (simulate-clicks 1.0 4242)
        ranked (boot/bootstrap (opts picks))
        best (first ranked)
        runner (second ranked)]
    (println (str "  hypotheses scored : " (count ranked)))
    (println (str "  best   : yaw " (fmt (:yaw (:hypothesis best)) 3)
                  " sense " (:sense (:hypothesis best))
                  " -> " (fmt (:rms-px best) 2) " px on "
                  (:photos-used best) " photos"))
    (when runner
      (println (str "  runner : yaw " (fmt (:yaw (:hypothesis runner)) 3)
                    " sense " (:sense (:hypothesis runner))
                    " -> " (fmt (:rms-px runner) 2) " px")))
    (let [con (boot/consensus ranked)]
      (println (str "  leading hypotheses (within 1.5x of best): " (:n-leading con)))
      (println (str "  they agree on dims: " (:agree? con)
                    "  spread [" (str/join " " (map #(fmt % 3) (:dim-spread con))) "] mm"))
      (println (str "  dims recovered: ["
                    (str/join " " (map #(fmt % 2) (:dims (:fit best))))
                    "]  true [42.20 15.00 80.00]"))

      (testing "the winning hypothesis fits at the noise floor"
        (is (< (:rms-px best) 3.0)
            (str "best hypothesis reprojection " (fmt (:rms-px best) 2) " px")))
      (testing "it recovers the dimensions"
        (let [d (:dims (:fit best))]
          (is (< (Math/abs (- (nth d 0) 42.2)) 1.0) (str "X " (fmt (nth d 0) 3)))
          (is (< (Math/abs (- (nth d 1) 15.0)) 1.0) (str "Y " (fmt (nth d 1) 3)))))
      (testing "every hypothesis that fits well agrees on the measurement"
        ;; NOT 'there is a unique winner': a box is symmetric under a
        ;; 180-degree yaw, so that pair always ties and always agrees. What
        ;; would be fatal is two hypotheses fitting equally well and
        ;; disagreeing about the answer.
        (is (:agree? con)
            (str "leading hypotheses disagree on dims, spread "
                 (pr-str (:dim-spread con)) " mm — labelling is undecided"))))))

(defn- simulate-clicks-groups
  "Clicks restricted to a subset of the groups."
  [sigma-px seed groups]
  (mapv #(select-keys % groups) (simulate-clicks sigma-px seed)))

(deftest all-three-groups-are-required
  (println "\n=== EXP 15b — why the protocol demands all three groups ===")
  (doseq [groups [[:vertical] [:vertical :top] [:vertical :top :bottom]]]
    (let [picks (simulate-clicks-groups 1.0 4242 groups)
          ranked (boot/bootstrap (opts picks))
          best (first ranked)]
      (println (str "  " (pr-str groups) " -> "
                    (if best
                      (str (fmt (:rms-px best) 2) " px, dims ["
                           (str/join " " (map #(fmt % 2) (:dims (:fit best)))) "]")
                      "nothing scored")))))
  (println "  Verticals alone fit PERFECTLY with absurd dimensions. The caliper")
  (println "  pins Z, but Z is only observable when both the top and the bottom")
  (println "  of the object are seen; without them the scale constraint never")
  (println "  bites and X/Y run away with the camera distance. A low residual")
  (println "  is not evidence of a correct answer.")
  (testing "verticals alone are under-determined and must not be trusted"
    (let [picks (simulate-clicks-groups 1.0 4242 [:vertical])
          best (first (boot/bootstrap (opts picks)))]
      (when best
        (is (> (Math/abs (- (nth (:dims (:fit best)) 0) 42.2)) 5.0)
            "verticals-only is expected to give a wrong answer at a low residual"))))
  (testing "all three groups recover the truth"
    (let [picks (simulate-clicks-groups 1.0 4242 [:vertical :top :bottom])
          best (first (boot/bootstrap (opts picks)))]
      (is (< (Math/abs (- (nth (:dims (:fit best)) 0) 42.2)) 1.0)
          (str "X " (fmt (nth (:dims (:fit best)) 0) 3))))))

(deftest registration-metric-measures-registration
  (println "\n=== EXP 17 — la metrica di registrazione misura la registrazione ===")
  ;; The claim under test: fiducials excluded from the fit give a number that
  ;; tracks CAMERA error, and does so independently of the dimensions. A
  ;; metric that stayed at zero when the cameras were wrong, or that simply
  ;; restated the edge residual, would be worthless for the design decision.
  (let [poses (true-poses)
        k (intrinsics)
        dims true-dims
        marks (vec (for [[i pose] (map-indexed vector poses)
                         m boot/target-marks
                         :let [p3 (boot/mark-position dims m)
                               px (cam/project k pose p3)]
                         :when px]
                     {:view i :id (:id m) :px px}))
        axis (true-axis)
        ;; the exact cameras: the marks must reproject onto themselves
        exact (boot/registration-report {:dims dims} (base-pose) axis
                                        (mapv #(+ true-yaw %) bootstrap-thetas)
                                        k marks)
        ;; ONE camera off by a degree. It has to be differential: shifting
        ;; every camera by the same angle is a rigid motion of the whole rig,
        ;; the triangulated point rides along with it, and consistency is
        ;; rightly untouched — measured at 0.00 px. That is the same
        ;; common-mode gauge that the turntable angles have.
        nudged (boot/registration-report {:dims dims} (base-pose) axis
                                         (vec (map-indexed
                                               (fn [i th]
                                                 (+ true-yaw th
                                                    (if (= i 1) (deg->rad 1.0) 0.0)))
                                               bootstrap-thetas))
                                         k marks)
        ;; dimensions wrong, cameras right. The marks were CLICKED where the
        ;; true part put them, so a wrong box must not move this number: the
        ;; metric never consults the dimensions.
        wrong-dims (boot/registration-report {:dims [40.0 13.0 80.0]} (base-pose) axis
                                             (mapv #(+ true-yaw %) bootstrap-thetas)
                                             k marks)]
    (println (str "  tacche / osservazioni    : " (:n-marks exact) " / " (:n exact)))
    (println (str "  camere esatte            : " (fmt (:rms-px exact) 4) " px"))
    (println (str "  UNA camera storta di 1°  : " (fmt (:rms-px nudged) 2) " px"))
    (println (str "  quote sbagliate di ~2mm  : " (fmt (:rms-px wrong-dims) 4) " px"))
    (println "  -> la consistenza fra viste non tocca le quote: una scatola")
    (println "     sbagliata non la sporca né la lusinga. Misura solo se le")
    (println "     camere sono d'accordo fra loro, che è la domanda da cui")
    (println "     dipende la funzione 'clicco una foto e il viewport ci va'.")

    (testing "consistent cameras give a consistent triangulation"
      (is (< (:rms-px exact) 0.01)
          (str "expected ~0, got " (fmt (:rms-px exact) 6))))
    (testing "a one-degree DIFFERENTIAL camera error shows up"
      (is (> (:rms-px nudged) 1.0)
          (str "1 deg on one camera should be visible, got "
               (fmt (:rms-px nudged) 2))))
    (testing "wrong dimensions do NOT contaminate it"
      ;; The flaw this replaced: anchoring the marks to the fitted faces made a
      ;; 2 mm dimension error read as 14.22 px, swamping the 5.82 px from a
      ;; 1 degree camera error — the metric measured the very thing it was
      ;; meant to be independent of.
      (is (< (Math/abs (- (:rms-px wrong-dims) (:rms-px exact))) 0.01)
          (str "dimension error must not move the registration number: exact "
               (fmt (:rms-px exact) 4) " vs wrong-dims "
               (fmt (:rms-px wrong-dims) 4))))))

(deftest mislabel-sets-off-the-alarm
  (println "\n=== EXP 16 — the mislabel alarm ===")
  (println "Correct labelling, then two edge labels deliberately swapped.")
  (let [picks (simulate-clicks 0.5 99)
        ranked (boot/bootstrap (opts picks))
        best (first ranked)
        obs (:observations best)
        clean (boot/residual-report (:fit best) obs {:sigma-px 1.0})
        ;; swap the edge labels of two observations in the same photo, which
        ;; is precisely what a slip of the hand in the click order produces
        v-idx (keep-indexed (fn [i o] (when (= 0 (:view o)) i)) obs)
        [i j] (take 2 v-idx)
        corrupted (-> (vec obs)
                      (assoc-in [i :edge] (:edge (nth obs j)))
                      (assoc-in [j :edge] (:edge (nth obs i))))
        thetas (mapv #(+ (:yaw (:hypothesis best))
                         (* (:sense (:hypothesis best)) %))
                     bootstrap-thetas)
        refit (tt/fit corrupted thetas (intrinsics) true-dims (base-pose)
                      true-axis-params
                      {:sigma-px 1.0
                       :scale-constraint {:axis 2 :value 80.0 :sigma 0.02}
                       :angle-prior-sigma-deg 1.0
                       :lm {:max-iterations 120}})
        dirty (boot/residual-report refit corrupted {:sigma-px 1.0})]
    (println (str "  clean    : median " (fmt (:median-px clean) 2)
                  " px, worst " (fmt (:residual-px (first (:observations clean))) 2)
                  " px, suspects " (:n-suspect clean)))
    (println (str "  swapped  : median " (fmt (:median-px dirty) 2)
                  " px, worst " (fmt (:residual-px (first (:observations dirty))) 2)
                  " px, suspects " (:n-suspect dirty)))
    (println (str "  the two swapped observations were edges "
                  (:edge (nth obs i)) " and " (:edge (nth obs j))
                  " in photo 0"))
    (let [flagged (set (map (juxt :view :edge)
                            (filter :suspect? (:observations dirty))))]
      (println (str "  flagged  : " (pr-str flagged)))
      (testing "a clean extraction raises no false alarms"
        (is (zero? (:n-suspect clean))
            (str "clean run flagged " (:n-suspect clean) " observations")))
      (testing "the swap is detected"
        (is (pos? (:n-suspect dirty)) "swapped labels must be flagged"))
      (testing "the alarm points at the photo that was actually corrupted"
        (is (some #(= 0 (first %)) flagged)
            (str "expected a flag in photo 0, got " (pr-str flagged)))))))
