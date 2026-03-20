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
(def ring-open-deg 60) ; opening angle at top
(def clamp-length 25)

;; Derived
(def ring-r (/ pipe-d 2)) ; inner ring radius
(def ring-angle (- 360 ring-open-deg))

;; --- Base plate ---

(def hole (circle (/ screw-d 2) 16))
(def si (- (/ base-side 2) screw-inset))
(defn neg [x] (* -1 x))

;; Countersink hole: capped widens the top of the hole
(def csink-hole
  (extrude hole (f (+ base-h 1))))

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


;(register base base-mesh)




;; --- Clamp ring ---
(def fillet-r 7)            ; fillet expansion at base
(def fillet-h 10)           ; fillet height
(def chamfer-r 1)           ; chamfer at top
(def chamfer-h 2.5)         ; chamfer height
(def tube-r (+ ring-r wall))

;; Fillet zone: cone from wide to normal
(def fillet-part
  (loft (tapered (circle (+ tube-r fillet-r) 48) :from 1 :to (/ tube-r (+ tube-r fillet-r)))
        (f fillet-h)))

;; Straight tube
(def tube-part
  (attach (extrude (circle tube-r 48) (f (- clamp-length fillet-h chamfer-h)))
          (f fillet-h)))

;; Top chamfer: cone from normal to slightly smaller
(def chamfer-part
  (attach (loft (tapered (circle tube-r 48) :from 1 :to (/ (- tube-r chamfer-r) tube-r))
                (f chamfer-h))
          (f (- clamp-length chamfer-h))))

;; Combine all clamp parts, then bore out the center
(def clamp-solid (mesh-union [fillet-part tube-part chamfer-part]))
(def bore (extrude (circle ring-r 48) (f (+ clamp-length 2))))

(def clamp-hollow
  (mesh-difference
    (attach clamp-solid (f (- base-h 1)))
    (attach bore (f -1))))

(register support (mesh-union [base-mesh clamp-hollow]))

