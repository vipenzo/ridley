;; SDF via SCI — test all major operations
;; Requires the Rust geometry server (Tauri desktop mode)

;; Basic primitives
(register sphere (sdf-sphere 10))

;; Boolean blend (smooth union)
(register blend
          (sdf-blend
           (sdf-sphere 8)
           (sdf-move (sdf-box 12 12 12) 6 0 0)
           0.5))

;; Shell + TPMS infill
(register infill
          (sdf-intersection
           (sdf-sphere 15)
           (sdf-gyroid 6 0.4)))

;; Morph between two shapes
(register morph
          (sdf-morph
           (sdf-sphere 10)
           (sdf-box 16 16 16)
           0.5))

;; Custom formula displacement
(register wave
          (sdf-intersection
           (sdf-formula '(- z (* 2 (sin (* x 0.5)) (cos (* y 0.5)))))
           (sdf-box 30 30 10)))
