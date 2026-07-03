;; ══════════════════════════════════════════════════════════════
;; move-to :from / :mate — anchor-on-anchor mating demo
;; ══════════════════════════════════════════════════════════════
;;
;; Two parts, each with a NAMED mating anchor. :from elects the anchor on the
;; MOBILE part; :mate presses the two faces together (destination frame ∘ th 180:
;; faces opposed, "up" kept concordant). A 0.15 FDM clearance is opened with a
;; chained (f -0.15) along the plug's own normal.

;; ── SOCKET: a 30×30×8 base plate ──────────────────────────────
;; :mouth sits on the +x end face, heading = +x (the outward face normal).
(register socket
          (extrude (rect 30 30) (f 8)))
(attach-path :socket
             (path (f 8)              ; walk from the creation-pose to the +x end face
                   (mark :mouth)))    ; heading +x, up +z

;; ── PLUG: a smaller 18×18×10 block ────────────────────────────
;; :key sits on its base face; (th 180) turns the mark to face -x, so :key's
;; heading is the plug's OWN outward mating normal.
(register plug
          (extrude (rect 18 18) (f 10)))
(attach-path :plug
             (path (th 180)           ; face -x: the plug's mating-face normal
                   (mark :key)))      ; heading -x, up +z, at the base cap

;; ── MATE ──────────────────────────────────────────────────────
;; Land :key onto :mouth, faces opposed (heading -mouth), up-on-up, then back
;; off 0.15 to open the print gap. The plug ends up centred on the socket face.
(attach! :plug
         (move-to :socket :at :mouth :from :key :mate)
         (f -0.15))

;; ── Try these variants (replace the move-to above) ────────────
;;
;; Pure translation (no rotation) — just drops :key's position onto :mouth:
;;   (move-to :socket :at :mouth :from :key)
;;
;; :align instead of :mate — frames aligned, NOT opposed (same-facing):
;;   (move-to :socket :at :mouth :from :key :align)
;;
;; Residual spin about the shared mating normal (add after the move-to):
;;   (move-to :socket :at :mouth :from :key :mate) (tr 30)
;;
;; Compenetrate instead of clearance: (f 0.15) instead of (f -0.15).
