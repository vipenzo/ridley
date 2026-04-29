(def hook mb_hook/mb_hook)
(def perno (mesh-intersection hook (attach (box 20) (path (rt 5) (f -5) (scale [1 1.4641000000000006 1]) (rt 5)))))
(def l10 (mesh-intersection perno (attach (box 10) (path (rt 5) (f -5) (scale [1.610510000000001 1 1.610510000000001]) (f -5) (rt 5) (f 10) (scale [1.4641000000000006 1 1.6105100000000008]) (f -5)))))
(def P40
  (mesh-union perno
              (attach l10 (rt -19))
              (attach l10 (rt -29))
              (attach (box 20) (path (rt -15)
                                     (scale [1.2 0.13 2.5])
                                     (f -5)
                                     (rt -9)
                                     (u 18)))))

(def P20
  (mesh-union perno
              (attach (box 20) (path (rt -15)
                                     (scale [1.2 0.13 2.5])
                                     (f -5)
                                     (rt 9)
                                     (u 18)))))

(def diametro 91)
(def H 40)
(def spessore 3)
(resolution :n 64)
(def reggibottiglia (mesh-union
                     (mesh-difference
                      (box 110 110 H)
                      (attach (cyl (/ diametro 2) diametro) (tv 30)))
                     (attach perno (path (tr 90) (rt 47) (u (* 1.5 25))))
                     (attach perno (path (tr 90) (rt 47) (u (* 1.5 -25))))))
(register C reggibottiglia)
  ;(register A (attach P20 (f 30)))
  ;(register B(attach P40 (f -30)))  