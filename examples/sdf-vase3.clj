(resolution :n 512)
(def n 4)
(def base-side 60)
(def off 15.9)
(def period (/ base-side n))

(def spessore 4)
(def H 90)

(register basket
          (sdf-difference
           (sdf-intersection
            (sdf-rounded-box base-side base-side H 4)
            (sdf-bar-cage base-side base-side H 9 1.5 :blend 1.2))
           ;; Hollow interior (open at top)
           (sdf-move (sdf-rounded-box 56 56 100 4) 0 0 7)))