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

;; ── la famiglia concentrica (leva 1 dello zero-click, 2026-08-29) ────────────

(deftest concentric-family-finds-the-full-rings
  ;; Three near-concentric rings (centres a few px apart, as perspective really
  ;; leaves them) plus scattered junk: the concentric family must return the
  ;; two full rings CLEAN. The third, sparse ring (eight discs, centre 10px off
  ;; the seeds) is the measured LIMIT of today's pinned search — printed, not
  ;; asserted, because it is the success criterion of the next iteration, and
  ;; on the real bench the sparse rings do surface, only inside contaminated
  ;; supersets (see the 2026-08-29 audit in HANDOVER-cage-zero-click.md).
  (println "\n=== ellisse: la famiglia concentrica e l'anello povero ===")
  (let [r (synth/rng 7)
        ;; ring A: 12 discs, big; ring B: 12, middle; ring C: EIGHT, small —
        ;; centres offset from each other like projected circle centres are
        ring (fn [cx cy a b rot n phase]
               (mapv (fn [i] (ellipse-pt cx cy a b rot (+ phase (* i (/ (* 2 Math/PI) n)))))
                     (range n)))
        A (ring 960.0 720.0 620.0 410.0 0.3 12 0.1)
        B (ring 952.0 728.0 430.0 300.0 0.5 12 0.4)
        C (ring 968.0 714.0 250.0 160.0 0.2 8 0.2)
        junk [[960.0 720.0] [980.0 700.0] [500.0 300.0] [1500.0 1100.0]
              [700.0 1200.0] [1300.0 260.0] [420.0 900.0] [1520.0 620.0]]
        jitter (fn [pts] (mapv (fn [[x y]] [(+ x (* 1.5 (synth/gauss r)))
                                            (+ y (* 1.5 (synth/gauss r)))]) pts))
        pts (vec (concat (jitter A) (jitter B) (jitter C) junk))
        idx-of (fn [lo n] (set (range lo (+ lo n))))
        [ia ib ic] [(idx-of 0 12) (idx-of 12 12) (idx-of 24 8)]
        opts {:iters 2000 :thr 0.04 :min-inliers 8 :top-k 6}
        hyps (ellipse/fit-concentric-ranked pts opts)
        covers (fn [ring-set]
                 (first (filter (fn [h] (>= (count (filter ring-set h))
                                            (dec (count ring-set))))
                                hyps)))
        purity (fn [h ring-set] (when h (/ (count (filter ring-set h)) (count h))))]
    (println (str "  " (count pts) " punti (12+12+8 su tre anelli + " (count junk)
                  " spazzatura) → " (count hyps) " ipotesi, taglie "
                  (pr-str (mapv count hyps))))
    (doseq [[nome ring-set] [["A (12)" ia] ["B (12)" ib]]]
      (let [h (covers ring-set)]
        (println (str "  anello " nome ": "
                      (if h (str "trovato, " (count h) " inlier, purezza "
                                 (.toFixed (* 100.0 (purity h ring-set)) 0) "%")
                          "NON trovato")))
        (is (some? h) (str "l'anello " nome " emerge fra le ipotesi"))
        (when h
          (is (>= (purity h ring-set) 0.8)
              (str "e l'ipotesi è quasi tutta anello vero (" nome ")")))))
    ;; the measured limit, on the record: today the 8-disc ring at a 10px-off
    ;; centre does not emerge on this scene — when it starts to, celebrate and
    ;; tighten this into an assertion
    (let [h (covers ic)]
      (println (str "  anello C (8, povero): "
                    (if h (str "trovato (" (count h) " inlier) — IL LIMITE È CADUTO,"
                               " promuovi questa stampa ad asserzione")
                        "NON trovato (limite misurato di oggi)"))))))

(deftest sisters-about-is-exhaustive-where-sampling-is-lucky
  ;; The pinned search from the TRUE centre: eight ring points drowned in
  ;; twenty junk points. Exhaustive over triples, so finding the ring is not
  ;; luck — the same scene where independent 5-point sampling has (8/28)⁵ ≈
  ;; 0.2% per-draw odds of a clean sample. From a centre ~6px off the ring is
  ;; NOT yet recovered on this scene (measured — the pin's tolerance is the
  ;; frontier), so the off-centre outcome is printed, not asserted.
  (println "\n=== ellisse: le sorelle dal centro, esaustive ===")
  (let [r (synth/rng 11)
        on (mapv (fn [i] (ellipse-pt 800.0 600.0 300.0 190.0 0.7
                                     (* i (/ (* 2 Math/PI) 8)))) (range 8))
        junk (mapv (fn [_] [(+ 200.0 (* 1200.0 (r)))
                            (+ 100.0 (* 1000.0 (r)))]) (range 20))
        pts (vec (concat on junk))
        on-set (set (range 8))
        found? (fn [hyps] (some (fn [h] (>= (count (filter on-set h)) 8)) hyps))
        exact (ellipse/sisters-about pts [800.0 600.0] {:min-inliers 8 :top-k 4})
        off (ellipse/sisters-about pts [805.0 596.0] {:min-inliers 8 :top-k 4})]
    (println (str "  dal centro vero: " (if (found? exact) "TROVATO" "perso")
                  " · da 6px fuori: " (if (found? off)
                                        "TROVATO — il limite è caduto, promuovi ad asserzione"
                                        "perso (limite misurato di oggi)")))
    (is (found? exact)
        "gli otto punti dell'anello emergono dal centro vero, deterministicamente")))
