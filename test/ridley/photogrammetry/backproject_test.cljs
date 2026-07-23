(ns ridley.photogrammetry.backproject-test
  "Backprojection (camera/pixel-ray + math/ray-plane-point) is the whole
   mathematical core of P3's plane-retrace: a photo click becomes a ray, the ray
   meets the declared plane, and the hit is the traced 3D point. The property it
   must have is that it is the EXACT inverse of cam/project on that plane — a
   point on the plane, projected to a pixel and backprojected onto the same
   plane, must return to itself at the numerical noise floor (no drift), because
   any drift here shows up directly as the retrace sliding off the real feature
   when the view is changed."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.math :as m]
            [ridley.photogrammetry.camera :as cam]))

(defn- k* [w h hfov]
  (cam/intrinsics-from-fov hfov w h))

(defn- dist [[ax ay az] [bx by bz]]
  (Math/sqrt (+ (* (- ax bx) (- ax bx))
                (* (- ay by) (- ay by))
                (* (- az bz) (- az bz)))))

(deftest pixel-ray-is-the-inverse-of-project
  (testing "a point projects to a pixel whose ray returns to it (any depth)"
    (let [kk (k* 4032 3024 39.6)
          pose (cam/look-at-pose [220.0 -140.0 160.0] [0.0 0.0 0.0] [0.0 0.0 1.0])]
      (doseq [p [[10.0 5.0 -3.0] [-25.0 0.0 12.0] [3.0 -18.0 7.0] [0.0 0.0 0.0]]]
        (let [px (cam/project kk pose p)
              ray (cam/pixel-ray kk pose px)
              ;; the recovered point must lie on the ray; recover it by pinning
              ;; the plane through p with an arbitrary (non-edge-on) normal
              hit (m/ray-plane-point ray p [1.0 1.0 1.0])]
          (is (some? hit) (str "ray must meet the plane through " p))
          (is (< (dist p hit) 1e-6)
              (str "backprojected " p " drifted " (.toFixed (dist p hit) 9) " mm")))))))

(deftest retrace-round-trip-on-a-declared-face
  (println "\n=== Backprojection: ricalco su faccia dichiarata, andata-ritorno ===")
  ;; Mimic the real gesture: several object-frame points on the box's TOP face
  ;; (y = +h/2), projected into a photo, then clicked back and intersected with
  ;; that same declared face. The plane's own coordinates (x,z) must come back
  ;; unchanged — this is exactly what makes a traced bezel reproject cleanly.
  (let [[w h d] [60.0 20.0 40.0]
        top-y (* 0.5 h)
        face-point [0.0 top-y 0.0]
        face-normal [0.0 1.0 0.0]
        kk (k* 4032 3024 39.6)]
    (doseq [eye [[200.0 -150.0 180.0] [-120.0 -200.0 140.0] [40.0 -260.0 90.0]]]
      (let [pose (cam/look-at-pose eye [0.0 0.0 0.0] [0.0 0.0 1.0])
            pts (for [x [-20.0 -5.0 12.0 24.0] z [-15.0 0.0 18.0]]
                  [x top-y z])
            errs (keep (fn [p]
                         (when-let [px (cam/project kk pose p)]
                           (when-let [hit (m/ray-plane-point (cam/pixel-ray kk pose px)
                                                             face-point face-normal)]
                             (dist p hit))))
                       pts)
            worst (apply max errs)]
        (println (str "  eye=" (mapv #(Math/round %) eye) " → "
                      (count errs) " punti, errore max " (.toFixed worst 9) " mm"))
        (testing (str "eye " eye)
          (is (= (count pts) (count errs)) "every face point must round-trip")
          (is (< worst 1e-7)
              (str "worst face round-trip error " (.toFixed worst 9) " mm")))))))
