(ns ridley.photogrammetry.match-test
  "Does per-photo matching recover the correspondence from geometry alone?

   The decisive test is that the clicks are SHUFFLED within each group before
   being handed to the solver. Under the old scheme that destroyed the answer;
   here it must make no difference whatsoever, because order carries no
   information any more."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.match :as match]
            [ridley.photogrammetry.turntable-fit :as tt]
            [ridley.photogrammetry.synth :as synth]))

(def true-dims [60.0 20.0 40.0])
(def focal-px (* 4032 (/ 48.0 36.0)))
(defn- k* [] {:fx focal-px :fy focal-px :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})
(defn- fmt [x n] (.toFixed (js/Number. x) n))

(defn- clicks-for
  "Clicked lines for one pose, grouped, with the order inside each group
   deterministically SHUFFLED and a little hand noise on each endpoint."
  [pose sigma rng shuffle?]
  (let [kk (k*)
        vis (set (bf/visible-edges true-dims pose))]
    (into {}
          (for [g [:vertical :top :bottom]
                :let [ks (filterv vis (match/projected-edges true-dims pose kk g))
                      segs (keep (fn [[_ [a b]]]
                                   (let [[nx ny _] (bf/line-through a b)
                                         j (fn [p o] [(+ (first p) (* nx o))
                                                      (+ (second p) (* ny o))])
                                         o1 (* sigma (synth/gauss rng))
                                         o2 (* sigma (synth/gauss rng))]
                                     {:p1 (j a o1) :p2 (j b o2)}))
                                 (match/projected-edges true-dims pose kk g))]
                :when (seq segs)]
            [g (vec (if shuffle? (reverse segs) segs))]))))

(defn- pose-at [az el d]
  (let [a (/ (* az Math/PI) 180.0) e (/ (* el Math/PI) 180.0)]
    (cam/look-at-pose [(* d (Math/cos e) (Math/cos a))
                       (* d (Math/cos e) (Math/sin a))
                       (* d (Math/sin e))]
                      [0.0 0.0 0.0] [0.0 0.0 1.0])))

(deftest line-distance-is-about-the-line-not-the-endpoints
  (testing "sliding endpoints along a line does not change the distance"
    (let [a [[100.0 100.0] [200.0 140.0]]
          b [[300.0 180.0] [400.0 220.0]]]   ; same line, different stretch
      (is (< (match/line-distance a b) 1e-9))))
  (testing "an offset line is measured as offset"
    (let [a [[100.0 100.0] [200.0 140.0]]
          b [[100.0 130.0] [200.0 170.0]]]
      (is (> (match/line-distance a b) 20.0)))))

;; A box maps onto itself under four proper rotations: the identity and 180
;; degrees about each axis (the Klein four-group). Each permutes the twelve
;; edge labels while describing the SAME solid seen from a symmetry-related
;; pose, so a recovered labelling is correct if it matches the truth under any
;; of them. Demanding literal equality would reject a perfectly good answer —
;; which is exactly what the first version of this test did.
(def ^:private corner-signs
  (vec (for [a [-1 1] b [-1 1] c [-1 1]] [a b c])))

(defn- edge-perm
  "How a sign flip permutes edge indices."
  [flip]
  (let [corner-of (fn [s] (.indexOf (mapv vec corner-signs) (vec s)))
        edge-of (fn [[i j]]
                  (let [a (corner-of (mapv * (nth corner-signs i) flip))
                        b (corner-of (mapv * (nth corner-signs j) flip))
                        pr (sort [a b])]
                    (first (keep-indexed (fn [k e] (when (= (sort e) pr) k)) bf/edges))))]
    (mapv (fn [e] (edge-of e)) bf/edges)))

(def ^:private symmetries
  (mapv edge-perm [[1 1 1] [1 -1 -1] [-1 1 -1] [-1 -1 1]]))

(defn- symmetry-equivalent? [got want]
  (boolean (some (fn [perm] (= (set (map #(nth perm %) got)) (set want)))
                 symmetries)))

(deftest box-symmetries-are-well-formed
  (testing "each symmetry is a permutation of all twelve edges"
    (doseq [p symmetries]
      (is (= (set p) (set (range 12))) (str "not a permutation: " (vec p))))))

(deftest recovers-correspondence-from-geometry
  (println "\n=== EXP 18 — corrispondenza per-foto, senza ordine dei click ===")
  (let [rng (synth/rng 31)
        kk (k*)]
    (doseq [[az el] [[35 30] [80 25] [130 35]]]
      (let [pose (pose-at az el 280)
            truth (into {} (for [g [:vertical :top :bottom]]
                             [g (mapv first (match/projected-edges true-dims pose kk g))]))
            picks (clicks-for pose 0.5 rng true)
            sol (match/solve-photo true-dims kk picks 0 {:sigma-px 1.0})
            got (set (map :edge (:obs sol)))
            want (set (mapcat val truth))]
        (println (str "  az=" az "° el=" el "° → "
                      (count (:obs sol)) " spigoli, reproiez. "
                      (fmt (:rms-px sol) 2) " px, "
                      (cond (= got want) "corrispondenza esatta"
                            (symmetry-equivalent? got want) "corretta a meno di simmetria"
                            :else "SBAGLIATA")))
        (testing (str "pose az=" az)
          (is (some? sol) "must find a solution")
          (is (symmetry-equivalent? got want)
              (str "recovered edges " (sort got) " vs visible " (sort want)
                   " — not related by any symmetry of the box"))
          (is (< (:rms-px sol) 3.0)
              (str "reprojection " (fmt (:rms-px sol) 2) " px")))))
    (println "  (i click sono stati passati in ordine INVERTITO dentro ogni gruppo:")
    (println "   se l'ordine contasse ancora, questi test fallirebbero)")
    (println "  Le etichette valgono a meno delle 4 simmetrie della scatola")
    (println "  (identità + 180° attorno a ciascun asse): descrivono lo stesso")
    (println "  solido da una posa simmetrica, e non cambiano le misure.")))

(deftest order-genuinely-does-not-matter
  (testing "shuffled and unshuffled clicks give the same correspondence"
    (let [kk (k*)
          pose (pose-at 55 28 280)
          a (match/solve-photo true-dims kk (clicks-for pose 0.0 (synth/rng 5) false) 0 {})
          b (match/solve-photo true-dims kk (clicks-for pose 0.0 (synth/rng 5) true) 0 {})]
      (is (symmetry-equivalent? (set (map :edge (:obs a))) (set (map :edge (:obs b))))
          "the recovered edge set must not depend on click order"))))

(deftest no-azimuth-blind-spot
  ;; The coverage bug, made a permanent guard. On the real session, solve-photo
  ;; landed IMG_8907 (azimuth ~45) at 152px while its Klein twin solved cleanly,
  ;; because top-N-by-cost seeding dropped the true pose in favour of a cheaper
  ;; wrong assignment. Best-per-azimuth-sector seeding fixes it. If any single
  ;; viewpoint regresses to a blind spot, this catches it.
  (println "\n=== EXP 19 — nessun punto cieco in azimut (regressione IMG_8907) ===")
  (let [kk (k*)
        worst (atom {:az nil :rms 0})]
    (doseq [az (range 0 360 15)]
      (let [pose (pose-at az 30 280)
            picks (clicks-for pose 0.4 (synth/rng (+ 700 az)) false)
            sol (match/solve-photo true-dims kk picks 0 {:sigma-px 1.0})]
        (when (or (nil? sol) (> (:rms-px sol) (:rms @worst)))
          (reset! worst {:az az :rms (if sol (:rms-px sol) 999.0)}))
        (is (some? sol) (str "az=" az "° non risolta (punto cieco)"))
        (is (and sol (< (:rms-px sol) 4.0))
            (str "az=" az "° rms " (when sol (fmt (:rms-px sol) 2)) " px"))))
    (println (str "  24 azimut testati, peggiore: az=" (:az @worst) "° a "
                  (fmt (:rms @worst) 2) " px (era 152px a 45° prima del fix)"))))

(deftest tolerates-a-missing-edge
  (testing "a group with one edge missing still matches the rest"
    ;; Under the old scheme a count mismatch disqualified the whole photo,
    ;; which is how half a session's clicks got silently discarded.
    (let [kk (k*)
          pose (pose-at 42 30 280)
          full (clicks-for pose 0.3 (synth/rng 9) false)
          gapped (update full :top #(vec (drop 1 %)))
          sol (match/solve-photo true-dims kk gapped 0 {})]
      (is (some? sol) "a photo with a missing edge must still solve")
      (is (< (:rms-px sol) 3.0)
          (str "reprojection " (fmt (:rms-px sol) 2) " px"))
      (is (= (count (:obs sol)) (reduce + (map count (vals gapped))))
          "every clicked line should be matched"))))

(deftest fit-turntable-seeded-recovers-a-consistent-multi-photo-pose
  ;; Regression guard for edit-acquire's 'f' (2026-07-22): a version of this
  ;; function that narrowed the yaw search around a 2-photo consistency
  ;; estimate returned a NON-nil result at 217px / 167px reprojection — a
  ;; real candidate, just not the right one — instead of failing loudly.
  ;; Live-diagnosed: the naive 2-point estimate was off by ~34° while
  ;; reporting an internal disagreement of only ~12°, nowhere near
  ;; self-diagnosing. The default (full 360° yaw sweep, axis offset fixed
  ;; at the origin, both rotation senses) must recover the true turntable
  ;; pose from just a few photos, at reprojection error near the synthetic
  ;; noise floor, not a spurious high-residual candidate.
  (println "\n=== fit-turntable-seeded: recovers a consistent pose from few photos ===")
  (let [kk (k*)
        rng (synth/rng 42)
        base (pose-at 10 25 260)
        axis (tt/axis-from-params 0.0 0.0 0.0 0.0)
        thetas-deg [0.0 30.0 65.0]
        yaw (/ (* 40.0 Math/PI) 180.0)
        sense 1.0
        ground-truth-poses (mapv (fn [theta-deg]
                                   (tt/pose-at-angle base axis
                                                     (+ yaw (* sense (/ (* theta-deg Math/PI) 180.0)))))
                                 thetas-deg)
        photos (mapv (fn [pose theta-deg]
                       {:picks (clicks-for pose 0.3 rng false)
                        :theta-deg theta-deg})
                     ground-truth-poses thetas-deg)
        result (match/fit-turntable-seeded true-dims kk photos {:sigma-px 1.0})]
    (is (some? result) "must find a turntable fit from 3 synthetic photos")
    (when result
      (println (str "  3 foto, rms " (fmt (:rms result) 2) " px, used=" (:used result)))
      (is (< (:rms result) 5.0)
          (str "joint fit reprojection " (fmt (:rms result) 2) " px — expected near the "
               "synthetic noise floor, not a spurious high-residual candidate")))))
