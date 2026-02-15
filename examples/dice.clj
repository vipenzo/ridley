; === Six-Sided Die ===
;
; A classic D6 die with subtracted pips and rounded edges.
;
; The die is built by starting from a box, rounding the edges
; with shape-offset, and subtracting spherical pips from each face.
; Pip positions follow standard Western dice layout where
; opposite faces sum to 7.
;
; Demonstrates:
; - mesh-difference for boolean subtraction (CSG)
; - attach to place geometry on specific faces
; - Parametric pip placement with loops
; - Building complex objects from simple primitives
;
; Try changing:
; - die-size for a bigger or smaller die
; - pip-radius / pip-depth for different pip styles
; - corner-radius for sharper or rounder edges

(def die-size 20)
(def half (/ die-size 2))
(def pip-radius 2.0)
(def pip-depth 1.2)
(def pip-spread 5.5)

; The die body
(def body (box die-size))

; A single pip: a flattened sphere positioned to cut into a face
(defn pip [x-off y-off]
  (attach (sphere pip-radius)
    (f (- half pip-depth))
    (rt x-off)
    (u y-off)))

; Pip patterns for each face value
(defn face-1 [] [(pip 0 0)])

(defn face-2 [] [(pip (- pip-spread) pip-spread)
                  (pip pip-spread (- pip-spread))])

(defn face-3 [] (concat (face-1) (face-2)))

(defn face-4 [] [(pip (- pip-spread) pip-spread)
                  (pip pip-spread pip-spread)
                  (pip (- pip-spread) (- pip-spread))
                  (pip pip-spread (- pip-spread))])

(defn face-5 [] (concat (face-4) (face-1)))

(defn face-6 [] [(pip (- pip-spread) pip-spread)
                  (pip (- pip-spread) 0)
                  (pip (- pip-spread) (- pip-spread))
                  (pip pip-spread pip-spread)
                  (pip pip-spread 0)
                  (pip pip-spread (- pip-spread))])

; Place pips on all six faces
; Face orientations: front(1), right(2), top(3), bottom(4), left(5), back(6)
(def all-pips
  (concat-meshes
    (concat
      ; Face 1 - front (default heading)
      (face-1)
      ; Face 6 - back
      (map (fn [p] (attach p (th 180))) (face-6))
      ; Face 2 - right
      (map (fn [p] (attach p (th -90))) (face-2))
      ; Face 5 - left
      (map (fn [p] (attach p (th 90))) (face-5))
      ; Face 3 - top
      (map (fn [p] (attach p (tv -90))) (face-3))
      ; Face 4 - bottom
      (map (fn [p] (attach p (tv 90))) (face-4)))))

(def die (mesh-difference body all-pips))

(register die die)
