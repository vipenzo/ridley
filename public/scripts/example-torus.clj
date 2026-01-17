; Square torus example
(def sq (path (dotimes [_ 4] (f 20) (th 90))))
(extrude-closed (circle 5) sq)
