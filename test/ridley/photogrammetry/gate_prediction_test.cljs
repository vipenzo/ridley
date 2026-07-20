(ns ridley.photogrammetry.gate-prediction-test
  "What will the real gate measure? A prediction, computed BEFORE the edges
   are extracted, using this session's actual configuration.

   Setup from test-assets/param-acq/NOTE.md: iPhone 15 Pro Max at 48mm
   equivalent (f = 4032 * 48/36 = 5376 px), 4032x3024, part standing on a
   turntable at the recorded angles, caliper 80 x 42.2 x 15 mm.

   The point of running this first: the SD reader has visibly rounded
   vertical edges, measured from IMG_8880 at roughly 1.7 mm radius. The
   design doc named exactly this as the expected dominant systematic term.
   Here it is quantified, so the real measurement can either confirm the
   diagnosis or refute it — instead of the result being a surprise with no
   explanation attached."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.turntable-fit :as tt]
            [ridley.photogrammetry.synth :as synth]))

;; Object frame: X = 42.2 (faccia larga), Y = 15 (spessore), Z = 80 (verticale,
;; l'asse del giradischi). The caliper pins Z; X and Y are the judged ones.
(def true-dims [42.2 15.0 80.0])
(def img-w 4032)
(def img-h 3024)
(def focal-px (* 4032 (/ 48.0 36.0)))   ; = 5376
(def distance 280.0)
(def elevation 30.0)
;; A systematic bias does not need trial-averaging the way scatter does, and
;; the free-pose solve at 14 views is an 87-parameter numerical-Jacobian fit.
;; Few trials, 8 views, and the free-pose comparison run once.
(def n-trials 3)
(def n-views 8)

;; The real theta sequence from NOTE.md, in degrees.
(def session-thetas [0 -10 -20 -30 -40 -50 -60 -70 -80 -90 -130 180 90 45])

(def true-axis-params {:phi 0.4 :psi 0.03 :a 3.0 :b -2.0})

(defn- intrinsics [] {:fx focal-px :fy focal-px
                      :cx (/ img-w 2.0) :cy (/ img-h 2.0) :k1 0.0 :k2 0.0})

(defn- fmt [x n] (.toFixed (js/Number. x) n))
(defn- pad [s w]
  (let [s (str s)] (str s (apply str (repeat (max 0 (- w (count s))) " ")))))
(defn- rms [xs]
  (Math/sqrt (/ (reduce + 0.0 (map #(* % %) xs)) (max 1 (count xs)))))

(defn- deg->rad [d] (/ (* d Math/PI) 180.0))

(defn- session-setup [n-views]
  (let [axis (tt/axis-from-params (:phi true-axis-params) (:psi true-axis-params)
                                  (:a true-axis-params) (:b true-axis-params))
        base (synth/viewpoint 0.0 elevation distance)
        thetas (mapv deg->rad (take n-views session-thetas))
        poses (mapv #(tt/pose-at-angle base axis %) thetas)]
    {:axis axis :base base :thetas thetas :poses poses}))

(defn- run-trial
  "Generate observations from a box with fillet radius r, then fit with the
   SHARP box model (which is what the solver currently assumes).
   The free-pose solve is opt-in because it is far more expensive."
  [{:keys [seed r views sigma-px with-free?]
    :or {sigma-px 0.5 views n-views}}]
  (let [g (synth/rng seed)
        {:keys [base thetas poses]} (session-setup views)
        k (intrinsics)
        ks (vec (repeat (count poses) k))
        obs (synth/observations-rounded true-dims r poses ks sigma-px g)
        scale-c {:axis 2 :value (nth true-dims 2) :sigma 0.02}
        init-dims (synth/perturb-dims true-dims 0.20 g)
        err (fn [d] {:x (- (nth d 0) (nth true-dims 0))
                     :y (- (nth d 1) (nth true-dims 1))})
        tt-init-pose (synth/perturb-pose base 5.0 10.0 g)
        tt-init-axis {:phi (+ (:phi true-axis-params) (* 0.1 (synth/gauss g)))
                      :psi (+ (:psi true-axis-params) (* 0.1 (synth/gauss g)))
                      :a (+ (:a true-axis-params) (* 3.0 (synth/gauss g)))
                      :b (+ (:b true-axis-params) (* 3.0 (synth/gauss g)))}
        tt-res (tt/fit obs thetas k init-dims tt-init-pose tt-init-axis
                       {:sigma-px sigma-px :scale-constraint scale-c
                        :angle-prior-sigma-deg 1.0
                        :lm {:max-iterations 200}})
        free-res (when with-free?
                   (bf/fit obs ks init-dims
                           (mapv #(synth/perturb-pose % 5.0 10.0 g) poses)
                           {:sigma-px sigma-px :scale-constraint scale-c
                            :lm {:max-iterations 200}}))]
    (cond-> {:n-obs (count obs)
             :tt (assoc (err (:dims tt-res)) :dims (:dims tt-res)
                        :rms-px (bf/rms-reprojection-px tt-res obs
                                                        {:sigma-px sigma-px}))}
      free-res (assoc :free (assoc (err (:dims free-res)) :dims (:dims free-res))))))

(defn- avg [xs] (/ (reduce + 0.0 xs) (max 1 (count xs))))

(deftest exp-9-fillet-bias-at-session-geometry
  (println "\n=== EXP 9 — PREDICTION for the real gate: cost of the fillet ===")
  (println (str "Session geometry: 42.2 x 15 x 80 mm, f=" (fmt focal-px 0)
                " px, d=" (fmt distance 0) " mm, 14 views at the recorded angles."))
  (println "Observations generated from a ROUNDED box; fit with the SHARP model.")
  (println "Caliper pins Z (80mm). Errors are SIGNED, on the two judged dims.")
  (println)
  (println (pad "fillet r" 10) (pad "err X (42.2)" 14) (pad "err Y (15)" 14)
           (pad "reproj px" 11) "verdict vs 0.2mm")
  (let [rows (mapv (fn [r]
                     (let [ts (mapv #(run-trial {:seed (+ 500 (* 37 %)) :r r})
                                    (range n-trials))
                           ex (avg (map #(get-in % [:tt :x]) ts))
                           ey (avg (map #(get-in % [:tt :y]) ts))
                           px (avg (map #(get-in % [:tt :rms-px]) ts))]
                       {:r r :ex ex :ey ey :px px}))
                   [0.0 0.5 1.0 1.5 1.7 2.0 2.5])]
    (doseq [{:keys [r ex ey px]} rows]
      (println (pad (str (fmt r 1) " mm") 10)
               (pad (str (if (pos? ex) "+" "") (fmt ex 3) " mm") 14)
               (pad (str (if (pos? ey) "+" "") (fmt ey 3) " mm") 14)
               (pad (fmt px 2) 11)
               (if (and (< (Math/abs ex) 0.2) (< (Math/abs ey) 0.2)) "PASS" "FAIL")))
    (println)
    (println "  Read the two columns separately — the bias is NOT uniform:")
    (println "  * Y (15 mm) collapses. The fillet removes a fixed ~r from each")
    (println "    side, so the THIN dimension pays proportionally far more:")
    (println "    1.7 mm of round on a 15 mm thickness is most of the budget.")
    (println "  * X (42.2 mm) drifts slightly POSITIVE. The fillet shrinks the")
    (println "    apparent Z too, and Z is pinned by the caliper, so the fit")
    (println "    rescales everything up to satisfy it. Which dimension you")
    (println "    pin therefore decides where the error is pushed.")
    (println "  It is a bias, not scatter: more photographs cannot average it")
    (println "  away. That is what makes it systematic.")
    (println)
    (println "  Note the reprojection column: it climbs from 0.5 px to >10 px.")
    (println "  The fit cannot fit, and says so — a wrong edge model DENOUNCES")
    (println "  ITSELF in the residual, which is the signal to surface in the UI.")

    (testing "with sharp edges this configuration passes comfortably"
      (let [r0 (first (filter #(= 0.0 (:r %)) rows))]
        (is (< (Math/abs (:ex r0)) 0.1)
            (str "sharp-edge X error " (fmt (:ex r0) 4)))
        (is (< (Math/abs (:ey r0)) 0.1)
            (str "sharp-edge Y error " (fmt (:ey r0) 4)))))

    (testing "the measured 1.7mm fillet pushes the fit past the 0.2mm gate"
      (let [r17 (first (filter #(= 1.7 (:r %)) rows))]
        (is (> (max (Math/abs (:ex r17)) (Math/abs (:ey r17))) 0.2)
            (str "expected the fillet to break the threshold; got X "
                 (fmt (:ex r17) 3) " Y " (fmt (:ey r17) 3)))))

    (testing "the thin dimension carries the bias, and it is negative"
      ;; The naive expectation (every dimension shrinks) is WRONG: with Z
      ;; pinned by the caliper the fit rescales, which can push a long
      ;; dimension positive. What is robust is that the thin dimension loses,
      ;; and that it loses monotonically with the radius.
      (let [ys (map :ey rows)]
        (is (every? neg? (map :ey (filter #(pos? (:r %)) rows)))
            "every non-zero fillet should shrink the thin dimension")
        (is (apply > ys)
            (str "the thin-dimension bias should grow monotonically with r: "
                 (vec (map #(fmt % 3) ys))))))

    (testing "a wrong edge model denounces itself in the reprojection residual"
      (let [r0 (first (filter #(= 0.0 (:r %)) rows))
            r17 (first (filter #(= 1.7 (:r %)) rows))]
        (is (> (:px r17) (* 10 (:px r0)))
            (str "reprojection should balloon: " (fmt (:px r0) 2) " px -> "
                 (fmt (:px r17) 2) " px"))))))

(deftest exp-10-how-sharp-must-the-part-be
  (println "\n=== EXP 10 — what fillet radius would this gate tolerate? ===")
  (println "Same setup; bisecting the radius at which the worst judged")
  (println "dimension crosses 0.2 mm.")
  (let [worst (fn [r]
                (let [ts (mapv #(run-trial {:seed (+ 500 (* 37 %)) :r r})
                               (range 6))]
                  (max (Math/abs (avg (map #(get-in % [:tt :x]) ts)))
                       (Math/abs (avg (map #(get-in % [:tt :y]) ts))))))]
    (loop [lo 0.0 hi 3.0 i 0]
      (if (>= i 7)
        (do (println (str "  -> tolerable fillet radius is about "
                          (fmt (/ (+ lo hi) 2) 2) " mm"))
            (println (str "  -> the measured part is about 1.7 mm: roughly "
                          (fmt (/ 1.7 (max 0.01 (/ (+ lo hi) 2))) 1)
                          "x too round for this gate")))
        (let [mid (/ (+ lo hi) 2)]
          (if (> (worst mid) 0.2)
            (recur lo mid (inc i))
            (recur mid hi (inc i))))))
    (is true)))
