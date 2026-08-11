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
    (testing "fewer than four correspondences → nil (neither the DLT's six nor the homography's four)"
      (let [corr (vec (take 3 (correspondences-for pose 0.0 (synth/rng 1))))]
        (is (nil? (pnp/estimate-dlt corr kk)))
        (is (nil? (pnp/estimate-homography corr kk)))
        (is (nil? (pnp/solve-pnp corr kk {})))))
    (testing "coplanar points → DLT singular, but the homography registers them"
      ;; The pivot behind the planar path: a set on ONE plane makes the DLT's
      ;; normal equations singular (nil), yet is exactly what the homography
      ;; solves — solve-pnp must route to it rather than fail.
      (let [coplanar (mapv (fn [[x y]] [x y 20.0]) [[-30 -10] [30 -10] [30 10] [-30 10] [0 -10] [0 10]])
            corr (vec (keep (fn [w] (when-let [px (cam/project kk pose w)] {:world w :px px})) coplanar))
            sol (pnp/solve-pnp corr kk {})]
        (is (nil? (pnp/estimate-dlt corr kk))
            "a set of coplanar model points must not yield a confident DLT pose")
        (is (= :planar (:method sol)) "solve-pnp routes coplanar points to the homography")
        (is (< (:rms-px sol) 1.0) "and registers them cleanly")))
    (testing "too few but WITH a seed → refines from the seed"
      (let [corr (vec (take 3 (correspondences-for pose 0.0 (synth/rng 2))))
            sol (pnp/solve-pnp corr kk {:seed pose})]
        (is (= :seed (:method sol)) "falls back to the supplied coarse seed")))))

;; ---------------------------------------------------------------------------
;; Planar PnP — the registration PLATE (marks all on one face, coplanar)

(defn- ring-marks
  "N coplanar model points on a circle of `radius` in the z=`z` plane — the
   registration plate's corona of marks in the object frame. Coplanar by
   construction: the DLT is singular here; the homography is the right tool."
  [n radius z]
  (mapv (fn [i]
          (let [a (* 2.0 Math/PI (/ (double i) n))]
            [(* radius (Math/cos a)) (* radius (Math/sin a)) z]))
        (range n)))

(defn- coplanar-correspondences-for
  "Project each plate mark at `pose`, with a little gaussian click noise."
  [marks pose sigma rng]
  (let [kk (k*)]
    (vec (keep (fn [world]
                 (when-let [[u v] (cam/project kk pose world)]
                   {:world world
                    :px [(+ u (* sigma (synth/gauss rng)))
                         (+ v (* sigma (synth/gauss rng)))]}))
               marks))))

(deftest cleans-a-bad-point-even-when-the-total-looks-acceptable
  ;; Live webcam frames sit at 5-12px, not the 1-3px a phone photo gives, and the
  ;; cleaning loop used to stop as soon as the rms fell under accept-rms-px (12).
  ;; So every frame kept its worst point. Measured on a real session (2026-08-11):
  ;; ten marks at 1-5px with ONE at 26-34px, which alone was three quarters of the
  ;; reported error — and the joint refine could not help, because the damage was
  ;; in the correspondences, not the poses (10.158 → 10.120 px over four views).
  (println "\n=== PnP: un punto sbagliato va tolto anche se il totale è 'accettabile' ===")
  ;; Built like the real thing: a PLATE (12 coplanar marks) on a 1920-wide frame,
  ;; ten marks at ordinary sub-pixel noise and ONE mis-snapped by 30px — the exact
  ;; signature the session showed.
  (let [kk {:fx 1400.0 :fy 1400.0 :cx 960.0 :cy 540.0 :k1 0.0 :k2 0.0}
        pose (cam/look-at-pose [90.0 -70.0 200.0] [0.0 0.0 0.0] [0.0 1.0 0.0])
        marks (ring-marks 12 58.0 1.5)
        rng (synth/rng 9)
        clean (vec (map-indexed
                    (fn [i w] {:ci i
                               :world w
                               :px (let [[u v] (cam/project kk pose w)]
                                     [(+ u (* 0.4 (synth/gauss rng)))
                                      (+ v (* 0.4 (synth/gauss rng)))])})
                    marks))
        bad (update-in clean [5 :px] (fn [[u v]] [(+ u 30.0) v]))
        before (pnp/solve-pnp bad kk {:max-outliers 0})
        sol (pnp/solve-pnp bad kk {})]
    (println (str "  soglia grossolano su 1920px " (fmt (* pnp/outlier-floor-frac 1920.0) 1)
                  "px · senza pulizia rms " (fmt (:rms-px before) 2)
                  "px → con pulizia " (fmt (:rms-px sol) 2) "px · scartati "
                  (mapv :ci (:outliers sol))))
    (is (some? sol))
    ;; the whole point: the UNCLEANED fit is already "acceptable", so the old loop
    ;; stopped there and kept the bad point
    (is (<= (:rms-px before) pnp/accept-rms-px)
        "il residuo senza pulizia sta sotto la soglia — è per questo che passava")
    (is (some #(= 5 (:ci %)) (:outliers sol))
        "il mark mis-agganciato va tolto lo stesso")
    (is (< (:rms-px sol) 1.5)
        (str "tolto quello, il resto è pulito (" (fmt (:rms-px sol) 2) "px)")))

  (testing "e su un fotogramma da 4032px la soglia resta quella di prima (~30px)"
    (is (< (Math/abs (- 30.0 (* pnp/outlier-floor-frac 4032.0))) 1.0)
        "il percorso delle foto da telefono non deve cambiare"))

  (testing "dati puliti: non si scarta niente"
    (let [kk {:fx 1400.0 :fy 1400.0 :cx 960.0 :cy 540.0 :k1 0.0 :k2 0.0}
          pose (cam/look-at-pose [90.0 -70.0 200.0] [0.0 0.0 0.0] [0.0 1.0 0.0])
          rng (synth/rng 11)
          corr (vec (map (fn [w] {:world w
                                  :px (let [[u v] (cam/project kk pose w)]
                                        [(+ u (* 0.4 (synth/gauss rng)))
                                         (+ v (* 0.4 (synth/gauss rng)))])})
                         (ring-marks 12 58.0 1.5)))
          sol (pnp/solve-pnp corr kk {})]
      (is (empty? (:outliers sol))
          "il rumore ordinario non deve far scartare punti innocenti"))))

(deftest recovers-pose-from-coplanar-plate-marks
  ;; The plate gate that triggered the planar-PnP work: 12 marks all on the
  ;; plate's top face are exactly coplanar, so estimate-dlt is singular. The
  ;; homography seed + refine must recover the camera pose at the noise floor,
  ;; and :auto must ROUTE to :planar off the coplanarity test (not stumble into
  ;; a garbage DLT that click noise made non-singular).
  (println "\n=== PnP planare: recupero posa da mark complanari di un piatto ===")
  (let [rng (synth/rng 23)
        kk (k*)
        marks (ring-marks 12 55.0 1.5)] ; ⌀110 corona, marks at z=1.5 in object frame
    ;; oblique views (elevation 30–55°): the turntable ring, where the planar
    ;; ambiguity twin is far and depth is well constrained. Near-vertical
    ;; (fronto-parallel) is the weak case by geometry, not solver — excluded.
    (doseq [[az el] [[0 40] [60 55] [130 30] [220 50]]]
      (let [pose (synth/viewpoint az el 250.0)
            corr (coplanar-correspondences-for marks pose 0.3 rng)
            seed (pnp/estimate-homography corr kk)
            sol (pnp/solve-pnp corr kk {})
            eye (cam/camera-center pose)
            eye-back (cam/camera-center (:pose sol))
            eye-err (Math/sqrt (reduce + (map #(* (- %1 %2) (- %1 %2)) eye eye-back)))]
        (println (str "  az=" az " el=" el " → metodo " (name (:method sol))
                      ", " (:n sol) " punti, rms " (fmt (:rms-px sol) 2)
                      "px, errore centro camera " (fmt eye-err 2) "mm"))
        (testing (str "az " az " el " el)
          (is (nil? (pnp/estimate-dlt corr kk)) "coplanar marks → DLT is singular")
          (is (some? seed) "homography seed must be produced from coplanar marks")
          (is (some? sol) "must solve")
          (is (= :planar (:method sol)) "coplanar marks → planar homography path")
          (is (< (:rms-px sol) 1.5) (str "reprojection " (fmt (:rms-px sol) 2) " px"))
          (is (< eye-err 4.0) (str "camera centre off by " (fmt eye-err 2) " mm")))))))

(deftest planar-homography-seed-alone-is-close
  ;; The homography seed, before any LM, must already land in the right basin —
  ;; that is what makes it a legitimate seedless start, the planar analogue of
  ;; dlt-alone-is-already-close.
  (testing "homography decomposition reprojects reasonably before refinement"
    (let [kk (k*)
          marks (ring-marks 12 55.0 1.5)
          pose (synth/viewpoint 35 45 250.0)
          corr (coplanar-correspondences-for marks pose 0.0 (synth/rng 7))
          seed (pnp/estimate-homography corr kk)
          eye (cam/camera-center pose)
          eye-back (cam/camera-center seed)
          eye-err (Math/sqrt (reduce + (map #(* (- %1 %2) (- %1 %2)) eye eye-back)))]
      (is (some? seed) "homography must produce an estimate from clean coplanar marks")
      (is (< eye-err 10.0)
          (str "seedless homography camera centre close before LM, got " (fmt eye-err 2) " mm")))))

(deftest method-flag-forces-the-estimator
  ;; Vincenzo wants both engines alive with a flag: :planar on the plate, and
  ;; the box's :dlt untouched. :auto routes correctly; the flag forces.
  (let [kk (k*)
        marks (ring-marks 12 55.0 1.5)
        pose (synth/viewpoint 45 45 250.0)
        corr (coplanar-correspondences-for marks pose 0.3 (synth/rng 11))]
    (testing "coplanar plate: :auto and forced :planar both use the homography"
      (is (= :planar (:method (pnp/solve-pnp corr kk {}))))
      (let [forced (pnp/solve-pnp corr kk {:method :planar})]
        (is (= :planar (:method forced)))
        (is (< (:rms-px forced) 1.5))))
    (testing "box corners: :auto stays on the DLT (planar routing does not fire)"
      (let [box-pose (cam/look-at-pose [220.0 -140.0 160.0] [0.0 0.0 0.0] [0.0 0.0 1.0])
            box-corr (correspondences-for box-pose 0.3 (synth/rng 12))]
        (is (= :dlt (:method (pnp/solve-pnp box-corr kk {}))))))))
