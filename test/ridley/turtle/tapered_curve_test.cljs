(ns ridley.turtle.tapered-curve-test
  "`tapered :curve` — a non-linear taper.

   Vincenzo kept writing the same hand-rolled shape-fns: a sine taper
   `(scale-shape s (+ 1 (sin (* t PI 0.5))))` and a there-and-back waist
   `(scale-shape s (+ 1 (* -0.65 (sin (* nt PI)))))`. `:curve` makes them one
   option of `tapered`: the scale is from + curve(t)·(to − from).

   These tests pin: no :curve is bit-for-bit the old linear taper (existing
   scripts move not one vertex); each named curve at its landmarks; both of
   Vincenzo's hand-written fns reproduced exactly by a named curve; a user
   function, including one that leaves [0,1]; the partial form; a clear error
   for an unknown curve; and a user fn written in interpreted SCI code."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.sci-harness :as h]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.shape-fn :as sfn]))

(def ^:private circle (shape/circle-shape 20 24))
(defn- rad [s] (apply max (map (fn [[x y]] (Math/sqrt (+ (* x x) (* y y)))) (:points s))))
(defn- approx [a b] (< (Math/abs (- a b)) 1e-9))
(def ^:private ts [0 0.1 0.25 0.5 0.7 0.9 1])

(deftest no-curve-is-the-old-linear-taper
  (testing "omitted, :linear, and the old formula agree exactly"
    (let [plain (sfn/tapered circle :from 0.5 :to 2)
          lin   (sfn/tapered circle :from 0.5 :to 2 :curve :linear)]
      (doseq [t ts]
        (is (= (:points (plain t)) (:points (lin t))))
        (is (approx (rad (plain t)) (* 20 (+ 0.5 (* t 1.5)))))))))

(deftest named-curves-at-their-landmarks
  (let [r (fn [curve t] (/ (rad ((sfn/tapered circle :from 1 :to 2 :curve curve) t)) 20))]
    (testing "every named curve starts at :from"
      (doseq [c [:linear :ease-in :ease-out :ease-in-out :bump]]
        (is (approx (r c 0) 1) (str c))))
    (testing "the monotone ones end at :to"
      (doseq [c [:linear :ease-in :ease-out :ease-in-out]]
        (is (approx (r c 1) 2) (str c))))
    (testing ":bump goes there and back — :to at mid-path, :from at both ends"
      (is (approx (r :bump 0.5) 2))
      (is (approx (r :bump 1) 1))
      (is (approx (r :bump 0.25) (r :bump 0.75)) "symmetric"))
    (testing "ease-in lags the line early, ease-out leads it, ease-in-out is centred"
      (is (< (r :ease-in 0.25) (r :linear 0.25) (r :ease-out 0.25)))
      (is (approx (r :ease-in-out 0.5) 1.5)))))

(deftest vincenzos-hand-written-fns-are-named-curves
  (testing "sintaper: (scale-shape s (+ 1 (sin (* t PI 0.5)))) == :from 1 :to 2 :curve :ease-out"
    (let [by-hand (sfn/shape-fn circle (fn [s t] (shape/scale-shape s (+ 1 (Math/sin (* t Math/PI 0.5))))))
          named   (sfn/tapered circle :from 1 :to 2 :curve :ease-out)]
      (doseq [t ts] (is (approx (rad (by-hand t)) (rad (named t)))))))
  (testing "singlepulse (with its mirrored nt) == :to 0.35 :curve :bump"
    (let [by-hand (sfn/shape-fn circle
                                (fn [s t]
                                  (let [nt (if (>= t 0.5) (- 1 t) t)]
                                    (shape/scale-shape s (+ 1 (* -0.65 (Math/sin (* nt Math/PI))))))))
          named   (sfn/tapered circle :to 0.35 :curve :bump)]
      (doseq [t ts] (is (approx (rad (by-hand t)) (rad (named t))))))))

(deftest a-function-curve
  (testing "any (fn [t] …) — fraction of the way from :from to :to"
    (let [sf (sfn/tapered circle :to 0.5 :curve (fn [t] (min 1 (* 3 t))))]
      (is (approx (rad (sf 0)) 20))
      (is (approx (rad (sf (/ 1 3))) 10) "reaches :to after a third")
      (is (approx (rad (sf 0.8)) 10) "and stays there")))
  (testing "its value may leave [0,1] — overshoot is not clamped"
    (let [sf (sfn/tapered circle :from 1 :to 2 :curve (fn [_] 1.5))]
      (is (approx (rad (sf 0.3)) 50)))))

(deftest partial-form-carries-the-curve
  (let [xf (sfn/tapered :to 0.35 :curve :bump)]
    (is (false? (sfn/shape-fn? xf)))
    (is (approx (rad (xf circle 0.5)) 7))
    (is (approx (rad (xf circle 1)) 20))))

(deftest unknown-curve-says-what-is-allowed
  (let [msg (try (sfn/tapered circle :to 0.5 :curve :wobble) nil
                 (catch :default e (.-message e)))]
    (is (some? msg))
    (is (re-find #":wobble" msg))
    (is (re-find #":bump" msg))))

(deftest a-curve-written-in-sci-code
  (let [{:keys [result error]}
        (h/eval-dsl "(loft (tapered (circle 20) :to 1.6 :curve (fn [t] (sin (* PI (sqrt t))))) (f 30))")]
    (is (nil? error))
    (is (seq (:vertices result)) "the loft built a mesh")))
