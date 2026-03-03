; === Meshing Gears — Animated ===
;
; Two interlocking spur gears rotating in sync.
; Uses gear-pair for automatic meshing geometry
; and anim! for continuous looped rotation.
;
; The large gear drives the small one at a 2:1 ratio:
; for every revolution of gear1, gear2 spins twice.

; =====================
; 1. Create the gear pair
; =====================

(def gp (gear-pair :ratio 2
                   :size 60
                   :thickness 8
                   :tolerance :fdm
                   :bore 5))

; =====================
; 2. Register meshes
; =====================

; Large gear — centered at origin
(register gear1 (:gear1 gp))

; Small gear — offset by meshing distance, phase-aligned
(register gear2
          (attach (:gear2 gp)
                  (rt (:distance gp))    ; translate along Y
                  (tr (:phase gp))))                     ; phase offset for teeth alignment

; =====================
; 3. Animate rotations
; =====================

; Large gear: one full turn in 4 seconds
(anim! :spin1 4.0 :gear1 :loop
  (span 1.0 :linear (tr 360)))

; Small gear: counter-rotate at ratio speed
; Negative because meshing gears turn in opposite directions
(anim! :spin2 4.0 :gear2 :loop
  (span 1.0 :linear (tr (* -1 (:ratio gp) 360))))

; =====================
; 4. Start!
; =====================

(play!)
