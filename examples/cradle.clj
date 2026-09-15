;; ============================================================================
;; THE CAGE STAND
;; ============================================================================
;;
;; The support the registration cage rests on while you photograph it (see
;; examples/print-cage.clj for the cage itself, and chapter 20 of the manual).
;; Two printed parts:
;;
;;   Base    a turned pedestal with a vertical hole in the middle;
;;   Holder  an arch that follows one ring of the cage, with two clips at each
;;           end that grab the ring from both faces, and a pin underneath that
;;           slides into the pedestal.
;;
;; You choose WHICH ring to clip and WHERE along it, and the whole cage turns
;; on the pedestal: any face of the cage, back and underside included, comes
;; round to the camera without touching the part. Nothing here is read from
;; the cage model: the arch radius and the clip gap are stated below, so keep
;; `cage-d` and `ring-h` equal to the cage you printed.
;;
;; HOW TO USE IT: evaluate the file, then export the two registered meshes
;; (Base and Holder). Print the pedestal hole-up and the holder lying on its
;; arch, no supports needed.

;; --- Parameters ---------------------------------------------------------------

(def cage-d 176)          ; diameter over the largest ring of the cage
(def ring-h 3)            ; ring thickness: the clip gap is built around it
(def clearance 0.4)       ; play on each face of the ring inside the clip
(def clip-t 2)            ; thickness of each clip arm
(def bar-h (+ ring-h clip-t clip-t clearance clearance))   ; height of the arch bar
(def clip-offset (- (/ bar-h 2) (/ clip-t 2)))              ; each arm, off the bar's midplane
(def pin-r 4)             ; the pin under the arch, and the hole in the pedestal
(def pin-len 70)
(def hole-clearance 0.3)

;; --- The holder ---------------------------------------------------------------

(defn clip
  "One clip arm: `side` is +1/-1 (which face of the ring it hugs), `end` is
   +1/-1 (which end of the arch it sits at). The arm reaches 20mm past the
   arch so the ring is held well beyond the bar."
  [side end]
  (attach (box clip-t 20 5) (cp-f (* end 2.5)) (cp-u 10) (tr 90) (rt (* side clip-offset))))

(register Holder
  (mesh-union
    ;; the arch: 60 degrees of the ring's own circle
    (extrude (rect 10 bar-h) (arc-h (/ cage-d 2) 60))
    ;; four clip arms: two at the start of the arch, two at its end
    (concat-meshes (for [side [1 -1]
                         at-start [true false]]
                     (if at-start
                       (clip side -1)
                       (turtle (arc-h (/ cage-d 2) 60) (clip side 1)))))
    ;; the pin, at the middle of the arch, pointing down into the pedestal
    (attach (cyl pin-r pin-len) (arc-h (/ cage-d 2) 30) (cp-f (/ pin-len 2)) (th 90))))

;; --- The base -----------------------------------------------------------------

(def base-profile
  "Half section of the pedestal, revolved below. Closed path: the flat foot,
   a small fillet, the flared flank, the top."
  (path-2d :closed
    (f 50)
    (bezier-to [0 4.35 1.29] [0 0 1.04] [0 3.11 8.59] :local)
    (bezier-to [0 -43.1 47.09] [0 0 22.26] [0 -44.64 33.04] :local)
    (f 13)))

(def base-shape (path-to-shape base-profile))

(register Base
  (attach
    (mesh-difference
      (revolve base-shape 360 :pivot :left)
      ;; the hole for the pin: a touch wider, so the holder turns freely
      (attach (cyl (+ pin-r hole-clearance) 50)
        (tv 90) (f 40)))
    (rt -90)))
