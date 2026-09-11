(ns ridley.photogrammetry.plate-calib-test
  "A warped plate is BUILT here, photographed synthetically, and then has to be
   recovered from the photographs alone. The nominal plate — the flat one the
   session would otherwise believe in — is what the solver starts from, so every
   test measures the same thing: does the calibration find the millimetre the
   model was wrong by?"
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.plate :as plate]
            [ridley.photogrammetry.plate-calib :as pc]
            [ridley.photogrammetry.pnp :as pnp]
            [ridley.photogrammetry.synth :as synth]))

;; ---------------------------------------------------------------------------
;; A plate, flat and warped

(def ^:private plate-d 300.0)
(def ^:private img-w 1920)
(def ^:private img-h 1440)

(defn- nominal-marks
  "The crown the model believes in — the twelve marks, in pick order. The
   zero-index is deliberately absent: it is never clicked (bridge/pnp-target-points
   drops it), so no ray ever crosses it and it has no measurable position. Its job
   is identity, not geometry."
  []
  (let [a (:anchors (plate/registration-plate :d plate-d))]
    (mapv (fn [i] (:position (get a (keyword (str "m" (when (< i 10) "0") i)))))
          (range 12))))

;; Measured on the real ⌀300 plate on 2026-08-13 (12 registered views): three
;; marks proud of the plane by more than a millimetre, the rest within a quarter.
;; The synthetic warp reproduces that shape — a gentle saddle plus one bad mark —
;; because a calibration that only recovers uniform errors would recover nothing
;; interesting: a uniform error IS the gauge, and the gauge is not measurable.
(def ^:private warp-mm
  [-1.63 0.46 0.68 -0.25 0.11 -0.15 1.24 -0.01 -0.04 0.33 1.37 0.57])

(defn- true-marks
  "The plate as it really is: nominal, warped out of plane, then put back on the
   model's own frame — so what is left is only the SHAPE, which is the only thing
   photographs can measure. This is what the calibration must return."
  []
  (let [nom (nominal-marks)]
    (pc/regauge (mapv (fn [[x y z] dz] [x y (+ z dz)]) nom warp-mm) nom)))

;; ---------------------------------------------------------------------------
;; Photographing it

(def ^:private intrinsics (cam/intrinsics-from-fov 50.0 img-w img-h))

(defn- ring-poses
  "`n` cameras around the plate at `elev` degrees above it, all looking at the
   centre — a turntable session."
  [n elev dist]
  (let [e (* (/ elev 180.0) Math/PI)]
    (mapv (fn [i]
            (let [a (* 2.0 Math/PI (/ (double i) n))]
              (cam/look-at-pose [(* dist (Math/cos e) (Math/cos a))
                                 (* dist (Math/cos e) (Math/sin a))
                                 (* dist (Math/sin e))]
                                [0.0 0.0 0.0] [0.0 0.0 1.0])))
          (range n))))

(defn- shoot
  "Photograph `marks` from `poses`: the picks a perfect click would produce."
  [poses marks]
  (mapv (fn [pose]
          {:pose pose
           :intrinsics intrinsics
           :picks (into {} (keep (fn [j]
                                   (when-let [px (cam/project intrinsics pose (nth marks j))]
                                     [j px]))
                                 (range (count marks))))})
        poses))

(defn- noisy
  "The same views with `sigma` pixels of click noise on every pick."
  [views sigma rng]
  (mapv (fn [v] (update v :picks
                        (fn [ps] (into {} (for [[ci [u vv]] ps]
                                            [ci [(+ u (* sigma (synth/gauss rng)))
                                                 (+ vv (* sigma (synth/gauss rng)))]])))))
        views))

(defn- as-session
  "What the app would have: the true picks, but every pose SOLVED against the
   nominal plate — the wrong model, which is the whole point."
  [views nominal]
  (mapv (fn [{:keys [picks] :as v}]
          (let [corr (vec (for [[ci px] picks] {:world (nth nominal ci) :px px}))
                sol (pnp/solve-pnp corr intrinsics {:method :planar :max-outliers 0})]
            (assoc v :pose (:pose sol))))
        views))

(defn- rms-vs [a b]
  (Math/sqrt (/ (reduce + 0.0 (map (fn [p q] (let [d (la/v-sub p q)] (la/v-dot d d))) a b))
                (count a))))

;; ---------------------------------------------------------------------------

(deftest the-warp-is-recovered-from-the-photographs
  (testing "a plate warped by ±1.6mm is measured back to a tenth of a millimetre"
    (let [nom (nominal-marks)
          truth (true-marks)
          session (as-session (shoot (ring-poses 8 35.0 600.0) truth) nom)
          r (pc/calibrate session nom)]
      (is (nil? (:error r)))
      ;; the model was wrong by this much, and it is not a small amount
      (is (> (rms-vs nom truth) 0.5)
          "the synthetic warp must be big enough to be worth measuring")
      ;; ...and the calibration finds it
      (is (< (rms-vs (:marks r) truth) 0.1)
          (str "recovered marks are " (rms-vs (:marks r) truth) "mm from the truth"))
      ;; the residual it was fighting collapses
      (is (< (:rms-after r) (* 0.2 (:rms-before r)))
          (str "reprojection " (:rms-before r) " -> " (:rms-after r) " px")))))

(deftest the-worst-mark-is-named
  (testing "the report points at the mark the plate is actually wrong about"
    (let [nom (nominal-marks)
          truth (true-marks)
          session (as-session (shoot (ring-poses 8 35.0 600.0) truth) nom)
          {:keys [deviation-mm worst-mm]} (pc/calibrate session nom)
          worst-idx (first (apply max-key second (map-indexed vector deviation-mm)))]
      ;; mark 0 carries the biggest warp (-1.63mm) in the synthetic plate
      (is (= 0 worst-idx))
      (is (> worst-mm 1.0)))))

(deftest a-true-plate-is-left-alone
  (testing "calibrating a plate that is already right moves nothing"
    (let [nom (nominal-marks)
          session (as-session (shoot (ring-poses 8 35.0 600.0) nom) nom)
          r (pc/calibrate session nom)]
      (is (< (:worst-mm r) 0.05)
          (str "a flat plate must stay flat, moved " (:worst-mm r) "mm")))))

(deftest the-scale-is-not-measured
  (testing "a plate 5% larger than the model comes back the MODEL's size, not its own

   This is the gauge, and it is a refusal, not a bug: a bigger plate photographed
   from further away makes exactly the same image, so no set of photographs can
   tell the two apart. The calibration measures SHAPE and hands scale back to
   `:d`. If this test ever fails the other way — if the marks come back 5% out —
   the calibration has started inventing a size it cannot see, and every
   measurement downstream inherits it.

   Pinned to :free deliberately. Under :out-of-plane a mark cannot move radially
   at all, so the crown radius comes back unchanged by construction and the test
   would pass while proving nothing — it is the GAUGE that has to do this work,
   and only :free gives it the freedom to fail."
    (let [nom (nominal-marks)
          big (mapv #(la/v-scale % 1.05) nom)
          session (as-session (shoot (ring-poses 8 35.0 600.0) big) nom)
          r (pc/calibrate session nom {:mode :free})
          radius (fn [ms] (/ (reduce + 0.0 (map (fn [[x y _]] (Math/hypot x y)) (take 12 ms))) 12.0))]
      (is (< (Math/abs (- (radius (:marks r)) (radius nom))) 0.05)
          (str "crown radius came back " (radius (:marks r))
               ", the model says " (radius nom))))))

(deftest a-result-too-big-to-be-a-plate-is-refused
  (testing "one badly-registered view must not be absorbed into the plate's shape

   The failure mode this guards is silent and total: given a view whose pose is
   wrong, the alternation happily bends the PLATE to explain it, and from then on
   every measurement in the session is referred to a ruler that was invented to
   fit a mistake. Refusing is the only honest answer, and the message has to send
   the user back to the registration, not to the printer."
    (let [nom (nominal-marks)
          truth (true-marks)
          session (as-session (shoot (ring-poses 8 35.0 600.0) truth) nom)
          ;; One view with two adjacent marks clicked in the wrong order. Two
          ;; things had to be ruled out before settling on this:
          ;;
          ;; - NUDGING a pose proves nothing. The alternation re-solves every pose
          ;;   from the picks, so a bad starting pose is corrected, not absorbed.
          ;; - ROTATING a view's labels by one proves almost nothing either, and
          ;;   for an interesting reason: on an equally-spaced crown that
          ;;   relabelling is very nearly a rigid TURN of the plate, so PnP simply
          ;;   returns a pose rotated by one mark and the rays land where they
          ;;   should. That near-invisibility is precisely why the crown needs a
          ;;   zero-index — but it makes the mislabelling harmless HERE.
          ;;
          ;; A swap is neither: it is a genuine contradiction between views, and
          ;; contradictions have nowhere to go but into the plate's shape.
          broken (update-in (vec session) [3 :picks]
                            (fn [picks] (assoc picks 0 (get picks 1) 1 (get picks 0))))
          r (pc/calibrate broken nom)]
      (is (string? (:error r)))
      (is (nil? (:marks r)))
      (is (re-find #"registration" (:error r))
          "the message must point at the registration, not at the plate"))))

(deftest the-refined-poses-come-back
  (testing "calibrate returns the poses it re-solved, so the caller can adopt them"
    (let [nom (nominal-marks)
          poses (ring-poses 8 35.0 600.0)
          session (as-session (shoot poses (true-marks)) nom)
          r (pc/calibrate session nom)]
      (is (= 8 (count (:poses r))))
      (doseq [[got want] (map vector (:poses r) poses)]
        (is (< (la/v-norm (la/v-sub (cam/camera-center got) (cam/camera-center want))) 1.0)
            "a re-solved camera should sit within a millimetre of where it stood")))))

(deftest which-mode-survives-click-noise
  (testing "with realistic click noise, does a real warp still confirm — and in which mode?

   The reason this test exists is that I picked a default from an argument and
   the argument was wrong. `:out-of-plane` has a good physical story (a printer
   places ink to a tenth of a percent, 0.13mm on a 133mm radius; what moves by
   millimetres is the surface warping, which is perpendicular) — but a story is
   not evidence, and on the real session that mode rescued nothing. So the choice
   is made here instead, on a plate that IS warped, seen through 1.5px of hand
   noise, and judged by the only test that counts: does a held-out photograph
   agree?"
    (let [nom (nominal-marks)
          truth (true-marks)
          rng (synth/rng 31)
          session (as-session (noisy (shoot (ring-poses 8 35.0 600.0) truth) 1.5 rng) nom)
          free (pc/cross-validate session nom {:mode :free})
          oop (pc/cross-validate session nom {:mode :out-of-plane})]
      (println "\n=== Piatto imbarcato + 1.5px di rumore: quale modo regge? ===")
      (doseq [[label cv] [[":free        " free] [":out-of-plane" oop]]]
        (println (str "  " label " → tenute fuori " (.toFixed (:nominal-px cv) 3)
                      " → " (.toFixed (:measured-px cv) 3) " px · "
                      (:better cv) " meglio / " (:worse cv) " peggio · " (:verdict cv))))
      (is (= :confirmed (:verdict oop))
          "the constrained fit must survive noise on a genuinely warped plate")
      (is (<= (:measured-px oop) (:measured-px free))
          (str "and be at least as good held out as the free one: "
               (:measured-px oop) " vs " (:measured-px free))))))

(deftest a-real-warp-survives-being-held-out
  (testing "a plate that is genuinely warped helps photographs that did not measure it"
    (let [nom (nominal-marks)
          session (as-session (shoot (ring-poses 8 35.0 600.0) (true-marks)) nom)
          cv (pc/cross-validate session nom)]
      (is (= :confirmed (:verdict cv)))
      (is (< (:measured-px cv) (* 0.5 (:nominal-px cv)))
          (str "held out: " (:nominal-px cv) " → " (:measured-px cv) " px"))
      (is (= 8 (:better cv)) "every held-out view must improve"))))

(deftest a-bad-pick-does-not-become-a-warped-plate
  (testing "one mark clicked wrong in some photographs must NOT be adopted as plate shape

   This is the trap the whole cross-validation exists for, and it is not
   hypothetical — it is what the first real session did (2026-08-14, twelve views
   on a ⌀300). The fitted residual fell convincingly, 2.18 to 1.78px, and
   reported a 1.97mm warp at one mark. Held out, that plate made eight of twelve
   photographs WORSE, and the whole effect traced to a single mark whose pick was
   wrong in four frames: dropping it put those four at 0.3-1.5px on the untouched
   model plate.

   Reproduced here: a PERFECTLY FLAT plate, with one mark mis-clicked by 8px in
   half the views. `calibrate` will happily find a millimetre of 'warp' in it,
   because bending the plate is the only freedom it has to explain the clicks.
   The hold-out is what refuses."
    (let [nom (nominal-marks)
          clean (as-session (shoot (ring-poses 8 35.0 600.0) nom) nom)
          ;; mark 3 clicked 8px off in four of the eight views — under any
          ;; gross-outlier floor, and consistent enough to look like geometry
          bad (vec (map-indexed
                    (fn [i v]
                      (if (even? i)
                        (update-in v [:picks 3] (fn [[u vv]] [(+ u 8.0) (- vv 5.0)]))
                        v))
                    clean))
          r (pc/calibrate bad nom)
          cv (pc/cross-validate bad nom)]
      ;; the fit is fooled — it must be, or the test proves nothing
      (is (> (:worst-mm r) 0.3)
          (str "the fit should invent a warp of " (:worst-mm r) "mm from a flat plate"))
      (is (< (:rms-after r) (:rms-before r))
          "and its own residual should fall, which is exactly why it convinces")
      ;; and the hold-out is not
      (is (not= :confirmed (:verdict cv))
          (str "held out: " (:nominal-px cv) " → " (:measured-px cv) " px, "
               (:better cv) " better / " (:worse cv) " worse — this must not be adopted")))))

(deftest two-views-are-refused
  (testing "with two views the marks and the poses agree on anything"
    (let [nom (nominal-marks)
          session (as-session (shoot (ring-poses 2 35.0 600.0) (true-marks)) nom)
          r (pc/calibrate session nom)]
      (is (string? (:error r)))
      (is (nil? (:marks r))))))

(deftest an-unseen-mark-is-refused
  (testing "a mark only one view ever saw has no position, and the run says so"
    (let [nom (nominal-marks)
          session (-> (as-session (shoot (ring-poses 8 35.0 600.0) (true-marks)) nom)
                      (->> (map-indexed (fn [i v]
                                          (if (pos? i) (update v :picks dissoc 3) v)))
                           vec))
          r (pc/calibrate session nom)]
      (is (string? (:error r))))))

(deftest a-shallow-ring-still-measures-the-warp
  (testing "out-of-plane error needs grazing views to be visible at all

   A camera looking straight down on the plate cannot see that a mark stands
   proud of it — the displacement is along the line of sight. This is why the
   session's own advice is to shoot from a low angle, and the test pins the
   consequence: at 20° the warp comes back, and it comes back better than at 60°."
    (let [nom (nominal-marks)
          truth (true-marks)
          run (fn [elev]
                (rms-vs (:marks (pc/calibrate (as-session (shoot (ring-poses 8 elev 600.0) truth) nom)
                                              nom))
                        truth))]
      (is (< (run 20.0) 0.1))
      (is (<= (run 20.0) (run 60.0))))))
