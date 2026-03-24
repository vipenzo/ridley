# Fillet & Chamfer — 2D

2D fillet and chamfer operate on **shape corners** before extrusion. They modify the 2D profile by rounding or cutting vertices.

## `fillet-shape` — Round Corners

```clojure
(fillet-shape shape radius)                        ; Round all corners with circular arcs
(fillet-shape shape radius :segments 16)           ; Smoother arcs (default 8)
(fillet-shape shape radius :indices [0 2])         ; Only specific vertices
```

**Examples:**

```clojure
;; Rounded rectangle
(register pill (extrude (fillet-shape (rect 40 20) 5) (f 10)))

;; Selective: only round two corners of a rect
(register tab (extrude (fillet-shape (rect 30 15) 4 :indices [2 3]) (f 8)))
```

## `chamfer-shape` — Cut Corners Flat

```clojure
(chamfer-shape shape distance)                     ; Cut all corners flat
(chamfer-shape shape distance :indices [0 1])      ; Only specific vertices
```

**Examples:**

```clojure
;; Chamfered hexagon
(register hex (extrude (chamfer-shape (polygon 6 20) 3) (f 15)))
```

## Cap Fillet (`capped`)

Round the edges at extrusion caps (where the 2D profile meets the top/bottom face):

```clojure
(loft (capped shape radius) path)                      ; Fillet both caps (quarter-circle)
(loft (capped shape radius :mode :chamfer) path)       ; Chamfer both caps (linear)
(loft (capped shape radius :start false) path)         ; Fillet end cap only
(loft (capped shape radius :end false) path)           ; Fillet start cap only
(loft (capped shape radius :fraction 0.15) path)       ; Wider transition zone (default 0.08)
```

**Examples:**

```clojure
;; Fully rounded box: 2D corner rounding + 3D cap rounding
(register rounded-box (loft (-> (rect 40 20) (fillet-shape 5) (capped 3)) (f 50)))

;; Tapered with rounded caps
(register drop (loft (-> (circle 20) (tapered :to 0.3) (capped 2)) (f 40)))
```

## Notes

- Distance must be less than half the shortest adjacent edge to avoid overlapping cuts
- Works on any shape including shapes with holes
- `fillet-shape` / `chamfer-shape` operate on **2D corners** (edges along the extrusion direction)
- `capped` operates on **3D cap edges** (where profile meets top/bottom face)
- Both compose freely with all shape-fns and with each other
