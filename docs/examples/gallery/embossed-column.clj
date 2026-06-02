;; Embossed Column
;; A classic column with raised text on its surface. Text is converted to a
;; 2D shape, extruded to a thin mesh, then rasterized as a heightmap with
;; mesh-to-heightmap. That heightmap is applied as radial displacement on a
;; cylindrical loft, so the letters stand out from the surface. The shaft is
;; also fluted, with a simple capital and base assembled via attach.
;; Try changing: the text string, :tile-x, :amplitude, n-flutes / flute-depth.

(def col-radius 15)
(def col-height 80)
(def n-flutes 16)
(def flute-depth 0.12)

(def letters (text-shape "RIDLEY" :size 20))
(def text-mesh (turtle (tv 90) (concat-meshes (extrude letters (f 3)))))
(def text-bounds (bounds text-mesh))
(def text-hm
  (mesh-to-heightmap text-mesh
    :resolution 128
    :offset-x (get-in text-bounds [:min 0])
    :offset-y (get-in text-bounds [:min 1])
    :length-x (- (get-in text-bounds [:max 0]) (get-in text-bounds [:min 0]))
    :length-y (- (get-in text-bounds [:max 1]) (get-in text-bounds [:min 1]))))

(def shaft
  (loft-n 96
    (-> (circle col-radius 64)
        (fluted :flutes n-flutes :depth flute-depth)
        (heightmap text-hm :amplitude 1.5 :center true :tile-x 2 :tile-y 1))
    (f col-height)))

(def base
  (attach
    (loft-n 8
      (tapered (circle (* col-radius 1.4) 64) :to (/ 1 1.4))
      (f 6))
    (tv 180)))

(def capital
  (attach
    (loft-n 8
      (tapered (circle col-radius 64) :to 1.3)
      (f 5))
    (f col-height)))

(def column (concat-meshes [base shaft capital]))
(register column column)
