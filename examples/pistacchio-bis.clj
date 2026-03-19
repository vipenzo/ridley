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
(def s1 (translate (circle b 128) bb (* aa 0.1)))
(def s2 (circle b2 128))
(def s3 (translate (circle b4 128) bb (* aa -0.2)))
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
          inset (shape-offset scaled (neg (+ spessore tolleranza)))
          s (shape-difference inset (scale s2 (forma-factor (/ z H))))]
      (resample s n-pts))))

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
  (loft-n 128 (woven-shell (shape-fn base-shape (make-forma-tray 0 tray-H))
                           :thickness spessore
                           :strands 20
                           :cap-top spessore)
          (f tray-H)))
(register tray _tray)

(register safety-net
          (loft-n 128
                  (shell (circle 20 128) :thickness 1
                         :fn (fn [a t]
                               (let [u (/ (+ a PI) (* 2 PI))
                                     cols 6 rows 16
                                     row-idx (int (floor (* t rows)))
                                     shift (if (odd? row-idx) 0.5 0.0)
                                     fu (mod (+ (* u cols) shift) 1)
                                     fv (mod (* t rows) 1)
                                     mx 0.08 my 0.08 r 0.15
                                     dx (max 0 (- mx (min fu (- 1 fu))))
                                     dy (max 0 (- my (min fv (- 1 fv))))
                                     on-edge? (or (< fu mx) (> fu (- 1 mx))
                                                  (< fv my) (> fv (- 1 my)))
                                     in-corner? (and (> dx 0) (> dy 0)
                                                     (> (sqrt (+ (* dx dx) (* dy dy))) r))]
                                 (if (and on-edge? (not in-corner?)) 1.0 0.0))))
                  (f 60)))

(comment
  (def max-gabbia 500)
  (def sz-gabbia 0.7)
  (def step-gabbia 3)
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
          (f H))))
 ; (def gabbia (mesh-difference G0 bowl-solid))

 ; (register G gabbia))

;(tv 90)
;(stamp (slice-mesh bowl))
;(stamp (slice-mesh tray) :color 0xff0000)

;(tv -90)
;(f 1)
;(stamp (slice-mesh bowl))
;(stamp (slice-mesh tray) :color 0xff0000)

;(color :G 0)
(color :bowl 0xffff00)
(color :tray 0x888888)
;(hide :bowl)
;(hide :tray)
;(register A bordo-tray)
;(hide :G)
;(hide :bowl)
;(hide :tray)
;(register I (mesh-intersection (mesh-union gabbia-raw-h gabbia-raw-v) _tray))
