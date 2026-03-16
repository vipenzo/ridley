(def tolleranza 0.8)
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
(def s1 (translate (circle b) bb (* aa 0.1)))
(def s2 (circle b2))
(def s3 (translate (circle b4) bb (* aa -0.2)))
(def spessore-gabbia 8)

(def base-shape (shape-hull s3 s1 s2))
(def n-pts (count (:points base-shape)))
(def gabbia-shape (shape-offset base-shape spessore-gabbia))

(defn forma-factor [t]
  (+ 0.3 (* 0.7 (cos (* t PI 0.5)))))

;; Bowl: shell of base-shape scaled by forma-factor
(defn make-forma [z0 z1]
  (fn [shape t]
    (let [z (+ z0 (* t (- z1 z0)))]
      (scale shape (forma-factor (/ z H))))))

;; Tray: same scaling, then offset inward by (spessore + tolleranza)
;; Guarantees: tray outer wall = bowl outer wall - (spessore + tolleranza)
;;             gap between bowl inner and tray outer = tolleranza
(defn make-forma-tray [z0 z1]
  (fn [shape t]
    (let [z (+ z0 (* t (- z1 z0)))
          scaled (scale shape (forma-factor (/ z H)))
          inset (shape-offset scaled (neg (+ spessore tolleranza)))]
      (resample inset n-pts))))

;; Bowl = shell of base-shape, full height
(def _bowl
  (turtle
   (loft (shell (shape-fn base-shape (make-forma 0 H))
                :thickness spessore
                :fn (fn [a t] 1.0)
                :cap-top spessore)
         (f H))))
(register bowl _bowl)

;; Tray = shell of base-shape offset inward, tray height
(def _tray
  (turtle
   (loft (shell (shape-fn base-shape (make-forma-tray 0 tray-H))
                :thickness spessore
                :fn (fn [a t] 1.0)
                :cap-top spessore)
         (f tray-H))))
(register tray _tray)

(def max-gabbia 500)
(def sz-gabbia 2)
(def step-gabbia 20)
(def r-gabbia (/ max-gabbia step-gabbia))
(def o-gabbia (/ r-gabbia 2))


(def gabbia-raw-h
  (concat-meshes (map #(attach (box sz-gabbia max-gabbia max-gabbia) (rt (* (- % o-gabbia) step-gabbia))) (range r-gabbia))))
;(register G1 gabbia-raw-h)
(def gabbia-raw-v
  (concat-meshes (map #(attach (box max-gabbia sz-gabbia max-gabbia) (u (* (- % o-gabbia) step-gabbia))) (range r-gabbia))))
;(register G2 gabbia-raw-v)


(def G0 (mesh-intersection
         (loft (shape-fn gabbia-shape (make-forma 0 (* H 0.7)))
               (f H))
         (mesh-union gabbia-raw-h gabbia-raw-v)))

(def bowl-solid
  (loft (shape-fn base-shape (make-forma 0 H))
        (f H)))

(def gabbia (mesh-difference G0 bowl-solid))

(register G gabbia)

(tv 90)
(stamp (slice-mesh bowl))
(stamp (slice-mesh tray) :color 0xff0000)

(tv -90)
(f 10)
(stamp (slice-mesh bowl))
(stamp (slice-mesh tray) :color 0xff0000)

(color :G 0)
(color :bowl 0xffff00)
(color :tray 0x888888)
;(hide :bowl)
;(hide :tray)
;(register A bordo-tray)
(hide :G)
(hide :bowl)
(hide :tray)
(register I (mesh-intersection _tray _bowl))
