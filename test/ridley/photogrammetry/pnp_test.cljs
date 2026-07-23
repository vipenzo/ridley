(ns ridley.photogrammetry.pnp-test
  "PnP from declared 3D↔2D correspondences: the seedless DLT + LM refine must
   recover a known camera pose from box-corner clicks, at the noise floor —
   the property the whole 'registrazione per corrispondenze' pivot rests on
   (no pose search, no Klein flip, because the correspondence is declared)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.pnp :as pnp]
            [ridley.photogrammetry.synth :as synth]))

(def dims [60.0 20.0 40.0])
(defn- k* [] {:fx (* 4032 (/ 48.0 36.0)) :fy (* 4032 (/ 48.0 36.0))
              :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})
(defn- fmt [x n] (.toFixed (js/Number. x) n))

(defn- correspondences-for
  "Project every box corner at `pose`, with a little gaussian click noise."
  [pose sigma rng]
  (let [kk (k*)]
    (vec (keep (fn [world]
                 (when-let [[u v] (cam/project kk pose world)]
                   {:world world
                    :px [(+ u (* sigma (synth/gauss rng)))
                         (+ v (* sigma (synth/gauss rng)))]}))
               (bf/corners dims)))))

(deftest recovers-a-known-pose-from-corner-correspondences
  (println "\n=== PnP: recupero posa da corrispondenze di spigolo dichiarate ===")
  (let [rng (synth/rng 17)
        kk (k*)]
    (doseq [eye [[220.0 -140.0 160.0] [300.0 40.0 120.0] [-180.0 -200.0 220.0]]]
      (let [pose (cam/look-at-pose eye [0.0 0.0 0.0] [0.0 0.0 1.0])
            corr (correspondences-for pose 0.3 rng)
            sol (pnp/solve-pnp corr kk {:sigma-px 1.0})
            eye-back (cam/camera-center (:pose sol))
            eye-err (Math/sqrt (reduce + (map #(* (- %1 %2) (- %1 %2)) eye eye-back)))]
        (println (str "  eye=" (mapv #(Math/round %) eye) " → metodo " (name (:method sol))
                      ", " (:n sol) " punti, rms " (fmt (:rms-px sol) 2)
                      "px, errore centro camera " (fmt eye-err 2) "mm"))
        (testing (str "eye " eye)
          (is (some? sol) "must solve")
          (is (= :dlt (:method sol)) "8 corners → seedless DLT path")
          (is (< (:rms-px sol) 2.0) (str "reprojection " (fmt (:rms-px sol) 2) " px"))
          (is (< eye-err 3.0) (str "camera centre off by " (fmt eye-err 2) " mm")))))))

(deftest dlt-alone-is-already-close
  ;; The linear estimate, before any LM, must land in the right basin — that
  ;; is what makes it a legitimate seedless start rather than a lucky refine.
  (testing "seedless DLT reprojects reasonably before refinement"
    (let [kk (k*)
          pose (cam/look-at-pose [240.0 -120.0 150.0] [0.0 0.0 0.0] [0.0 0.0 1.0])
          corr (correspondences-for pose 0.0 (synth/rng 3))
          seed (pnp/estimate-dlt corr kk)
          eye-back (cam/camera-center seed)
          eye-err (Math/sqrt (reduce + (map #(* (- %1 %2) (- %1 %2))
                                            [240.0 -120.0 150.0] eye-back)))]
      (is (some? seed) "DLT must produce an estimate from 8 clean corners")
      (is (< eye-err 8.0)
          (str "seedless DLT camera centre within a cm before LM, got " (fmt eye-err 2) " mm")))))

(deftest rejects-a-mislabeled-corner
  ;; The real-photo failure mode Vincenzo hit ("certe foto non scendono sotto
  ;; 62px"): ONE wrong-identity click on a symmetric box poisons the whole
  ;; least-squares pose, so EVERY corner ends up tens of px off — not one clean
  ;; outlier. Greedy rejection must drop the culprit and recover a clean pose
  ;; from the innocent corners.
  (println "\n=== PnP: scarto di uno spigolo mal-etichettato ===")
  (let [kk (k*)
        pose (cam/look-at-pose [220.0 -140.0 160.0] [0.0 0.0 0.0] [0.0 0.0 1.0])
        corr (vec (map-indexed (fn [i c] (assoc c :ci i))
                               (correspondences-for pose 0.2 (synth/rng 4))))]
    (testing "clean data drops nothing"
      (let [sol (pnp/solve-pnp corr kk {})]
        (is (empty? (:outliers sol)) "no correspondence should be rejected from clean data")))
    (testing "corner 7 given corner 3's pixel is detected and dropped"
      (let [bad (assoc-in corr [7 :px] (get-in corr [3 :px]))
            sol (pnp/solve-pnp bad kk {})]
        (is (some? sol))
        (println (str "  rms dopo scarto " (fmt (:rms-px sol) 2) "px, scartati "
                      (mapv :ci (:outliers sol))))
        (is (< (:rms-px sol) pnp/accept-rms-px)
            (str "fit must be clean after rejection, got " (fmt (:rms-px sol) 2) " px"))
        (is (some #(= 7 (:ci %)) (:outliers sol))
            "the mislabeled corner 7 must be the one dropped")))))

(deftest rejects-too-few-and-coplanar
  (let [kk (k*)
        pose (cam/look-at-pose [220.0 -140.0 160.0] [0.0 0.0 0.0] [0.0 0.0 1.0])]
    (testing "fewer than six correspondences → nil (no seed given)"
      (let [corr (vec (take 4 (correspondences-for pose 0.0 (synth/rng 1))))]
        (is (nil? (pnp/estimate-dlt corr kk)))
        (is (nil? (pnp/solve-pnp corr kk {})))))
    (testing "coplanar points → singular normal equations → nil"
      (let [coplanar (mapv (fn [[x y]] [x y 20.0]) [[-30 -10] [30 -10] [30 10] [-30 10] [0 -10] [0 10]])
            corr (vec (keep (fn [w] (when-let [px (cam/project kk pose w)] {:world w :px px})) coplanar))]
        (is (nil? (pnp/estimate-dlt corr kk))
            "a set of coplanar model points must not yield a confident pose")))
    (testing "too few but WITH a seed → refines from the seed"
      (let [corr (vec (take 3 (correspondences-for pose 0.0 (synth/rng 2))))
            sol (pnp/solve-pnp corr kk {:seed pose})]
        (is (= :seed (:method sol)) "falls back to the supplied coarse seed")))))
