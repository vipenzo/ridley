(def tolleranza 0.3)
(def spessore 2.5)
(def H 90)
(def base-side 60)
(def round-radius 6)
(def rounded-rect (shape-offset (rect base-side base-side) round-radius :join-type :round))
(def base-shape (resample rounded-rect 1024))

(def solid
  (sdf-offset (sdf-box (- base-side (* 2 round-radius))
                       (- base-side (* 2 round-radius))
                       (- H (* 2 round-radius)))
              round-radius))

;; Shell, open at bottom (-Z)
(def container
  (sdf-difference
   (sdf-shell solid spessore)
   ;; Cut bottom face open
   (sdf-move (sdf-box (+ base-side 10) (+ base-side 10) (* round-radius 2))
             0 0 (- (/ round-radius 2) (/ H 2)))))

;; Punch gyroid holes through the container (only in the middle band)
(def rim-h (* spessore 3))
(def gyroid-zone
  (sdf-move (sdf-box (+ base-side 10) (+ base-side 10) (- H (* 2 rim-h)))
            0 0 (/ rim-h 2)))

;; Holes = gyroid zone minus gyroid walls
(def gyroid-holes
  (sdf-difference gyroid-zone (sdf-gyroid 12 1.5)))

;; Container with holes punched through
;(register glass (sdf-difference container gyroid-holes))

;; Tre set di lamelle ortogonali blendati
(register glass
  (sdf-difference container (sdf-grid 10 2 0.5)))