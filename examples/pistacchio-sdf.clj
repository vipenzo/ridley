;; Profilo 2D del guscio: fascia tra R(y)-wall/2 e R(y)+wall/2, y in [0,H]
;; Cioè: |x - R(y)| < wall/2  AND  0 < y < H
(def wall 3)
(def hw (/ wall 2))

(resolution :n 128)

(def bowl-wall-profile
  (sdf-intersection
   ;; Fascia attorno alla curva: distanza dalla curva < hw
   (sdf-formula (list '- (list 'abs (list '- 'x (list '+ 47 (list '* 23 (list '- 1 (list 'cos (list '* 'y 0.048))))))) hw))
   ;; Limitato in altezza
   (sdf-intersection
    (sdf-formula '(- (- y) 0))
    (sdf-formula '(- y 50)))))

(register bowl (sdf-revolve bowl-wall-profile :x-range [0 75] :y-range [-1 55]))