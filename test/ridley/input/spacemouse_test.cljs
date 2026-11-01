(ns ridley.input.spacemouse-test
  "Pure SpaceMouse logic for edit-attach (dev-docs/brief-spacemouse-edit-attach.md,
   commit 1), written before the code. The fixtures are MEASURED: per-gesture
   six-axis medians and the first report over the start threshold, from the guided
   log of dev-docs/accertamento-spacemouse-risultati.md (point 1) — coupled axes
   included, since they are what a naive filter gets wrong."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.input.spacemouse :as sm]))

;; ============================================================
;; Fixtures from the real device (SpaceMouse Compact, guided log)
;; ============================================================

;; gesture → [family expected-cmd expected-sign median-input onset-input]
;; median = per-axis median of the non-zero reports while the gesture was held;
;; onset = the device state when the family's largest axis first reached 80.
(def gestures
  {:push-right  [:step  :rt +1 {:t [343 11 217]    :r [20 -206 30]}   {:t [85 -9 2]    :r [0 0 0]}]
   :push-left   [:step  :rt -1 {:t [-322 0 242]    :r [25 124 21]}    {:t [-84 0 17]   :r [0 0 0]}]
   :push-away   [:step  :f  +1 {:t [0 -305 117]    :r [-271 -41 20]}  {:t [0 -84 34]   :r [0 0 0]}]
   :pull-back   [:step  :f  -1 {:t [5 276 55]      :r [266 -7 28]}    {:t [0 90 0]     :r [0 0 0]}]
   :lift        [:step  :u  +1 {:t [-21 -46 -350]  :r [-17 31 105]}   {:t [0 -77 -86]  :r [0 0 0]}]
   :press       [:step  :u  -1 {:t [0 0 350]       :r [0 -2 0]}       {:t [2 0 80]     :r [0 0 0]}]
   :tilt-away   [:angle :tv -1 {:t [-28 -34 94]    :r [-350 -36 -62]} {:t [0 0 0]      :r [-83 0 0]}]
   :tilt-back   [:angle :tv +1 {:t [2 0 92]        :r [350 20 0]}     {:t [0 0 0]      :r [93 0 0]}]
   :tilt-right  [:angle :tr +1 {:t [-35 0 139]     :r [-7 -305 0]}    {:t [0 0 0]      :r [0 -87 0]}]
   :tilt-left   [:angle :tr -1 {:t [-70 -21 78]    :r [-59 350 0]}    {:t [0 0 0]      :r [0 98 0]}]
   :twist-cw    [:angle :th -1 {:t [4 8 190]       :r [8 -16 350]}    {:t [0 0 0]      :r [0 0 85]}]
   :twist-ccw   [:angle :th +1 {:t [19 0 272]      :r [0 0 -330]}     {:t [0 0 0]      :r [0 -34 -87]}]})

(def opts {:family :step :step 5 :angle-step 15 :free? false})

(defn- run
  "Feed `frames` ([input dt-seconds] pairs) through step-gesture from idle; return
   every intermediate state."
  [frames o]
  (reductions (fn [s [in dt]] (sm/step-gesture s in dt o)) sm/idle frames))

(defn- hold [input secs] (repeat (Math/round (* secs 60)) [input (/ 1 60)]))

(def rest-in {:t [0 0 0] :r [0 0 0] :buttons 0})

(defn- commits [states] (keep :commit states))

;; ============================================================
;; device->turtle + pick-axis on the measured gestures
;; ============================================================

(deftest measured-gestures-pick-the-right-axis-and-sign
  (doseq [[g [family cmd sign median onset]] gestures
          [label input] [[:median median] [:onset onset]]]
    (testing (str g " " label " in " family)
      (let [comps (sm/device->turtle input)
            axis (sm/pick-axis family comps)]
        (is (= cmd axis))
        (is (= sign (Math/sign (get comps axis))))))))

(deftest pull-back-in-step-is-f-negative
  (testing "pull-back couples rx +266 to ty +276 — in STEP only translations compete"
    (let [comps (sm/device->turtle (get-in gestures [:pull-back 3]))]
      (is (= :f (sm/pick-axis :step comps)))
      (is (neg? (:f comps))))))

(deftest tilt-back-in-angle-is-nose-up
  (let [comps (sm/device->turtle (get-in gestures [:tilt-back 3]))]
    (is (= :tv (sm/pick-axis :angle comps)))
    (is (pos? (:tv comps)))))

(deftest scale-family-picks-nothing
  (is (nil? (sm/pick-axis :scale (sm/device->turtle (get-in gestures [:push-away 3]))))))

(deftest button-noise-stays-below-start
  (testing "pressing a button moves the cap up to ±52 (point 1): no axis"
    (let [comps (sm/device->turtle {:t [0 -46 23] :r [52 -52 0]})]
      (is (nil? (sm/pick-axis :step comps)))
      (is (nil? (sm/pick-axis :angle comps))))))

;; ============================================================
;; response
;; ============================================================

(deftest response-dead-zone-sign-and-clamp
  (is (zero? (sm/response 0)))
  (is (zero? (sm/response 39)))
  (is (zero? (sm/response -39)))
  (is (= 1 (sm/response 350)))
  (is (= 1 (sm/response 500)) "clamped")
  (is (= -1 (sm/response -350)))
  (is (< 0 (sm/response 175) 0.5) "curve > 1: half deflection gives well under half speed"))

;; ============================================================
;; step-gesture
;; ============================================================

(deftest full-gesture-one-commit-rounded-to-step
  (let [push {:t [0 -305 117] :r [-271 -41 20] :buttons 0}
        states (run (concat (hold push 1.0) [[rest-in (/ 1 60)]]) opts)
        cs (commits states)
        expected (* (sm/response 305) (:max-steps-per-s sm/config) 5 1.0)]
    (is (= 1 (count cs)))
    (let [[cmd v] (first cs)]
      (is (= :f cmd))
      (is (zero? (mod v 5)) "rounded to the step")
      (is (<= (Math/abs (- v expected)) 2.5)))
    (is (= :idle (:phase (last states))))))

(deftest shift-rounds-to-a-tenth
  (let [push {:t [0 -305 117] :r [-271 -41 20] :buttons 0}
        [[_ v]] (commits (run (concat (hold push 1.0) [[rest-in (/ 1 60)]])
                              (assoc opts :free? true)))]
    (is (= v (/ (Math/round (* v 10)) 10)))
    (is (not (zero? (mod v 5))) "not snapped to the 5 mm grid")))

(deftest angle-gesture-uses-angle-step
  (let [twist {:t [4 8 190] :r [8 -16 350] :buttons 0}
        [[cmd v]] (commits (run (concat (hold twist 0.5) [[rest-in (/ 1 60)]])
                                (assoc opts :family :angle)))]
    (is (= :th cmd))
    (is (neg? v))
    (is (zero? (mod v 15)))))

(deftest under-half-a-step-commits-nothing
  (let [nudge {:t [0 -100 0] :r [0 0 0] :buttons 0}
        states (run (concat (hold nudge 0.1) [[rest-in (/ 1 60)]]) opts)]
    (is (some #(= :active (:phase %)) states) "the gesture did start")
    (is (empty? (commits states)))
    (is (:ended (last states)) "but it ended, so the preview can be reset")))

(deftest axis-stays-locked
  (let [start {:t [0 -150 0] :r [0 0 0] :buttons 0}
        veer {:t [340 -100 0] :r [0 0 0] :buttons 0}
        states (run (concat (hold start 0.5) (hold veer 0.5) [[rest-in (/ 1 60)]]) opts)]
    (is (every? #(= :f (:axis %)) (filter #(= :active (:phase %)) states)))
    (is (= :f (ffirst (commits states))))))

(deftest release-hysteresis
  (let [push {:t [0 -305 0] :r [0 0 0] :buttons 0}
        mid {:t [0 -60 0] :r [0 0 0] :buttons 0}      ; under start, over release
        low {:t [0 -20 0] :r [0 0 0] :buttons 0}]     ; under release
    (testing "between release and start thresholds the gesture keeps going"
      (let [s (last (run (concat (hold push 0.5) (hold mid 1.0)) opts))]
        (is (= :active (:phase s)))))
    (testing "under release for less than the hold time: still active"
      (let [s (last (run (concat (hold push 0.5) (hold low 0.05)) opts))]
        (is (= :active (:phase s)))))
    (testing "under release for long enough: one commit"
      (let [states (run (concat (hold push 0.5) (hold low 0.3)) opts)]
        (is (= 1 (count (commits states))))
        (is (= :idle (:phase (last states))))))
    (testing "all-zero ends it at once"
      (let [states (run (concat (hold push 0.5) [[rest-in (/ 1 60)]]) opts)]
        (is (:commit (last states)))))))

(deftest buttons-mask-axes-and-close-a-gesture
  (testing "with a button down a deflection starts nothing"
    (let [states (run (hold {:t [0 -305 0] :r [0 0 0] :buttons 2} 0.5) opts)]
      (is (every? #(= :idle (:phase %)) states))))
  (testing "a button pressed mid-gesture commits it"
    (let [push {:t [0 -305 0] :r [0 0 0] :buttons 0}
          states (run (concat (hold push 0.5) [[(assoc push :buttons 2) (/ 1 60)]]) opts)]
      (is (= 1 (count (commits states))))
      (is (= :idle (:phase (last states)))))))

(deftest button-noise-over-time-is-no-gesture
  (let [noise {:t [0 -46 23] :r [52 -52 0] :buttons 0}]
    (is (every? #(= :idle (:phase %)) (run (hold noise 2.0) opts)))))

(deftest blocked-starts-nothing
  (testing "while a re-eval is pending no new gesture starts (brief, integration 9)"
    (let [states (run (hold {:t [0 -305 0] :r [0 0 0] :buttons 0} 0.5)
                      (assoc opts :blocked? true))]
      (is (every? #(= :idle (:phase %)) states)))))

(deftest scale-family-never-starts
  (let [states (run (hold {:t [0 -305 0] :r [0 0 0] :buttons 0} 0.5)
                    (assoc opts :family :scale))]
    (is (every? #(= :idle (:phase %)) states))))

(deftest nil-input-is-rest
  (testing "no device state yet behaves like rest; it also ends an active gesture"
    (is (= :idle (:phase (sm/step-gesture sm/idle nil (/ 1 60) opts))))
    (let [states (run (concat (hold {:t [0 -305 0] :r [0 0 0] :buttons 0} 0.5)
                              [[nil (/ 1 60)]]) opts)]
      (is (= 1 (count (commits states)))))))

(deftest gesture-value-matches-the-commit
  (let [push {:t [0 -305 0] :r [0 0 0] :buttons 0}
        before (last (run (hold push 0.8) opts))
        after (sm/step-gesture before rest-in (/ 1 60) opts)]
    (is (= (sm/gesture-value before opts) (second (:commit after))))))

(deftest long-frame-is-clamped
  (testing "a stalled frame (tab hidden, heavy eval) doesn't jump the value"
    (let [push {:t [0 -350 0] :r [0 0 0] :buttons 0}
          s (sm/step-gesture (sm/step-gesture sm/idle push (/ 1 60) opts) push 5.0 opts)]
      (is (< (:acc s) (* 5 (:max-steps-per-s sm/config) 0.2))))))
