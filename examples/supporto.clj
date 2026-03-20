;; Pipe clamp for 30mm wooden poles (garden trellis)
;; Square base plate + open C-ring clamp

;; --- Parameters ---
(def pipe-d 30) ; tube outer diameter (mm)
(def wall 1) ; wall thickness
(def base-side 50) ; base plate size
(def base-h 3) ; base plate height
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

;; Countersink: capped widens the hole at the top
(def csink-hole
  (loft (capped hole -2.5 :start false :fraction 0.5) (f (+ base-h 1))))

(def base-shape
  (shape-difference
   (fillet-shape (rect base-side base-side) corner-r)
   (circle ring-r 48)))

(def base-mesh
  (mesh-difference
   (extrude base-shape (f base-h))
   (concat-meshes
    (attach csink-hole (u si) (rt si))
    (attach csink-hole (u si) (rt (neg si)))
    (attach csink-hole (u (neg si)) (rt si))
    (attach csink-hole (u (neg si)) (rt (neg si))))))

;; --- Clamp ring ---
(def ring-section
  (shape-difference
   (circle (+ ring-r wall) 48)
   (circle ring-r 48)))

(def clamp-mesh
  (loft (capped ring-section -7 :end false :fraction 0.4) (f clamp-length)))

;; --- Assembly ---
(register support (mesh-union [base-mesh (attach clamp-mesh (f (- base-h 1)))]))
