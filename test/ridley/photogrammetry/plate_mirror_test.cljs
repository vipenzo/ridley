(ns ridley.photogrammetry.plate-mirror-test
  "The plate's mirror twin, on REAL picks.

   Vincenzo, 2026-08-01/02: on one photo the proxy comes up seen from behind —
   the crown dots land on the face pointing away — and `r` puts it back on the
   wrong side however he rotates it by hand. The handover's plan was to make
   pnp/estimate-homography return both branches of its decomposition and let the
   physical constraint choose. Measuring first says otherwise: with the plane
   metrically known and h33 pinned, that decomposition has exactly ONE valid
   branch (the other puts the target behind the camera), so there is nothing to
   return. The ambiguity is in the LABELS.

   A crown of evenly-spaced marks is mirror-symmetric about the axis through
   mark 0 — and so is the zero-index, which sits radially inside mark 0 on that
   very axis. Reflecting every declared identity therefore produces a labelling
   the same clicks fit EQUALLY WELL, whose camera sits on the opposite side of
   the printed face. Equally well is not a figure of speech here: the two rms
   agree to ~1e-9 px. No residual test can ever separate them; the physical one
   always does, because the discs were photographed.

   The picks below are Vincenzo's own, lifted from test-assets/param-plate-paper
   (photo 9, the grazing shot that fails; photo 8, a good one) and inlined so the
   test needs neither the session file nor the 2 MB JPEGs beside it."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.photogrammetry.plate :as plate]
            [ridley.photogrammetry.bridge :as bridge]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.pnp :as pnp]))

(def ^:private image-w 4032)
(def ^:private image-h 3024)
(def ^:private focal-mm 48)

(def ^:private photo-9-picks
  "The grazing shot: 11 crown marks clicked (mark 0 hidden by the part)."
  {1 [2848.137 1242.458]  2 [3349.304 1379.823]  3 [3616.354 1574.519]
   4 [3459.694 1796.397]  5 [2777.866 1967.268]  6 [1774.786 1983.099]
   7 [934.883 1839.349]   8 [574.009 1615.219]   9 [673.334 1407.886]
   10 [1071.232 1263.397] 11 [1621.784 1186.287]})

(def ^:private photo-8-picks
  "A good shot of the same session, all 12 marks, correct as declared."
  {0 [665.735 1187.682]   1 [561.876 1426.521]   2 [883.379 1678.738]
   3 [1640.025 1843.475]  4 [2568.829 1824.832]  5 [3235.528 1633.047]
   6 [3429.078 1368.942]  7 [3215.768 1139.195]  8 [2754.542 982.082]
   9 [2182.097 914.165]   10 [1591.892 928.548]  11 [1054.357 1014.424]})

(defn- solve
  "Solve `picks` (crown index → pixel) against the plate, optionally reflecting
   every declared identity first. Returns {:rms-px :sees-face? :camera-z}."
  [picks mirror?]
  (let [mesh (plate/registration-plate)
        detect (bridge/plate-detect mesh)
        targets (bridge/pnp-target-points mesh nil)
        n (count targets)
        k (cam/intrinsics-from-fov
           (cam/equiv-focal->hfov-deg focal-mm (/ image-w image-h)) image-w image-h)
        corr (mapv (fn [[ci px]]
                     (let [j (if mirror? (bridge/mirror-crown-index n ci) ci)]
                       {:ci j :world (:obj (nth targets j)) :px px}))
                   picks)
        sol (pnp/solve-pnp corr k {})]
    {:rms-px (:rms-px sol)
     :n (:n sol)
     :sees-face? (bridge/camera-sees-marked-face? detect (:pose sol))
     :camera-z (nth (cam/camera-center (:pose sol)) 2)}))

(deftest mirror-crown-index-is-the-crown-s-own-symmetry
  (testing "mark 0 is fixed — it is the axis the crown (and the zero-index
            sitting radially inside it) reflects about"
    (is (= 0 (bridge/mirror-crown-index 12 0))))
  (testing "it pairs each mark with its opposite across that axis"
    (is (= [0 11 10 9 8 7 6 5 4 3 2 1]
           (mapv #(bridge/mirror-crown-index 12 %) (range 12)))))
  (testing "applying it twice is the identity, so a solve can try it and undo it"
    (doseq [n [8 12 16 36] i (range n)]
      (is (= i (bridge/mirror-crown-index n (bridge/mirror-crown-index n i)))))))

(deftest grazing-photo-the-residual-cannot-choose-but-physics-can
  (let [declared (solve photo-9-picks false)
        mirrored (solve photo-9-picks true)]
    (testing "both labellings are actually fitted (no correspondence dropped)"
      (is (= 11 (:n declared) (:n mirrored))))
    (testing "the two fits are EQUAL, not merely close — so no rms threshold,
              however tuned, could ever pick the right one"
      (is (< (Math/abs (- (:rms-px declared) (:rms-px mirrored))) 1e-6)
          (str "declared " (:rms-px declared) " vs mirrored " (:rms-px mirrored))))
    (testing "as declared, the camera sits BEHIND the printed face — impossible,
              since the discs are what was photographed"
      (is (false? (:sees-face? declared)))
      (is (neg? (:camera-z declared))))
    (testing "reflecting the labels puts it in front, at the mirrored height"
      (is (true? (:sees-face? mirrored)))
      (is (pos? (:camera-z mirrored))))))

(deftest a-good-photo-is-not-flipped-by-the-same-rule
  (testing "the test discriminates both ways: on a correctly-declared photo the
            reflection is the one that becomes impossible, so a solve that only
            mirrors when the camera lands behind never touches this photo"
    (let [declared (solve photo-8-picks false)
          mirrored (solve photo-8-picks true)]
      (is (true? (:sees-face? declared)))
      (is (pos? (:camera-z declared)))
      (is (false? (:sees-face? mirrored)))
      (is (neg? (:camera-z mirrored)))
      (is (< (Math/abs (- (:rms-px declared) (:rms-px mirrored))) 1e-6)))))
