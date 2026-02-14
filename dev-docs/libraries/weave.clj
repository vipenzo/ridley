

(defn canvas [thickness compactness]
  (let [step121 (* thickness 1.1314) ;; aggiustato per tiling
        spacing (* thickness 1.8)
        wave (+ (* 2 thickness) (* 2 step121 (cos (/ PI 4))))
        n-strands 6 ;; più del necessario
        n-waves 4 ;; più cicli per avere bordi puliti

        make-strands (fn [n]
                       (concat (for [i (range n)]
                                 (let [[g ud] (if (odd? i) [180 0] [0 (* -0.5 thickness)])
                                       pat (path (dotimes [_ n-waves]
                                                   (f thickness) (tv 45) (f step121) (tv -45)
                                                   (f thickness) (tv -45) (f step121) (tv 45)))]
                                   (attach (loft (circle (* thickness compactness)) identity
                                                 (bezier-as pat :tension 0.3))
                                           (lt (* i spacing)) (tr g) (u ud))))))

        m1 (concat-meshes (make-strands n-strands))
        m2 (attach m1 (tr 180)
                   (th 90)
                   (rt -1)
                   (f 1)
                   (d (* thickness compactness 0.5)))
        weave (concat-meshes [m1 m2])

        ;; Campiona 2x2 tile dal centro (evita le cap ai bordi)
        b (bounds weave)
        center-x (* 0.5 (+ (get-in b [:min 0]) (get-in b [:max 0])))
        center-y (* 0.5 (+ (get-in b [:min 1]) (get-in b [:max 1])))
        tile-x (* 2 wave) ;; 2 periodi d'onda
        tile-y (* 2 wave) ;; simmetrico per la rotazione 90°

        hm (mesh-to-heightmap weave :resolution 256
                              :offset-x (- center-x (* 0.5 tile-x))
                              :offset-y (- center-y (* 0.5 tile-y))
                              :length-x tile-x
                              :length-y tile-y)]
    [weave hm]))


(def weave-hm (canvas 1.5 0.5))
;(register W (weave-hm 0))

;(register debug-hm (attach (heightmap-to-mesh (weave-hm 1)) (u 30)))

(register AA
          (attach
           (loft-n 128 (heightmap (circle 20 256) (weave-hm 1) :amplitude 4 :center true :tile-x 5 :tile-y 5) (f 100))
           (d 50)))

