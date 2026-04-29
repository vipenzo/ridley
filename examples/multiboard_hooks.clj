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

(resolution :n 64)

(defn reggibottiglia [diam H spessore p-pos]
  (mesh-difference
   (mesh-union
    (box (+ diam 15) (+ diam 15) H)
    (concat-meshes (for [x p-pos]
                     (attach perno (path (tr 90) (rt (/ diam 2)) (u (* x 25)))))))

   (attach (cyl (/ diam 2) (* diam 3)) (tv 30))))
(register C (attach (reggibottiglia 91 40 3 [-1.5 1.5]) (f 60)))
;(register A (attach P20 (f 30)))
;(register B(attach P40 (f -30)))  
(register D (reggibottiglia 35 20 3 [0]))
