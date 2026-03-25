;; WORK IN PROGRESS

;; ── Parametri fondamentali ──────────────────────────────────────
(def cell-size 25)
(def height 6.4)

(def side-l (/ cell-size (+ 1 (* 2 (cos (/ PI 4))))))
(def size-offset (* 0.5 (- cell-size side-l)))

;; ── Forma base della cella (quadrato con angoli tagliati) ───────
;; Traduzione diretta dei base_points di OpenSCAD
(def cell-shape
  (poly (- cell-size size-offset) cell-size
        size-offset               cell-size
        0                         (- cell-size size-offset)
        0                         size-offset
        size-offset               0
        (- cell-size size-offset) 0
        cell-size                 size-offset
        cell-size                 (- cell-size size-offset)))

;; ── Multihole ───────────────────────────────────────────────────
(def hole-thick      3.6)
(def hole-thin       1.6)
(def hole-thick-height 2.4)

(def hole-thick-size (- cell-size hole-thick))
(def hole-thin-size  (- cell-size hole-thin))

(def hole-thick-side-l (/ hole-thick-size (+ 1 (* 2 (cos (/ PI 4))))))
(def hole-thin-side-l  (/ hole-thin-size  (+ 1 (* 2 (cos (/ PI 4))))))

(def hole-thick-r (/ hole-thick-side-l (* 2 (sin (/ PI 8)))))
(def hole-thin-r  (/ hole-thin-side-l  (* 2 (sin (/ PI 8)))))

(def h1 (/ (- height hole-thick-height) 2))
(def h2 (/ (+ height hole-thick-height) 2))

(defn lerp [a b t] (+ a (* t (- b a))))

(defn hole-r-at [z]
  (cond
    (< z h1) (lerp hole-thin-r hole-thick-r (/ z h1))
    (< z h2) hole-thick-r
    :else    (lerp hole-thick-r hole-thin-r (/ (- z h2) (- height h2)))))

(def multihole
  (loft (circle 1 8)
        #(scale (rotate-shape %1 22.5)
                (hole-r-at (* %2 height)))
        (f height)))

;; multihole va a [12.5, 12.5] nel piano della cella
;; right = X della shape, up = Y della shape
(def multihole-positioned
  (attach multihole
          (rt (/ cell-size 2))
          (u (/ cell-size 2))))

(def cell
  (mesh-difference
   (-> cell-shape (extrude (f height)))
   multihole-positioned))

(register Cell cell)
(register MH multihole-positioned)