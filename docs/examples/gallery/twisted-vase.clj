;; Twisted Fluted Vase
;; Shape-fn composition with the -> macro: fluted + twisted + a custom
;; non-linear taper (belly that widens, neck that narrows, flaring lip).
;; Try changing: n-flutes, twist-amount, the taper curve coefficients.

(def n-flutes 12)
(def twist-amount 90)
(def height 80)
(def base-radius 20)

(def vase
  (loft-n 96
    (-> (circle base-radius 64)
        (fluted :flutes n-flutes :depth 0.15)
        (twisted :angle twist-amount)
        (shape-fn (fn [shape t]
                    (let [belly (+ 1.0 (* 0.3 (sin (* t PI))))
                          neck (max 0.15 (- 1.0 (* 0.25 (pow (max 0 (- t 0.6)) 2) 40)))
                          lip (if (> t 0.85)
                                (+ 1.0 (* 90 (pow (- t 0.85) 2)))
                                1.0)
                          s (* belly neck lip)]
                      (scale-shape shape s s)))))
    (f height)))

(register vase vase)
