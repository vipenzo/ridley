(def tolleranza 0.3)
(def spessore 2.5)
(defn neg [x] (* x -1))
(def H 50)
(def tray-ratio 0.6)
(def tray-H (* H tray-ratio))
(def a 100)
(def b 50)
(def b2 (/ b 2))
(def b4 (/ b 3))
(def bb 130)
(def aa (* 2 a))
(def s1 (translate (circle b 512) bb (* aa 0.1)))
(def s2 (circle b2 512))
(def s3 (translate (circle b4 512) bb (* aa -0.2)))
(def spessore-gabbia 8)

(def base-shape (resample-shape (shape-hull s3 s1 s2) 2048))
(def n-pts (count (:points base-shape)))
(def gabbia-shape (shape-offset base-shape spessore-gabbia))

(defn forma-factor [t]
  (+ 0.3 (* 0.7 (cos (* t PI 0.5)))))

;; Bowl: shell of base-shape scaled by forma-factor
(defn make-forma [z0 z1]
  (fn [shape t]
    (let [z (+ z0 (* t (- z1 z0)))]
      (scale-shape shape (forma-factor (/ z H))))))

;; Tray: same scaling, then offset inward by (spessore + tolleranza)
;; Guarantees: tray outer wall = bowl outer wall - (spessore + tolleranza)
;;             gap between bowl inner and tray outer = tolleranza
(defn make-forma-tray [z0 z1]
  (fn [shape t]
    (let [z (+ z0 (* t (- z1 z0)))
          scaled (scale-shape shape (forma-factor (/ z H)))
          inset (shape-offset scaled (neg (+ spessore tolleranza)))
          s (shape-difference inset (scale-shape s2 (forma-factor (/ z H))))]
      (resample-shape s n-pts))))

(def _bowl
  (turtle
   (loft (shell (shape-fn base-shape (make-forma 0 H))
                :thickness spessore
                :fn (fn [a t] 1.0)
                :cap-top spessore)
         (f H))))
(register bowl _bowl)

;; Tray = shell of base-shape offset inward, tray height
;; mesh-smooth rounds off the staircase aliasing along the voronoi shell
;; silhouette using Manifold's tangent-based subdivision (sharp-angle 100
;; smooths every dihedral <= 100deg, refine 2 quadruples triangle count).
;; mesh-laplacian: Taubin smoothing (iterative vertex averaging, no overshoot)
;; Eliminates the staircase aliasing at voronoi shell hole edges.
(def _tray
  (-> (loft-n 256
              (shell (shape-fn base-shape (make-forma-tray 0 tray-H))
                     :style :voronoi
                     :thickness spessore :cells 10 :rows 4 :seed 42
                     :cap-top {:style :voronoi :thickness spessore})
              (f tray-H))
      (mesh-simplify 0.5)))
(register tray _tray)

(color :bowl 0xffff00)
(color :tray 0x888888)
(hide :bowl)