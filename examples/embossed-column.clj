; === Embossed Column ===
;
; A classical column with text embossed around its surface.
;
; The text is converted to a 2D shape, extruded into a thin 3D mesh,
; then rasterized as a heightmap. That heightmap is applied as radial
; displacement on a cylindrical loft, making the letters stand out
; from the surface. The column also has a fluted shaft and a
; simple capital and base.
;
; Demonstrates:
; - text-shape for generating 2D letter outlines
; - Extruding text to 3D and rasterizing with mesh-to-heightmap
; - heightmap shape-fn for surface displacement from arbitrary geometry
; - Combining multiple shape-fns (fluted + heightmap)
; - Building assemblies with attach
;
; Try changing:
; - The text string for different inscriptions
; - :tile-x to control how many times the text repeats
; - :amplitude for deeper or shallower embossing
; - n-flutes / flute-depth for the column shaft

(def col-radius 15)
(def col-height 80)
(def n-flutes 16)
(def flute-depth 0.12)

; Generate text heightmap
(def letters (text-shape "RIDLEY" :size 20))
(def text-mesh (extrude-z letters 3))
(def text-bounds (bounds text-mesh))
(def text-hm
  (mesh-to-heightmap text-mesh
    :resolution 128
    :offset-x (get-in text-bounds [:min 0])
    :offset-y (get-in text-bounds [:min 1])
    :length-x (- (get-in text-bounds [:max 0]) (get-in text-bounds [:min 0]))
    :length-y (- (get-in text-bounds [:max 1]) (get-in text-bounds [:min 1]))))

; Column shaft: fluted + text embossed
(def shaft
  (loft-n 96
    (-> (circle col-radius 64)
        (fluted :flutes n-flutes :depth flute-depth)
        (heightmap text-hm :amplitude 1.5 :center true :tile-x 2 :tile-y 1))
    (f col-height)))

; Simple base: wider disk
(def base
  (attach
    (loft-n 8
      (tapered (circle (* col-radius 1.4) 64) :to (/ 1 1.4))
      (f 6))
    (tv 180)))

; Simple capital: flared top
(def capital
  (attach
    (loft-n 8
      (tapered (circle col-radius 64) :to 1.3)
      (f 5))
    (f col-height)))

(def column (concat-meshes [base shaft capital]))
(register column column)
