(def spessore 2)
(def c (circle 20 512))

(register AA
          (loft-n 512
                  (shell c
                         :thickness spessore :style :voronoi :cells 8 :rows 6 :seed 42
                         :cap-bottom {:thickness spessore :style :voronoi :cells 10 :wall 1}
                         :cap-top spessore)
                  (f 50)))
