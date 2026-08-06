(ns ridley.photogrammetry.bundle-test
  "One focal for the session, every pose refined together.

   The experiment each test runs is the same: build a synthetic session whose
   truth is known — a plate of marks, some cameras around it, a real focal —
   then hand the solver the WRONG focal (the EXIF value, quantised or plain
   mistaken) and the poses that a per-photo PnP would have produced under that
   wrong focal, and check that the joint fit recovers the truth.

   What must hold is not just 'the residual gets smaller'. A wrong focal is
   absorbed by each photo into its own distance, so the per-photo residuals look
   fine while every camera sits at the wrong depth; the test therefore measures
   the CAMERA POSITIONS against the truth, which is the thing the fusion
   downstream actually cares about."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.bundle :as bundle]))

(def ^:private image-size [4032 3024])
(def ^:private true-focal 48.0)

(def ^:private plate
  "Twelve marks on a crown of radius 58, plus the inner zero — the registration
   plate's own geometry, which is what the picks are of."
  (conj (mapv (fn [i]
                (let [a (* i (/ (* 2 Math/PI) 12))]
                  [(* 58 (Math/cos a)) (* 58 (Math/sin a)) 1.5]))
              (range 12))
        [52.0 0.0 1.5]))

(defn- camera-at
  "A camera 250 mm from the plate centre, at azimuth `az` and elevation `el`."
  [az el]
  (let [r 250.0
        eye [(* r (Math/cos el) (Math/cos az))
             (* r (Math/cos el) (Math/sin az))
             (* r (Math/sin el))]]
    (cam/look-at-pose eye [0 0 0] [0 0 1])))

(def ^:private truth
  (mapv (fn [i] (camera-at (* i (/ (* 2 Math/PI) 5)) (+ 0.5 (* 0.1 i)))) (range 5)))

(defn- views-with
  "The session as the solver receives it: picks projected through the TRUE focal
   and true poses (that is what the photographs contain), paired with the poses
   a per-photo fit produced under `assumed-focal` — which is the true pose pushed
   in depth by the focal error, exactly what a lone photo does with it."
  [assumed-focal]
  (let [k-true (cam/intrinsics-from-fov
                (cam/equiv-focal->hfov-deg true-focal (/ 4032 3024)) 4032 3024)
        scale (/ assumed-focal true-focal)]
    (mapv (fn [pose idx]
            {:idx idx
             :image-size image-size
             :picks (vec (keep (fn [X] (when-let [px (cam/project k-true pose X)]
                                         {:world X :px px}))
                               plate))
             ;; a longer assumed focal is compensated by a camera further away:
             ;; the translation scales, the rotation does not
             :pose {:rvec (:rvec pose) :t (mapv #(* scale %) (:t pose))}})
          truth (range))))

(defn- centre-error-mm
  "How far each fitted camera centre is from the truth."
  [poses]
  (mapv (fn [p t] (la/v-norm (la/v-sub (cam/camera-center p) (cam/camera-center t))))
        poses truth))

(deftest a-wrong-focal-moves-every-camera-and-hides-in-the-residual
  ;; The premise. Without this the rest would be solving a problem nobody has.
  (let [views (views-with 49.0)                 ; EXIF says 49, the lens is 48
        errs (centre-error-mm (mapv :pose views))]
    (is (every? #(> % 3.0) errs)
        "a 2% focal error puts every camera centimetres out in depth")))

(deftest the-joint-fit-recovers-the-focal-and-the-poses
  (let [views (views-with 49.0)
        out (bundle/refine-session views 49.0)]
    (is (nil? (:error out)) (:error out))
    (testing "the focal comes back to the lens's own"
      (is (< (js/Math.abs (- (:focal-mm out) true-focal)) 0.05)
          (str "fitted " (:focal-mm out))))
    (testing "and every camera goes back where it was"
      (is (every? #(< % 0.05) (centre-error-mm (:poses out)))
          (str (centre-error-mm (:poses out)))))
    (testing "the reprojection improves too, though that was never the point"
      (is (< (:rms-px out) (:rms-px (:before out))))
      (is (< (:rms-px out) 0.01)))))

(deftest it-does-not-invent-a-lens
  (testing "a focal that is already right is left alone"
    (let [out (bundle/refine-session (views-with true-focal) true-focal)]
      (is (< (js/Math.abs (- (:focal-mm out) true-focal)) 0.02))))

  (testing "and it will not wander off to explain bad data"
    ;; one mark mislabelled: the fit must not turn that into a strange lens
    (let [views (update-in (vec (views-with 48.0)) [0 :picks 0 :px]
                           (fn [[u v]] [(+ u 300) (- v 250)]))
          out (bundle/refine-session views 48.0)]
      (is (< (js/Math.abs (- (:focal-mm out) 48.0)) (* 48.0 bundle/focal-band))
          "the focal stays inside its band")
      (is (> (:rms-px out) 1.0)
          "and the damage stays visible as residual instead of being absorbed"))))

(deftest one-photo-cannot-separate-focal-from-distance
  (let [out (bundle/refine-session (take 1 (views-with 49.0)) 49.0)]
    (is (re-find #"almeno DUE foto" (:error out)))))

(deftest views-without-picks-or-pose-are-skipped-not-fitted
  (let [views (concat (views-with 49.0)
                      [{:idx 99 :image-size image-size :picks [] :pose nil}])
        out (bundle/refine-session (vec views) 49.0)]
    (is (nil? (:error out)))
    (is (= 5 (count (:poses out))) "the unregistered photo takes no part")
    (is (= [0 1 2 3 4] (:views out)) "and the caller is told which ones did")))
