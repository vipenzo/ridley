; === Twisted Fluted Vase ===
;
; A parametric vase that showcases shape-fn composition.
;
; The profile is a circle that gets fluted (scalloped edges),
; twisted along the height, and tapered to create a neck.
; The taper function uses a custom curve: it widens at the belly,
; narrows at the neck, and flares slightly at the lip.
;
; Demonstrates:
; - Shape-fn threading with -> (compose multiple transformations)
; - fluted: adds radial undulations to the cross-section
; - twisted: rotates the profile progressively along the path
; - shape-fn with custom (fn [shape t] ...) for non-linear taper
; - loft-n for high-resolution extrusion
;
; Try changing:
; - n-flutes for more or fewer scallops
; - twist-amount for tighter or looser spiral
; - The taper curve coefficients for different silhouettes

(def n-flutes 12)
(def twist-amount 90)
(def height 80)
(def base-radius 20)

; Custom taper: wide belly, narrow neck, flared lip
(def vase
  (loft-n 96
          (-> (circle base-radius 64)
              (fluted :flutes n-flutes :depth 0.15)
              (twisted :angle twist-amount)
              (shape-fn (fn [shape t]
                          (let [; Belly peaks around t=0.35
                                belly (+ 1.0 (* 0.3 (sin (* t PI))))
                                neck (max 0.1 (- 1.0 (* 0.25 (pow (max 0 (- t 0.6)) 2) 40)))
                                lip (if (> t 0.9) (* 0.85 (- t 0.9) 10) 0)
                                s (+ (* belly neck) lip)]
                            (scale-shape shape s s)))))
          (f height)))

(register vase vase)
