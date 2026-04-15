(register wave
          (sdf-intersection
           (sdf-formula (- z (* 2 (sin (* x 0.5)) (cos (* y 0.5)))))
           (sdf-box 30 30 10)))

;;;;;;;;;;
(defn mk-formula []
  (quote (- z (* 2 (sin (* x 0.5)) (cos (* y 0.5))))))

(register wave
          (sdf-intersection
           (sdf-formula (mk-formula))
           (sdf-box 30 30 10)))

;;;;;;;;;;;;
(defn wave [freq amp]
  (list '- 'z (list '* amp (list 'sin (list '* 'x freq)))))
(sdf-formula (wave 0.5 2))
;;;;;;;;;;;;;

(register morph (sdf-intersection
                 (sdf-shell
                  (sdf-morph
                   (sdf-sphere 10)
                   (attach (sdf-box 16 16 16) (f 14))
                   0.5)
                  1)
                 (sdf-box 22 20 20))) 

;;;;;;;;;;;;

 (register infill
          (sdf-intersection
           (sdf-sphere 20)
           (sdf-gyroid 8 0.5)))