# Ridley — DSL Specification

## Overview

Ridley scripts are valid Clojure code executed in a pre-configured environment. All DSL functions are available without imports.

## Dense Syntax

For paths and shapes, a compact string notation is available alongside the functional form.

### Equivalence

```clojure
;; Functional form
(path
  (f 20)
  (fillet 2)
  (th 90)
  (f 10))

;; Dense form
(path "F20 R2 TH90 F10")

;; Mixed form
(path
  "F20 R2"
  (th angle)  ; computed value
  "F10")
```

### Dense Syntax Grammar

```
command     = movement | rotation | modifier | anchor | curve
movement    = ("F" | "B") number
rotation    = ("TH" | "TV" | "TR") number
modifier    = ("R" | "C") number                    ; fillet (R=radius), chamfer (C)
anchor      = ("@" | "@>") identifier               ; define anchor
goto        = ("GA" | "GD") identifier              ; goto anchor
curve       = arc | bezier
arc         = ("AH" | "AV") number "," number       ; arc horizontal/vertical: radius, angle
bezier      = "BZ" number "," number "," number ["H" number "," number "," number]

number      = "-"? digit+ ("." digit+)?
identifier  = letter (letter | digit | "-" | "_")*
separator   = " " | ";" | newline
```

### Examples

```
F20TH90F10              ; no separators needed when unambiguous
F20;TH90;F10            ; semicolons for clarity
F20 TH90 F10            ; spaces
AH50,90                 ; arc: radius 50, angle 90
BZ10,20,30              ; bezier to point
BZ10,20,30H0,0,1        ; bezier with arrival heading
@>start                 ; define oriented anchor
GAstart                 ; goto anchor
```

---

## Turtle Commands

### Movement

| Function | Dense | Description |
|----------|-------|-------------|
| `(f dist)` | `F<n>` | Forward |
| `(b dist)` | `B<n>` | Backward |

### Rotation

| Function | Dense | Description |
|----------|-------|-------------|
| `(th angle)` | `TH<n>` | Turn horizontal (yaw), degrees |
| `(tv angle)` | `TV<n>` | Turn vertical (pitch), degrees |
| `(tr angle)` | `TR<n>` | Turn roll, degrees |

### Pen Control

| Function | Dense | Description |
|----------|-------|-------------|
| `(pen :off)` | `PO` | Stop drawing (pen up) |
| `(pen :2d)` | `P2D` | Draw 2D lines |
| `(pen :3d ...)` | — | Draw on arbitrary plane (see below) |
| `(pen <face-id>)` | — | Select face for 2D drawing |

#### Drawing on Arbitrary Planes

To start a new mesh by extruding a 2D shape, use `(pen :3d ...)` with full frame specification:

```clojure
;; Explicit plane: point + normal + heading (full frame)
(pen :3d :at [10 20 0] :normal [0 0 1] :heading [1 0 0])

;; Using an anchor (has full frame: position + heading + up)
(pen @my-anchor)                           ; Uses anchor's full frame

;; Then draw and extrude
(circle 15)
(f 30)                                     ; Creates cylinder mesh
```

The plane requires a full coordinate frame:
- `:at` — position (origin of the 2D coordinate system)
- `:normal` — Z axis of the plane (extrusion direction)
- `:heading` — X axis of the plane (turtle's forward direction)

#### Face Selection

When working with existing 3D meshes, select a face to draw on:

```clojure
;; Semantic face names (for primitives)
(pen :top)
(pen :bottom)
(pen :front)
(pen :back)
(pen :left)
(pen :right)

;; Numeric face IDs (for complex meshes)
(pen 42)

;; With offset on face (UV coordinates from center)
(pen :top :at [5 10])                      ; Offset from face center

;; Face discovery
(list-faces mesh)           ; Lists all faces with IDs
(select mesh face-id)       ; Highlights face in viewport
```

### Anchors

```clojure
;; Define position-only anchor
(mark :name)              ; @name in dense

;; Define position+orientation anchor  
(mark-oriented :name)     ; @>name in dense

;; Navigate to anchor
(goto :name)              ; GA<name> — keeps current orientation
(goto-oriented :name)     ; GD<name> — adopts anchor's orientation

;; Orient toward anchor (without moving)
(look-at :name)
```

### Curves

```clojure
;; Arcs
(arc-h radius angle)      ; AH<r>,<a> — horizontal arc
(arc-v radius angle)      ; AV<r>,<a> — vertical arc

;; Bezier curves
(bezier-to [x y z])                           ; BZ<x>,<y>,<z>
(bezier-to [x y z] :heading [hx hy hz])       ; BZ<x>,<y>,<z>H<hx>,<hy>,<hz>
(bezier-to-anchor :name)                      ; uses anchor position, current heading
(bezier-to-oriented :name)                    ; uses anchor position AND heading
```

### Modifiers (in path context)

```clojure
(fillet radius)           ; R<n> — fillet at current point
(chamfer dist)            ; C<n> — chamfer at current point
```

---

## Path and Shape Construction

### Path (3D guide line)

```clojure
(def my-path
  (path
    (f 100)
    (fillet 10)
    (th 45)
    (f 50)))

;; Or dense
(def my-path (path "F100 R10 TH45 F50"))
```

### Shape (2D closed profile)

```clojure
(def my-shape
  (shape
    (f 20)
    (th 90)
    (f 10)
    (th 90)
    (f 20)))
;; Auto-closes back to start

;; Explicit close variants
(def my-shape
  (shape
    (f 20)
    (th 90)
    (f 10)
    (close)))           ; straight line to start

(def my-shape
  (shape
    (f 20)
    (th 90)
    (f 10)
    (close-smooth)))    ; bezier to start
```

### 2D Primitives (in shape context)

```clojure
(circle radius)
(rect width height)
(polygon [[x1 y1] [x2 y2] ...])
```

---

## 3D Primitives

Primitives are placed at current turtle position and orientation.

```clojure
(box size)                    ; cube
(box x y z)                   ; rectangular box
(sphere radius)
(cyl radius height)           ; cylinder
(cone r1 r2 height)           ; cone/frustum
```

With modifiers:

```clojure
(box 10 20 30 :fillet 2)                ; all edges filleted
(box 10 20 30 :fillet 2 :edges :top)    ; only top edges
(cyl 10 50 :chamfer 1 :cap :top)        ; chamfer top edge only
```

---

## Generative Operations

### Extrude

```clojure
(extrude shape distance)
(extrude shape distance :cap-fillet r)
(extrude shape distance :twist angle)       ; twist while extruding
(extrude shape distance :scale factor)      ; scale while extruding
```

### Extrude Along Path

```clojure
(sweep path shape)
(sweep path shape :twist angle)
(sweep path shape :align true)    ; shape stays aligned to path tangent
```

### Revolve

```clojure
(revolve shape axis)                        ; full 360°
(revolve shape axis :angle 180)             ; partial
(revolve shape axis :fillet r)              ; fillet at junction
```

### Loft

```clojure
(loft shape1 shape2)
(loft shape1 shape2 :steps 20)
(loft [shape1 shape2 shape3])     ; multiple profiles
```

---

## Face-Based Modeling (Primary Method)

The primary way to modify 3D shapes is through face extrusion:

```clojure
;; Create a box with a cylindrical hole
(box 100 100 50)              ; Create initial box
(pen :top)                    ; Select top face
(circle 20)                   ; Draw circle profile
(f -50)                       ; Extrude down = subtract hole

;; Add a raised boss
(pen :top)                    ; Select top face again
(circle 15)                   ; Draw smaller circle
(f 30)                        ; Extrude up = add material

;; Create a pocket
(pen :top)
(rect 30 20)                  ; Draw rectangle
(f -10)                       ; Shallow pocket
```

### Extrusion Direction

The direction of `(f dist)` determines the operation:
- **Positive distance**: Add material (like union)
- **Negative distance**: Remove material (like subtract)

### Face Discovery

For complex meshes where face IDs aren't obvious:

```clojure
(list-faces my-mesh)          ; Print all face IDs and info
;; => [{:id :top :normal [0 0 1] :center [50 50 50]}
;;     {:id :side-0 :normal [1 0 0] :center [100 50 25]}
;;     ...]

(select my-mesh :side-0)      ; Highlight face in viewport
(pen :side-0)                 ; Select for drawing
```

---

## Boolean Operations (Advanced)

For cases where face-based modeling isn't sufficient:

```clojure
(union a b)
(union a b c d)                   ; variadic

(subtract base tool)
(subtract base t1 t2 t3)          ; subtract multiple

(intersect a b)
```

With integrated fillet/chamfer:

```clojure
(union a b :fillet 2)             ; fillet at seam
(subtract base tool :fillet 1)
(subtract base tool :chamfer 0.5)
```

Note: Boolean operations require Manifold WASM integration.

---

## Variables and Functions

Full Clojure available:

```clojure
(def wall-thickness 2)
(def radius 30)

(defn tube [outer inner height]
  (subtract
    (cyl outer height)
    (cyl inner (+ height 1))))

(defn hole-pattern [n radius]
  (for [i (range n)]
    (-> (turtle)
        (th (* i (/ 360 n)))
        (f radius)
        (position))))             ; returns [x y z]

(def body
  (reduce
    (fn [b pos] (subtract b (cyl 3 20) :at pos :fillet 0.5))
    (box 100 100 20)
    (hole-pattern 8 35)))
```

---

## Viewport Control

### Visibility

```clojure
(show shape)                      ; add to viewport
(show shape :as :wireframe)
(show path :as :line)

(hide shape)                      ; remove from viewport
(solo shape)                      ; show only this, hide others
(show-all)                        ; show everything
```

### Inspection

```clojure
(inspect shape)                   ; show + zoom to fit

(bounds shape)                    ; => {:min [...] :max [...]}
(volume shape)                    ; => mm³
(watertight? shape)               ; => true/false
(center shape)                    ; => [x y z]
```

### View Settings

```clojure
(set-view :axes true)
(set-view :grid true)
(set-view :anchors true)
(set-view :workplanes false)
```

### Search

```clojure
(find "pattern")                  ; fuzzy search defined names
;; => [{:type :shape :name "pattern-1"}
;;     {:type :anchor :name "pattern-start"}]

(show-matching "hole")            ; show all matching
```

---

## Export

Exports whatever is currently visible.

```clojure
(export "filename.stl")
(export "filename.stl" :binary true)
(export "filename.stl" :ascii true)
(export "filename.obj")
(export "filename.3mf")
(export "filename.3mf" :multicolor true)

;; With transform
(export "filename.stl" :scale 2)
(export "filename.stl" :orient :print)   ; auto-orient for printing

;; Batch export
(export-each "directory/")               ; one file per visible shape
```

### Pre-export Check

```clojure
(check-printable shape)
;; => {:watertight true
;;     :volume 12450.0
;;     :bounds [45 30 22]
;;     :manifold true
;;     :overhangs [{:z [15 18] :angle 52}]
;;     :thin-walls nil}
```

---

## VR Settings

```clojure
(set-vr :scale 1.0)               ; 1:1 real scale
(set-vr :scale 10.0)              ; 10x magnification
(set-vr :table-height 0.9)        ; meters
(set-vr :mode :vr)                ; full VR
(set-vr :mode :passthrough)       ; AR passthrough (Quest 3)
(set-vr :grid true)
(set-vr :ruler true)
```

---

## Complete Example

### Face-Based Approach (Recommended)

```clojure
;; Parametric enclosure with mounting holes

(def wall 3)
(def outer-w 60)
(def outer-h 40)
(def outer-d 25)
(def corner-r 3)
(def hole-r 2.5)
(def hole-inset 5)

;; Start with solid box
(box outer-w outer-h outer-d)

;; Hollow out from top
(pen :top)
(rect (- outer-w (* wall 2)) (- outer-h (* wall 2)))
(f (- (- outer-d wall)))                ; Deep pocket, leaves bottom wall

;; Add mounting holes in corners
(pen :bottom)
(pen-up)
(f hole-inset) (th 90) (f hole-inset)   ; Move to corner
(pen-down)
(circle hole-r)
(f outer-d)                              ; Through hole (positive = add? No, from bottom face normal points down)
;; Actually: (f -outer-d) to go "into" the part

;; Repeat for other corners...
```

### Traditional Approach (with Booleans)

```clojure
;; Parametric box with snap-fit lid

(def wall 2)
(def inner-w 40)
(def inner-h 30)
(def inner-d 25)
(def clearance 0.2)
(def lip 3)

;; Outer shell profile
(def box-profile
  (shape
    (f inner-w)
    (fillet 3)
    (th 90)
    (f inner-h)
    (fillet 3)
    (th 90)
    (f inner-w)
    (fillet 3)
    (th 90)
    (f inner-h)))

;; Box body
(def body
  (-> box-profile
      (extrude inner-d :cap-fillet 1)
      (subtract
        (-> box-profile
            (offset (- wall))
            (extrude (- inner-d wall))
            (translate [0 0 wall])))))

;; Lid profile (slightly larger for clearance)
(def lid-profile
  (shape
    (f (+ inner-w clearance))
    (fillet 3)
    (th 90)
    (f (+ inner-h clearance))
    (fillet 3)
    (th 90)
    (f (+ inner-w clearance))
    (fillet 3)
    (th 90)
    (f (+ inner-h clearance))))

;; Lid with lip
(def lid
  (union
    (extrude lid-profile wall :cap-fillet 0.5)
    (-> lid-profile
        (offset (- wall clearance))
        (extrude lip)
        (translate [0 0 (- wall)]))))

;; Position lid for preview
(def lid-positioned
  (translate lid [0 0 (+ inner-d 5)]))

;; Show
(show body)
(show lid-positioned)

;; Export separately
(solo body)
(export "box-body.stl")
(solo lid)
(export "box-lid.stl")
```