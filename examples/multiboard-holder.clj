(def tolleranza 0.3)
(def spessore 2.5)
(def H 90)
(def base-side 60)
(def round-radius 6)

(def coso push-fit-vertical-positive/push-fit-vertical-positive)

;; SDF rounded box shell — open at top (Z > 0 = open side)
(def inner-box (- base-side (* 2 round-radius)))
(def inner-h   (- H (* 2 round-radius)))

(def container
  (sdf-difference
    ;; Hollow rounded box
   (sdf-shell
    (sdf-offset (sdf-box inner-box inner-box inner-h) round-radius)
    spessore)
    ;; Cut off top to leave it open
   (sdf-move (sdf-box (+ base-side 10) (+ base-side 10) H) 0 0 (/ H 2))))

;; Gyroid-perforated walls + solid bottom rim for rigidity
(def gyroid-period 12)
(def gyroid-thickness 1.5)

(def _glass
  (sdf-union
    ;; Perforated walls
   (sdf-intersection container (sdf-gyroid gyroid-period gyroid-thickness))
    ;; Solid bottom rim (a band at the base for strength)
   (sdf-intersection container
                     (sdf-move (sdf-box (+ base-side 10) (+ base-side 10) (* spessore 3))
                               0 0 (- (/ spessore 3) (/ H 2))))))

;; Assemble with push-fit attachments
(def __glass
  (attach
   (mesh-union
    (attach _glass (tv -90) (f (- H)))
    (for [x [-1 1] y [-1 -3]]
      (attach coso (f (+ round-radius (/ base-side 2))) (tv -90) (rt (* 25 x)) (f (* 25 y)))))
   (tv 90) (u (/ H -2))))

(register glass (attach __glass (f (- (get-in (bounds __glass) [:min 2])))))

(color :glass 0xff8888)

