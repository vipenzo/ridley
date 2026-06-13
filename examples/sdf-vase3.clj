;; Basket vase — a rounded box intersected with a bar cage and hollowed,
;; open at the top.
;; Requires the Rust geometry server (Tauri desktop mode).

(resolution :n 512)
(def base-side 60)
(def H 90)

(register basket
          (sdf-difference
           (sdf-intersection
            (sdf-rounded-box base-side base-side H 4)
            (sdf-bar-cage base-side base-side H 9 1.5 :blend 1.2))
           ;; Hollow interior (open at top)
           (translate (sdf-rounded-box 56 56 100 4) 0 0 7)))