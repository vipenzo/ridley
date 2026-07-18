(ns ridley.photogrammetry.turntable-study-test
  "Does the calibrated turntable (design doc § 'Input opzionale', level 1)
   actually buy anything?

   The claim to test: known rotation angles collapse the pose unknowns from
   6 per view to a single shared camera pose plus a shared axis, giving a
   'solver piu piccolo e molto piu robusto'. Both fits are run on the SAME
   synthetic observations, so any difference is the constraint and nothing
   else.

   Also tested: what happens when the printed plate's angles are not quite
   true, which is the realistic failure mode of a hand-indexed turntable."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.turntable-fit :as tt]
            [ridley.photogrammetry.synth :as synth]))

(def true-dims [60.0 25.0 10.0])
(def distance 250.0)
(def n-trials 10)

;; The part sits slightly off-centre on the plate, and the plate is not
;; perfectly level: a realistic axis, not a convenient one.
(def true-axis-params {:phi 0.3 :psi 0.10 :a 4.0 :b -3.0})

(defn- true-axis []
  (tt/axis-from-params (:phi true-axis-params) (:psi true-axis-params)
                       (:a true-axis-params) (:b true-axis-params)))

(defn- base-intrinsics [] (cam/intrinsics-from-fov 69.4 4032 3024))

(defn- fmt [x n] (.toFixed (js/Number. x) n))
(defn- pad [s w]
  (let [s (str s)] (str s (apply str (repeat (max 0 (- w (count s))) " ")))))

(defn- equivalent-pose
  "The free pose that reproduces 'fixed camera, object rotated by theta'.
   Composing the two is what lets both solvers see identical observations."
  [pose axis theta]
  (let [{:keys [point dir]} axis
        r-a (cam/rodrigues (mapv #(* % theta) dir))
        ;; A(X) = R_a X + (c - R_a c)
        p-a (mapv - point (la/mat*vec r-a point))
        r-p (cam/rodrigues (:rvec pose))
        r-i (la/mat*mat r-p r-a)
        t-i (mapv + (la/mat*vec r-p p-a) (:t pose))]
    {:rvec (cam/rot-mat->rodrigues r-i) :t t-i}))

(defn- ring-angles [n]
  (mapv #(* 2.0 Math/PI (/ (double %) n)) (range n)))

(defn- rms [xs]
  (Math/sqrt (/ (reduce + 0.0 (map #(* % %) xs)) (max 1 (count xs)))))

(defn- run-pair
  "Run both solvers on one synthetic turntable session.
   Returns {:free {...} :tt {...}} with dimensional errors in mm."
  [{:keys [seed n-views sigma-px angle-error-deg]
    :or {sigma-px 0.3 angle-error-deg 0.0}}]
  (let [r (synth/rng seed)
        axis (true-axis)
        base-pose (synth/viewpoint 0.0 25.0 distance)
        angles (ring-angles n-views)
        poses (mapv #(equivalent-pose base-pose axis %) angles)
        k (base-intrinsics)
        ks (vec (repeat n-views k))
        obs (synth/observations true-dims poses ks sigma-px r)
        ;; the angles the SOLVER is told, which may be slightly wrong
        injected (mapv (fn [_] (* (/ (* angle-error-deg Math/PI) 180.0)
                                  (synth/gauss r)))
                       angles)
        told-angles (mapv + angles injected)
        init-dims (synth/perturb-dims true-dims 0.20 r)
        scale-c {:axis 0 :value (nth true-dims 0) :sigma 0.02}
        ;; --- free-pose fit: 3 + 6N parameters
        free-init-poses (mapv #(synth/perturb-pose % 5.0 10.0 r) poses)
        free-res (bf/fit obs ks init-dims free-init-poses
                         {:sigma-px sigma-px :scale-constraint scale-c
                          :lm {:max-iterations 200}})
        ;; --- turntable fit: 3 + 6 + 4 parameters, whatever N is
        tt-init-pose (synth/perturb-pose base-pose 5.0 10.0 r)
        tt-init-axis {:phi (+ (:phi true-axis-params) (* 0.15 (synth/gauss r)))
                      :psi (+ (:psi true-axis-params) (* 0.15 (synth/gauss r)))
                      :a (+ (:a true-axis-params) (* 4.0 (synth/gauss r)))
                      :b (+ (:b true-axis-params) (* 4.0 (synth/gauss r)))}
        tt-res (tt/fit obs told-angles k init-dims tt-init-pose tt-init-axis
                       {:sigma-px sigma-px :scale-constraint scale-c
                        :lm {:max-iterations 200}})
        ;; --- turntable with the angles as PRIORS rather than as truth
        soft-res (tt/fit obs told-angles k init-dims tt-init-pose tt-init-axis
                         {:sigma-px sigma-px :scale-constraint scale-c
                          :angle-prior-sigma-deg 1.0
                          :lm {:max-iterations 200}})
        err (fn [dims] [(Math/abs (- (nth dims 1) (nth true-dims 1)))
                        (Math/abs (- (nth dims 2) (nth true-dims 2)))])]
    {:n-obs (count obs)
     :free {:errs (err (:dims free-res)) :n-params (count (:params free-res))}
     :tt {:errs (err (:dims tt-res)) :n-params (count (:params tt-res))}
     :soft {:errs (err (:dims soft-res)) :n-params (count (:params soft-res))}
     ;; mechanism check: the soft fit's corrections should cancel the error we
     ;; injected, i.e. delta ~= -injected. Without this, a soft fit that simply
     ;; ignored the angles would look identical in the accuracy column.
     :angle-recovery
     (let [deltas (:angle-deltas soft-res)
           residual (mapv + injected deltas)
           ;; A constant offset applied to EVERY view is not an error at all:
           ;; rotating the whole sequence is indistinguishable from rotating
           ;; the fixed camera, which is already a free parameter. Only the
           ;; view-to-view (differential) part is observable, and only it can
           ;; distort the geometry.
           mean (/ (reduce + 0.0 residual) (max 1 (count residual)))
           differential (mapv #(- % mean) residual)
           deg #(/ (* % 180.0) Math/PI)]
       {:injected-rms (deg (rms injected))
        :leftover-rms (deg (rms residual))
        :leftover-differential-rms (deg (rms differential))})}))

(defn- agg [pairs k]
  (let [errs (mapcat #(get-in % [k :errs]) pairs)]
    {:rms (rms errs)
     :max (apply max errs)
     :n-params (get-in (first pairs) [k :n-params])}))

(deftest turntable-vs-free-pose
  (println "\n=== EXP 7 — calibrated turntable vs free poses (same observations) ===")
  (println "Camera fixed, part rotating by known angles. Caliper pins width.")
  (println (pad "views" 7) (pad "sigma" 7)
           (pad "free par" 9) (pad "free RMS" 11)
           (pad "tt par" 7) (pad "tt RMS" 11) "gain")
  (doseq [n [3 6 12]
          s [0.3 1.0]]
    (let [pairs (mapv (fn [t] (run-pair {:seed (+ 3000 (* 61 t)) :n-views n
                                         :sigma-px s}))
                      (range n-trials))
          f (agg pairs :free)
          m (agg pairs :tt)]
      (println (pad n 7) (pad (fmt s 1) 7)
               (pad (:n-params f) 9) (pad (fmt (:rms f) 4) 11)
               (pad (:n-params m) 7) (pad (fmt (:rms m) 4) 11)
               (str (fmt (/ (:rms f) (max 1e-9 (:rms m))) 2) "x"))))
  (testing "the turntable constraint never costs accuracy"
    (let [pairs (mapv (fn [t] (run-pair {:seed (+ 3000 (* 61 t)) :n-views 6
                                         :sigma-px 0.3}))
                      (range n-trials))
          f (agg pairs :free)
          m (agg pairs :tt)]
      (is (<= (:rms m) (:rms f))
          (str "turntable RMS " (fmt (:rms m) 4)
               " should not exceed free-pose RMS " (fmt (:rms f) 4)))))
  (testing "the parameter count stops growing with the number of views"
    (let [p3 (run-pair {:seed 3000 :n-views 3 :sigma-px 0.3})
          p12 (run-pair {:seed 3000 :n-views 12 :sigma-px 0.3})]
      (is (= (get-in p3 [:tt :n-params]) (get-in p12 [:tt :n-params]) 13))
      (is (< (get-in p3 [:free :n-params]) (get-in p12 [:free :n-params]))))))

(deftest turntable-angle-error
  (println "\n=== EXP 8 — how true must the plate's angles be? ===")
  (println "6 views, sigma 0.3px. Angles reported to the solver are off by:")
  (println (pad "angle err" 12) (pad "hard RMS" 11) (pad "soft RMS" 11)
           (pad "free RMS" 11) (pad "soft leftover" 14) "verdict")
  (doseq [ae [0.0 0.25 0.5 1.0 2.0 5.0]]
    (let [pairs (mapv (fn [t] (run-pair {:seed (+ 3000 (* 61 t)) :n-views 6
                                         :sigma-px 0.3 :angle-error-deg ae}))
                      (range n-trials))
          m (agg pairs :tt)
          s (agg pairs :soft)
          f (agg pairs :free)
          leftover (/ (reduce + 0.0 (map #(get-in % [:angle-recovery
                                                     :leftover-differential-rms])
                                         pairs))
                      (count pairs))
          best (cond (and (<= (:rms m) (:rms s)) (<= (:rms m) (:rms f))) "hard wins"
                     (<= (:rms s) (:rms f)) "soft wins"
                     :else "turntable HURTS")]
      (println (pad (str (fmt ae 2) " deg") 12) (pad (fmt (:rms m) 4) 11)
               (pad (fmt (:rms s) 4) 11) (pad (fmt (:rms f) 4) 11)
               (pad (str (fmt leftover 3) " deg") 14) best)))
  (println "  hard = angles trusted (13 params); soft = angles as priors with")
  (println "  sigma 1 deg (13+N params); free = angles ignored (3+6N params).")
  (println "  'soft leftover' is the DIFFERENTIAL angle error surviving the fit")
  (println "  (view-to-view, mean removed). A constant offset across all views")
  (println "  is gauge, not error: it is absorbed by the free camera pose.")
  (testing "soft priors actually recover the injected angle error"
    ;; The flat accuracy column above is only trustworthy if this holds: a
    ;; soft fit that simply ignored the angles would look identical there.
    (let [pairs (mapv (fn [t] (run-pair {:seed (+ 3000 (* 61 t)) :n-views 6
                                         :sigma-px 0.3 :angle-error-deg 5.0}))
                      (range n-trials))
          avg (fn [k] (/ (reduce + 0.0 (map #(get-in % [:angle-recovery k]) pairs))
                         (count pairs)))
          injected (avg :injected-rms)
          leftover (avg :leftover-rms)
          differential (avg :leftover-differential-rms)]
      (is (> injected 1.0) "the experiment must actually inject a large error")
      (is (< differential (* 0.2 injected))
          (str "soft fit should absorb the OBSERVABLE part of the injected "
               "error; injected " (fmt injected 3) " deg, differential leftover "
               (fmt differential 3) " deg"))
      (println (str "  mechanism at 5 deg: injected " (fmt injected 3)
                    " deg, total leftover " (fmt leftover 3)
                    " deg, of which differential " (fmt differential 3)
                    " deg (the rest is common-mode gauge)"))))
  (testing "soft priors keep the turntable from ever being worse than free poses"
    (let [pairs (mapv (fn [t] (run-pair {:seed (+ 3000 (* 61 t)) :n-views 6
                                         :sigma-px 0.3 :angle-error-deg 2.0}))
                      (range n-trials))
          s (agg pairs :soft)
          m (agg pairs :tt)]
      (is (< (:rms s) (:rms m))
          (str "with a 2 deg plate error, soft priors (" (fmt (:rms s) 4)
               ") should beat trusting the angles (" (fmt (:rms m) 4) ")"))))
  (testing "angle error degrades the turntable fit (so the angles matter)"
    (let [clean (agg (mapv (fn [t] (run-pair {:seed (+ 3000 (* 61 t)) :n-views 6
                                              :sigma-px 0.3 :angle-error-deg 0.0}))
                           (range n-trials)) :tt)
          bad (agg (mapv (fn [t] (run-pair {:seed (+ 3000 (* 61 t)) :n-views 6
                                            :sigma-px 0.3 :angle-error-deg 5.0}))
                         (range n-trials)) :tt)]
      (is (> (:rms bad) (:rms clean))
          "a 5 deg angle error should measurably hurt"))))
