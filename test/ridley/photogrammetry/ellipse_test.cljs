(ns ridley.photogrammetry.ellipse-test
  "Robust ellipse fit (fetta C's crown selector): the RANSAC conic must pick the
   points ON an ellipse out of a set laced with off-ellipse outliers — that is how
   fit-crown separates the 12 crown discs (on the crown's imaged ellipse) from the
   zero-index (inside it) and the frame noise. Deterministic, so the same set always
   selects the same inliers."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.ellipse :as ellipse]
            [ridley.photogrammetry.synth :as synth]))

(defn- ellipse-pt
  "A point on the ellipse centred (cx,cy), semi-axes (a,b), rotated by `rot`, at
   parameter angle `t`."
  [cx cy a b rot t]
  (let [x (* a (Math/cos t)) y (* b (Math/sin t))
        c (Math/cos rot) s (Math/sin rot)]
    [(+ cx (- (* c x) (* s y))) (+ cy (+ (* s x) (* c y)))]))

(deftest selects-ellipse-inliers-among-outliers
  (println "\n=== ellisse: RANSAC seleziona i punti sull'ellisse tra gli outlier ===")
  (let [r (synth/rng 5)
        cx 2000.0 cy 1500.0 a 900.0 b 600.0 rot 0.4
        ;; 12 points on the ellipse (like a crown), + 6 off-ellipse outliers
        on (mapv (fn [i] (ellipse-pt cx cy a b rot (* i (/ (* 2 Math/PI) 12)))) (range 12))
        outliers [[2000.0 1500.0]           ; centre (the "zero-index")
                  [2050.0 1520.0] [1200.0 400.0] [3400.0 2600.0]
                  [2600.0 1490.0] [1500.0 2400.0]]
        ;; light per-point noise on the on-ellipse points (sub-disc)
        noisy-on (mapv (fn [[x y]] [(+ x (* 3.0 (synth/gauss r))) (+ y (* 3.0 (synth/gauss r)))]) on)
        pts (vec (concat noisy-on outliers))
        inliers (ellipse/fit-inliers pts {:seed 1})
        on-set (set (range 12))
        picked-on (count (filter on-set inliers))
        picked-out (count (remove on-set inliers))]
    (println (str "  " (count pts) " punti (12 su ellisse + 6 outlier) → inlier "
                  (count inliers) " (" picked-on " su ellisse, " picked-out " outlier)"))
    (is (>= picked-on 11) "recupera (quasi) tutti i 12 punti sull'ellisse")
    (is (<= picked-out 1) "quasi nessun outlier entra tra gli inlier")
    ;; inliers come back sorted by fit — the first ones are genuine ellipse points
    (is (on-set (first inliers)) "il migliore inlier è un vero punto d'ellisse")))

(deftest too-few-or-no-ellipse
  (testing "fewer than 5 points → no fit"
    (is (= [] (ellipse/fit-inliers [[0 0] [1 1] [2 2] [3 3]] {}))))
  (testing "scattered points with no ellipse structure → below min-inliers"
    ;; 6 points on a line have no proper ellipse through them with a tight band
    (let [pts [[0.0 0.0] [100.0 0.0] [200.0 0.0] [300.0 0.0] [400.0 0.0] [500.0 0.0]]]
      (is (empty? (ellipse/fit-inliers pts {:min-inliers 6 :thr 0.01}))))))
