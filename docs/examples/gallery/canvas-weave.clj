;; Canvas Weave Cup — the most complex example in the gallery.
;; Two sets of strands are lofted along sinusoidal paths with bezier-as, then
;; rotated 90 degrees and interwoven with concat-meshes. The resulting mesh is
;; sampled as a 2D tile heightmap with mesh-to-heightmap, then applied as radial
;; displacement on a cylindrical loft; the tile repeats seamlessly around the
;; cup. Finally, mesh-difference hollows out the inside.
;;
;; WARNING: computationally expensive, takes 30-60 seconds to render. The
;; bottleneck is the 256x256 heightmap rasterization plus the 128-step loft
;; with per-step heightmap sampling.
;;
;; Try changing: thickness / compactness, :tile-x / :tile-y, :amplitude.

(defn canvas [thickness compactness]
  (let [step121 (* thickness 1.1314)
        spacing (* thickness 1.8)
        wave (+ (* 2 thickness) (* 2 step121 (cos (/ PI 4))))
        n-strands 6
        n-waves 4

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
             (th 90) (rt -1) (f 1)
             (d (* thickness compactness 0.5)))
        weave (concat-meshes [m1 m2])

        b (bounds weave)
        center-x (* 0.5 (+ (get-in b [:min 0]) (get-in b [:max 0])))
        center-y (* 0.5 (+ (get-in b [:min 1]) (get-in b [:max 1])))
        tile-x (* 2 wave)
        tile-y (* 2 wave)

        hm (mesh-to-heightmap weave :resolution 256
             :offset-x (- center-x (* 0.5 tile-x))
             :offset-y (- center-y (* 0.5 tile-y))
             :length-x tile-x
             :length-y tile-y)]
    [weave hm]))

(def weave-hm (canvas 2 0.6))

(def tazza
  (let [esterno (attach
                  (loft-n 128
                    (heightmap (circle 30 128) (weave-hm 1)
                      :amplitude 4 :center true
                      :tile-x 8 :tile-y 4)
                    (f 60)))
        interno (attach (extrude (circle 26 128) (f 60)) (f 4))]
    (mesh-difference esterno interno)))

(register A tazza)
