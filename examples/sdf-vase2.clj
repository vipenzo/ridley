;; Bars vase — a rounded box carved into a lattice of blended X/Y/Z bars,
;; capped top and bottom and hollowed out.
;; Requires the Rust geometry server (Tauri desktop mode).

(resolution :n 512)
(def n 4)
(def base-side 60)
(def off 15.9)
(def period (/ base-side n))

(def spessore 4)
(def H 90)

(def solid (sdf-rounded-box base-side base-side H 6))
(def interno (translate
              (sdf-rounded-box (- base-side spessore) (- base-side spessore) (+ H (* spessore 3)) 6)
              0 0 spessore))
(def cap1 (translate
           (sdf-rounded-box base-side base-side 10 6)
           0 0 (/ H 2)))
(def cap2 (translate
           (sdf-rounded-box base-side base-side 10 6)
           0 0 (- (/ H 2))))

(def raggio-tubi (/ spessore 2.5))
(defn cut [m]
  (sdf-intersection
   m
   solid))

(def bx (cut (sdf-bars :x period raggio-tubi off off)))
(def by (cut (sdf-bars :y period raggio-tubi off off)))
(def bz (cut (sdf-bars :z period raggio-tubi off off)))
(def bars (sdf-blend (sdf-blend bx by 2) bz 2))
(def vase
  (sdf-difference
   (sdf-blend
    (sdf-blend bars cap1 2)
    cap2
    2)
   interno))

(register bars-vase vase)

