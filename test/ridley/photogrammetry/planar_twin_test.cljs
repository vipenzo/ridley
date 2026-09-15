(ns ridley.photogrammetry.planar-twin-test
  "The 2-fold ambiguity of a PLANAR pose, and how to get out of it.

   A plane admits two camera poses that project it identically. On a good, oblique
   shot the reprojection error separates them; on a grazing one with a mark or two
   missing it cannot, and the solver can return the twin — the camera BEHIND the
   printed face. The proxy then renders seen from behind and its crown of dots
   lands on the far side, which reads as 'the proxy has been flipped' (Vincenzo,
   param-plate-paper photo 10).

   Two things must hold for that to be recoverable:
     1. the wrong twin must be RECOGNISABLE — by physics, not by residual: the
        discs were photographed, so the camera was on their side;
     2. the solver must be steerable into the other basin — `:method :seeded`
        refines the caller's pose instead of letting an estimator choose."
  (:require [cljs.test :refer [deftest is testing]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.bridge :as bridge]
            [ridley.photogrammetry.pnp :as pnp]
            [ridley.photogrammetry.plate :as plate]))

(defn- k* []
  {:fx 5590.0 :fy 5590.0 :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})

(def ^:private detect
  "What plate-detect would report for a plate at its identity creation-pose:
   the marked face's normal and a point on it, in the object frame."
  {:face-normal [0.0 0.0 1.0] :zero-obj [52.0 0.0 1.5]})

(defn- crown-points
  "The plate's crown in the object frame — coplanar at z = +h/2, which is what
   makes the pose planar and the ambiguity possible in the first place."
  []
  (mapv :position (vals (dissoc (:anchors (plate/registration-plate :d 130)) :zero))))

(defn- correspondences [pose pts]
  (vec (keep (fn [p] (when-let [px (cam/project (k*) pose p)] {:world p :px px})) pts)))

(defn- fmt [x] (.toFixed (js/Number. x) 2))

(deftest the-physical-test-separates-the-twins
  (println "\n=== Gemello planare: riconoscerlo ===")
  (let [true-pose (cam/look-at-pose [180.0 0.0 200.0] [0.0 0.0 1.5] [0.0 0.0 1.0])
        ;; the twin as the symptom presents it: the camera on the other side
        twin-pose (cam/look-at-pose [180.0 0.0 -200.0] [0.0 0.0 1.5] [0.0 0.0 1.0])]
    (println (str "  camera davanti: " (bridge/camera-sees-marked-face? detect true-pose)
                  " | camera dietro: " (bridge/camera-sees-marked-face? detect twin-pose)))
    (is (true? (bridge/camera-sees-marked-face? detect true-pose)))
    (is (false? (bridge/camera-sees-marked-face? detect twin-pose))
        "a pose behind the printed face is impossible, and says so")))

(deftest seeding-chooses-the-basin
  (println "\n=== Gemello planare: uscirne col seed ===")
  (let [pts (crown-points)
        true-pose (cam/look-at-pose [180.0 0.0 200.0] [0.0 0.0 1.5] [0.0 0.0 1.0])
        corr (correspondences true-pose pts)
        ;; a seed near the truth, as a neighbouring photo's pose would be
        near (cam/look-at-pose [200.0 30.0 190.0] [0.0 0.0 1.5] [0.0 0.0 1.0])
        ;; and one on the wrong side
        wrong (cam/look-at-pose [200.0 30.0 -190.0] [0.0 0.0 1.5] [0.0 0.0 1.0])
        from-near (pnp/solve-pnp corr (k*) {:method :seeded :seed near})
        from-wrong (pnp/solve-pnp corr (k*) {:method :seeded :seed wrong})]
    (println (str "  seed davanti → rms " (fmt (:rms-px from-near)) "px, davanti? "
                  (bridge/camera-sees-marked-face? detect (:pose from-near))))
    (println (str "  seed dietro  → rms " (fmt (:rms-px from-wrong)) "px, davanti? "
                  (bridge/camera-sees-marked-face? detect (:pose from-wrong))))
    (testing "a seed on the right side lands on the right pose"
      (is (< (:rms-px from-near) 0.5) "and fits to sub-pixel")
      (is (true? (bridge/camera-sees-marked-face? detect (:pose from-near)))))
    (testing "and — better than expected — so does a seed on the WRONG side"
      (is (< (:rms-px from-wrong) 0.5))
      (is (true? (bridge/camera-sees-marked-face? detect (:pose from-wrong)))
          "the twin is not a stable minimum for a clean crown: LM walks out of it.
           So a photo that came back flipped is recoverable by re-solving from a
           seed — the wrong pose came from the seedless decomposition's choice,
           not from the refinement being trapped. The physical test is still
           applied to the RESULT, because 'very likely' is not 'always'."))))
