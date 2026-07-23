(ns ridley.photogrammetry.branch-by-marker-test
  "bridge/branch-by-marker is fix (1) of the edit-acquire registration-stability
   work: it resolves the Klein branch of a registration by an OBSERVED asymmetric
   mark (a pen arrow on one box corner) instead of a predicted turntable seed. A
   box with three distinct sides has four camera poses that reproject to the same
   silhouette (klein-images) — the edge solver can't tell them apart, which is
   what splits a turntable session into two opposite senses. The mark is NOT
   silhouette-symmetric, so it images to a different pixel under each of the four
   poses; clicking it decides the branch by construction. The property this test
   pins: given the pixel the mark truly images to under some Klein image, the
   function returns THAT image — for every one of the four, and independently of
   the turntable angle (the θ≈180 case, where a seed-based guess is worst, is
   just another camera here). If this regresses, a symmetric box silently
   registers onto the wrong twin again."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.math :as m]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.bridge :as bridge]))

(def proxy-pose
  ;; a generically-oriented proxy (heading/up not axis-aligned) so the test
  ;; doesn't accidentally pass only for the trivial world-aligned box
  {:position [12.0 -3.0 8.0]
   :heading (m/normalize [0.9 0.3 0.1])
   :up (m/orthogonalize-up (m/normalize [0.9 0.3 0.1]) [-0.05 0.2 0.98])})

(def dims [60.2 20.2 40.1])
;; the +++ corner in the object/box-local frame (x=ex y=ey z=ez, box-fit's frame)
(def marker-obj [(/ (nth dims 0) 2.0) (/ (nth dims 1) 2.0) (/ (nth dims 2) 2.0)])

(def intrinsics (cam/intrinsics-from-fov 55.0 4032 3024))

(defn- look-at-camera [eye target up]
  (let [h (m/normalize (m/v- target eye))]
    {:position eye :heading h :up (m/orthogonalize-up h up)}))

;; a camera looking at the box from a standoff
(def camera (look-at-camera [220.0 -140.0 160.0] (:position proxy-pose) [0.0 0.0 1.0]))

(defn- reproject-marker
  "Where the marked corner images under `cam` — the pixel the user would click if
   the box were really in this Klein image."
  [cam]
  (cam/project intrinsics (bridge/editor->solver-pose cam proxy-pose) marker-obj))

(deftest each-klein-image-is-recovered-from-its-own-click
  (testing "clicking the mark where image i puts it returns image i (all four)"
    (let [images (bridge/klein-images camera proxy-pose)]
      (is (= 4 (count images)))
      (doseq [i (range 4)]
        (let [img (nth images i)
              click (reproject-marker img)]
          (is (some? click) (str "image " i " must project the mark in front of the camera"))
          (let [picked (bridge/branch-by-marker camera proxy-pose marker-obj click intrinsics)]
            (is (< (m/magnitude (m/v- (:position picked) (:position img))) 1e-6)
                (str "image " i ": branch-by-marker must return the image whose "
                     "mark-reprojection matches the click"))))))))

(deftest a-sloppy-click-still-picks-the-right-branch
  (testing "the four reprojections are far enough apart that ±80px of click error is safe"
    (let [images (bridge/klein-images camera proxy-pose)
          ;; correct branch = image 3 (180° about ez/heading — the split seen in the
          ;; real tape session); its true reprojection, nudged by a sloppy 80px
          true-px (reproject-marker (nth images 3))
          sloppy (m/v+ true-px [80.0 -60.0])
          picked (bridge/branch-by-marker camera proxy-pose marker-obj sloppy intrinsics)]
      (is (< (m/magnitude (m/v- (:position picked) (:position (nth images 3)))) 1e-6)
          "an 80px-off click still resolves to the intended branch")
      ;; and the margin is genuinely large: the nearest WRONG image sits far away
      (let [others (map reproject-marker [(nth images 0) (nth images 1) (nth images 2)])
            min-sep (apply min (map #(m/magnitude (m/v- % true-px)) others))]
        (is (> min-sep 300.0)
            (str "nearest wrong reprojection should be >300px away, was " min-sep))))))
