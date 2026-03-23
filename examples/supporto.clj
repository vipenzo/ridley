;; Pipe clamp for 30mm wooden poles (garden trellis)
;; Square base plate + open C-ring clamp

;; --- Parameters ---
(def pipe-d 30) ; tube outer diameter (mm)
(def wall 3) ; wall thickness
(def base-side 50) ; base plate size
(def base-h 5) ; base plate height
(def screw-d 5) ; screw hole diameter
(def screw-inset 5) ; hole distance from edge
(def corner-r 4) ; base plate corner radius
(def clamp-length 25)

;; Derived
(def ring-r (/ pipe-d 2)) ; inner ring radius
(defn neg [x] (* -1 x))

;; --- Base plate ---
(def hole (circle (/ screw-d 2) 16))
(def si (- (/ base-side 2) screw-inset))

(def base-shape
  (shape-difference
   (fillet-shape (rect base-side base-side) corner-r)
   (circle ring-r 48)))

(def base (-> base-shape
              (extrude (f base-h))
              (chamfer :top 1.5 :min-radius ring-r)))

(def screw-hole
  (loft (capped hole -1.5 :start false :fraction 0.4) (f (+ base-h 1))))

(def base-mesh
  (mesh-difference
   base
   (concat-meshes
    (for [x [1 -1] y [1 -1]]
      (attach screw-hole (u (* si x)) (rt (* si y)))))))

;; --- Clamp ring ---
(def ring-section
  (shape-difference
   (circle (+ ring-r wall) 48)
   (circle ring-r 48)))

(def clamp-mesh
  (-> (loft (capped ring-section -7 :end false :fraction 0.4) (f clamp-length))
      (chamfer :top wall :min-radius ring-r)))

;; --- Assembly ---
(register support (mesh-union [base-mesh (attach clamp-mesh (f (- base-h 1)))]))
