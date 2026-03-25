;; Pipe clamp for 30mm wooden poles (garden trellis)
;; Square base plate + open C-ring clamp
;; Tests: fillet-shape (2D corners) and capped (3D cap fillet on loft)

;; --- Parameters ---
(def pipe-d 30)            ; tube outer diameter (mm)
(def wall 3)               ; wall thickness
(def base-side 50)         ; base plate size
(def base-h 5)             ; base plate height
(def screw-d 5)            ; screw hole diameter
(def screw-inset 5)        ; hole distance from edge
(def corner-r 4)           ; base plate corner radius
(def ring-open-deg 60)     ; opening angle at top

;; Derived
(def ring-r (/ pipe-d 2))  ; inner ring radius
(def ring-angle (- 360 ring-open-deg))
(defn neg [x] (* -1 x))

;; --- Base plate ---
(def base-shape (fillet-shape (rect base-side base-side) corner-r))
(def hole (circle (/ screw-d 2) 16))
(def si (- (/ base-side 2) screw-inset))

(def base-mesh
  (mesh-difference
    (extrude base-shape (f base-h))
    (concat-meshes
      (attach (extrude hole (f (+ base-h 1))) (u si) (rt si))
      (attach (extrude hole (f (+ base-h 1))) (u si) (rt (neg si)))
      (attach (extrude hole (f (+ base-h 1))) (u (neg si)) (rt si))
      (attach (extrude hole (f (+ base-h 1))) (u (neg si)) (rt (neg si))))))
(register base base-mesh)

;; --- Clamp ring ---
;; Cross-section: annulus (hollow circle)
(def ring-section
  (shape-difference
    (circle (+ ring-r wall) 48)
    (circle ring-r 48)))

;; Clamp: loft ring section along a circular arc path
;; Start on top of base, arc upward and around
(def clamp-mesh
  (turtle
    (goto 0 0 base-h)
    (tv 90)                                    ; point upward
    (th (/ ring-open-deg 2))                   ; center the gap at top
    (loft (capped ring-section 2)              ; cap fillet at base junction
          (arc-h ring-angle (+ ring-r (/ wall 2))))))
(register clamp clamp-mesh)

;; --- Assembly ---
(register support (mesh-union [base-mesh clamp-mesh]))
(hide :base)
(hide :clamp)
