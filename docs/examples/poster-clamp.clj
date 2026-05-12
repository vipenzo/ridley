(def dist-palette 187)
(def L 300)
(def H 15)
(def D 10)

(def
  palette
  (concat-meshes (for [y [(- (/ dist-palette 2)) (/ dist-palette 2)]]
                   (attach (mesh-difference
                            (box 20 25 3)
                            (attach (cyl 4 20) (u 4)))
                           (tv 90) (u 19) (f (- 1.5 5)) (rt y)))))

(def viti
  (mesh-union
   (for [y [(- 5 (/ L 2)) 0 (- (/ L 2) 5)]]
     (attach
      (mesh-union
       (cyl 1 10)
       (attach (cone 3 1 3) (f 5)))
      (tv 90) (f -1) (rt y) (u -3)))))

(register ClampDown
          (attach (mesh-difference
                   (box L D H)
                   (mesh-union
                    (attach (box (inc L) 1 (inc H)) (f 5))
                    viti)) (f 50) (tv 180) (tr 180)))

(register ClampUp
          (mesh-union
           (mesh-difference
            (box L D H)
            (mesh-union
             (attach (box (inc L) 1 (inc H)) (f 5))
             viti))
           palette))
