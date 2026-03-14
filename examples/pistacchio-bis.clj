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
(def s1 (translate (circle b) bb (* aa 0.1)))
(def s2 (circle b2))
(def s3 (translate (circle b4) bb (* aa -0.2)))
(def spessore-gabbia 8)


(def base-shape (shape-hull s3 s1 s2))
(def tray-shape
  (shape-difference
   (shape-offset base-shape (neg (+ tolleranza spessore)))
   (scale-shape s2 1.1)))

(def gabbia-shape (shape-offset base-shape spessore-gabbia))



(def bordo
  (shape-offset tray-shape (+ tolleranza spessore)))
(def bordo-tray-shape
  (shape-difference
   (shape-offset bordo (neg tolleranza))
   (shape-offset bordo (neg (+ spessore tolleranza)))))
(stamp bordo-tray-shape)

(def bordo-bowl-shape (shape-offset bordo tolleranza))

(def bordo-tray (extrude bordo-tray-shape (f spessore)))
(def bordo-bowl (extrude bordo-bowl-shape (f (+ spessore tolleranza))))

;(stamp base-shape)
;(stamp s1)
;(stamp s2)
;(stamp s3)


(defn forma-factor [t]
  (+ 0.3 (* 0.7 (cos (* t PI 0.5)))))

(defn forma-factor [t]
  (+ 0.3 (* 0.7 (cos (* t PI 0.5)))))


;(stamp tray-shape :color 0x00ff00)
;(stamp base-shape :color 0x00ffff)

(defn make-forma [z0 z1]
  (fn [shape t]
    (let [z (+ z0 (* t (- z1 z0)))]
      (scale shape (forma-factor (/ z H))))))




;(register bowl (mesh-difference body buco))
(defn conchiglia [start-shape h]
  (turtle
   (let [walls (loft
                (shell
                 (shape-fn start-shape (make-forma 0 h))
                 :thickness spessore
                 :fn (fn [a t] 1.0))
                (f h))
         _ (f (- h spessore))
         cap (loft (shape-fn start-shape (make-forma (- h spessore) h))
                   (f spessore))]
     (mesh-union walls cap))))


(def _bowl (mesh-difference
            (conchiglia base-shape H)
            bordo-bowl))
(register bowl _bowl)
(register tray (mesh-union
                (conchiglia tray-shape tray-H)
                bordo-tray))

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

(def gabbia (mesh-difference G0 _bowl))

(register G gabbia)

(tv 90)
(stamp (slice-mesh bowl))
(stamp (slice-mesh tray) :color 0xff0000)

(tv -90)
(f 10)
(stamp (slice-mesh bowl))
(stamp (slice-mesh tray) :color 0xff0000)


(hide :bowl)
(hide :tray)
;(register A bordo-tray)
