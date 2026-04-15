(def d-viti-h 113.5)
(def d-viti-v 85)
(def frontgap 10)
(def frame-h (+ 20 d-viti-h))
(def frame-l (+ 20 d-viti-v))
(def border 0)
(def tolerance 0.3)
(def frame (rect (+ frame-l (* 2 tolerance)) (+ frame-h (* 2 tolerance))))
(def total-l (+ frame-l border frontgap))
(def total-h (+ frame-h border))
(def bigframe (rect (+ total-l (* 2 tolerance)) (+ total-h (* 2 tolerance))))
;(stamp bigframe)
(def altezzavite 12)
(def altezzafondo 2)
(def altezzacornice 15)
(def spessore 2)
(def fondo (extrude (fillet-shape bigframe 5) (f altezzafondo)))
(def shape-pareti (fillet-shape (shape-difference bigframe (shape-offset bigframe (- spessore))) 5))
(def pareti (extrude shape-pareti (f altezzacornice)))
(def buco (extrude (circle 1) (f 10)))
(def supporto-vite (mesh-difference
                    (extrude (rect 20 20) (f 10))
                    buco))
(def buco-cavi (extrude (rect 20 (/ d-viti-h 2)) (f 13)))


(def viti (mesh-union (for [x [-1 1] y [-1 1]]
                        (attach supporto-vite (u (* x (/ d-viti-h 2))) (rt (* y (/ d-viti-v 2)))))))

(def tilt 30)
(println "bounds pareti: " (bounds pareti))
(print "total-h: " total-h)
;(println "bounds shape-pareti:" (bounds shape-pareti))


(register scivolo
          (lay-flat
           (mesh-difference
            (mesh-union
             viti
             fondo
             pareti
             (turtle (th 180)
                     (transform-> shape-pareti
                                  (revolve+ tilt :pivot :left)
                                  (extrude+ (f 30) :mark :base :end-cap))))
            (attach buco-cavi (f 2) (rt (/ frame-l 2))))
           :base))

;(def s (shape-difference (rect 40 40) (rect 30 30)))

;(def topcover-shape (fillet-shape bigframe 5))
;(register topcover (attach (extrude topcover-shape 2) (u 30))) 

