(def tolleranza 0.3)
(def spessore 2.5)
(def H 120)
(def base-side 100)
(def round-radius 6)

(def coso push-fit-vertical-positive/push-fit-vertical-positive)

;; SDF rounded box shell — open at top (Z > 0 = open side)
(def inner-box (- base-side (* 2 round-radius)))
(def inner-h (- H (* 2 round-radius)))

(def container
  (sdf-difference
   ;; Hollow rounded box
   (sdf-shell
    (sdf-offset (sdf-box inner-box inner-h inner-box) round-radius)
    spessore)
   ;; Cut off top to leave it open
   (sdf-move (sdf-box (+ base-side 10) (* 2 round-radius) (+ base-side 10)) 0 0 (/ H 2))))

(comment
  ;; Gyroid-perforated walls + solid bottom rim for rigidity
  (def gyroid-period 12)
  (def gyroid-thickness 1.5)

  (def _glass
    (sdf-union
     ;; Perforated walls
     (sdf-intersection container (sdf-gyroid gyroid-period gyroid-thickness))

     ;; Solid bottom rim (a band at the base for strength)
     (sdf-intersection container
                       (sdf-move (sdf-box
                                  (+ base-side 10) (* spessore 3) (+ base-side 10))
                                 0 0 (- (/ spessore 2) (/ H 2))))
     (sdf-intersection container
                       (sdf-move (sdf-box
                                  (+ base-side 10) (* spessore 3) (+ base-side 10))
                                 0 0 (- (/ H 2) round-radius))))))


(def slat-w 1.9)
(def slat-pitch 6)
(def slat-h (- H (* 4 round-radius)))
(def slat-len (+ base-side 20))
(def n-slats (int (/ base-side slat-pitch)))

(def slats
  (let [half (/ (* (dec n-slats) slat-pitch) 2)
        ;; box thin in Y, tall in Z, lungo in X → taglia entrambe le pareti X
        base-slat (sdf-box slat-w slat-h slat-len)]
    (apply sdf-union
           (concat
            ;; serie 1: array lungo Y, taglia le pareti X
            (for [i (range n-slats)]
              (sdf-move base-slat 0 (+ (- half) (* i slat-pitch)) 0))
            ;; serie 2: ruotata 90° attorno a Z, array lungo X, taglia le pareti Y
            (for [i (range n-slats)]
              (sdf-move (sdf-rotate base-slat :z 90)
                        (+ (- half) (* i slat-pitch)) 0 0))))))

(def _glass (sdf-difference container slats))

(def manici (concat-meshes
             (for [x [-1 1] y [1 -1]]
               (attach coso
                       (f (- (/ base-side 2) 0.5))
                       (tv -90)
                       (rt (* 25 x))
                       (f (* 25 y))))))
;; Assemble with push-fit attachments
(def glass
  (mesh-union
   _glass
   manici))

(register Glass (attach glass (u (- (get-in (bounds glass) [:min 2])))))
;(register AAA _glass)
;(register BBB manici)

(color :Glass 0xffffcc)

