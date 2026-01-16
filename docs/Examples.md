# Ridley — Examples

Working examples demonstrating Ridley's features.

---

## Basic Turtle Drawing (2D)

```clojure
;; Simple square
(f 50)
(th 90)
(f 50)
(th 90)
(f 50)
(th 90)
(f 50)

;; Triangle
(f 60)
(th 120)
(f 60)
(th 120)
(f 60)

;; Zigzag pattern
(f 20) (th 45) (f 20) (th -90) (f 20) (th 45) (f 20)
```

---

## 3D Primitives

```clojure
;; Box at origin
(box 30)

;; Rectangular box
(box 40 20 10)

;; Sphere
(sphere 25)

;; Cylinder
(cyl 15 40)

;; Cone
(cone 20 5 30)
```

### Positioned Primitives

```clojure
;; Move then place
(f 50)
(box 20)

;; Multiple primitives
(box 20)
(f 40)
(sphere 15)
(f 40)
(cyl 10 30)
```

---

## Generative Operations

### Extrude

```clojure
;; Extrude a path along Z
(def my-shape
  (shape
    (f 30)
    (th 90)
    (f 20)
    (th 90)
    (f 30)))

(extrude-z my-shape 15)
```

### Revolve

```clojure
;; Revolve a profile to create a vase
(def vase-profile
  (path
    (f 20)
    (th 90)
    (f 5)
    (th -45)
    (f 15)
    (th 45)
    (f 10)))

(revolve vase-profile [0 1 0] 360 32)
```

---

## Shape Extrusion (Phase 3 - New API)

The new shape-based modeling uses `extrude` to stamp and extrude shapes.

### Core Concept

Shapes are 2D profiles. Use `extrude` with a shape and movements:

```clojure
;; Shapes are data - they don't modify the turtle
(circle 15)              ; Returns a circular shape
(rect 40 20)             ; Returns a rectangular shape
(polygon [[0 0] ...])    ; Returns a polygon shape

;; Stamp and extrude in one call
(extrude (circle 15) (f 30)) ; Stamp circle, extrude 30 units
```

**Key insight**: The shape is stamped on the plane perpendicular to the turtle's
heading. Movements inside `extrude` create the 3D geometry.

### Basic Cylinder

```clojure
;; Turtle starts at origin, facing +X
;; Stamp circle (on YZ plane), extrude along +X
(extrude (circle 15) (f 30))
```

### Box via Rectangle

```clojure
(extrude (rect 40 20) (f 25))  ; Rectangle extruded 25 along X
```

### Custom Polygon Extrusion

```clojure
(extrude (polygon [[-10 -10] [10 -10] [15 0] [10 10] [-10 10] [-15 0]])
         (f 20))
```

### Positioned Extrusion

Move the turtle first, then extrude:

```clojure
(pen :off)                     ; Don't draw while moving
(f 50)                         ; Move to position
(tv 90)                        ; Point up (+Z)
(extrude (circle 10) (f 30))   ; Extrude cylinder along Z
```

### Diagonal Extrusion

```clojure
(tv 45)                        ; Point 45° up from X axis
(extrude (circle 10) (f 30))   ; Diagonal cylinder
```

### Multiple Shapes

```clojure
;; First cylinder at origin
(extrude (circle 10) (f 20))

;; Move and create second cylinder
(pen :off)
(th 90)                        ; Turn left
(f 50)                         ; Move
(extrude (circle 10) (f 20))
```

### Sweep (Multiple Movements)

Multiple movements inside `extrude` create a unified mesh:

```clojure
(extrude (circle 8)
         (f 20)                ; First segment
         (th 45)               ; Turn
         (f 20))               ; Second segment - joins seamlessly
```

All movements within a single `extrude` produce one unified mesh with proper connectivity.

### Loft (Shape Transformation)

`loft` extrudes a shape while applying a transformation function.
Works like `extrude` - uses turtle movements to define the path:

```clojure
;; Basic cone (scale down to 0)
(loft (circle 20) #(scale %1 (- 1 %2)) (f 30))

;; Cone that tapers to half size
(loft (circle 20) #(scale %1 (- 1 (* 0.5 %2))) (f 30))

;; Twist: rectangle rotating 90° during extrusion
(loft (rect 30 10) #(rotate-shape %1 (* %2 90)) (f 40))

;; Twist + scale (combined transforms)
(loft (rect 20 20)
      #(-> %1
           (scale (- 1 (* 0.5 %2)))      ; scale from 100% to 50%
           (rotate-shape (* %2 180)))    ; rotate from 0° to 180°
      (f 30))

;; Scale down to point + twist
(loft (rect 20 20)
      #(-> %1 (scale (- 1 %2)) (rotate-shape (* %2 90)))
      (f 40))

;; Witch hat: cone with a bend
(loft (circle 20) #(scale %1 (- 1 %2)) (f 15) (th 30) (f 15))

;; Star morphing to circle
(loft (star 5 20 8)
      #(morph %1 (circle 15 10) %2)  ; both need same point count
      (f 30))

;; With custom step count (smoother)
(loft-n 32 (circle 20) #(scale %1 (- 1 %2)) (f 30))
```

The transform function receives:
- `shape` - the current 2D shape
- `t` - progress from 0.0 to 1.0 (based on cumulative distance traveled)

Step count:
- Default: 16 steps (use `loft`)
- Custom: use `loft-n` with step count as first argument

Available shape transforms:
- `(scale shape factor)` - uniform scale
- `(scale shape fx fy)` - non-uniform scale
- `(rotate-shape shape angle)` - rotate (degrees)
- `(translate shape dx dy)` - move
- `(morph shape-a shape-b t)` - interpolate between shapes
- `(resample shape n)` - resample to n points

### Pen Modes

`pen` controls line drawing mode (separate from extrusion):

```clojure
(pen :off)               ; Stop drawing lines
(pen :on)                ; Draw lines (default turtle mode)
(f 30)                   ; This draws a line when pen is :on
```

---

## Paths (Recorded Movements)

Paths record turtle movements for later replay. The code inside `path` executes
on a "recorder" turtle - you can use any Clojure code including loops.

### Basic Path

```clojure
;; Record a simple path
(def square-path (path (f 20) (th 90) (f 20) (th 90) (f 20) (th 90) (f 20)))

;; Use with extrude
(extrude (circle 5) square-path)
```

### Path with Loops

```clojure
;; Record a square using dotimes
(def square (path (dotimes [_ 4] (f 30) (th 90))))

;; Record a zigzag
(def zigzag (path (dotimes [_ 5] (f 10) (th 60) (f 10) (th -60))))

;; Use paths in extrude or loft
(extrude (rect 10 10) square)
(loft (circle 15) #(scale %1 (- 1 %2)) zigzag)
```

### Path with 3D Movement

```clojure
;; Helix path
(def helix (path (dotimes [_ 36] (f 5) (th 10) (tv 5))))

;; Spiral staircase
(extrude (rect 20 5) helix)
```

### Combining Paths

```clojure
;; Define reusable path segments
(def step (path (f 10) (tv 45) (f 10) (tv -45)))

;; Paths can be used multiple times
(extrude (circle 8) step)
(pen :off) (f 50) (th 90)
(extrude (circle 8) step)
```

---

## Closed Extrusion (Torus-like)

`extrude-closed` creates a closed mesh where the last ring connects back to the first.
Use this for paths that return to the starting point.

```clojure
;; Square torus
(def square-path (path (dotimes [_ 4] (f 20) (th 90))))
(extrude-closed (circle 5) square-path)

;; Triangular torus
(def tri-path (path (dotimes [_ 3] (f 30) (th 120))))
(extrude-closed (circle 8) tri-path)

;; Hexagonal torus
(def hex-path (path (dotimes [_ 6] (f 15) (th 60))))
(extrude-closed (rect 4 4) hex-path)
```

**Note**: `extrude-closed` automatically processes any pending rotation at the end,
ensuring proper corner filleting before closing the loop.

---

## Manifold Operations

Manifold operations validate meshes and perform boolean operations (CSG).
Requires manifold-3d WASM module (loaded automatically from CDN).

### Mesh Validation

```clojure
;; Create a mesh
(box 20)

;; Check if the mesh is valid (watertight, no self-intersections)
(manifold? (first (:meshes (get-turtle))))
;; => true

;; Get detailed status
(mesh-status (first (:meshes (get-turtle))))
;; => {:manifold? true, :status :ok, :volume 8000, :surface-area 2400}
```

### Boolean Union

Combine two meshes into one:

```clojure
;; Create first mesh
(box 20)
(def mesh-a (first (:meshes (get-turtle))))

;; Create second mesh (overlapping)
(f 15)
(sphere 12)
(def mesh-b (second (:meshes (get-turtle))))

;; Union: A + B
(mesh-union mesh-a mesh-b)
```

### Boolean Difference

Subtract one mesh from another:

```clojure
;; Box with spherical hole
(box 30)
(def base (first (:meshes (get-turtle))))

(sphere 18)
(def cutter (second (:meshes (get-turtle))))

;; Difference: A - B
(mesh-difference base cutter)
```

### Boolean Intersection

Keep only the overlapping region:

```clojure
;; Create two overlapping shapes
(box 25)
(def a (first (:meshes (get-turtle))))

(f 10)
(box 25)
(def b (second (:meshes (get-turtle))))

;; Intersection: A ∩ B
(mesh-intersection a b)
```

### Practical Example: Dice

```clojure
;; Definitions panel:
(def dice-size 20)
(def pip-radius 2.5)
(def pip-depth 1.5)

;; Create the base cube
(box dice-size)

;; Note: For a complete dice, you would create spheres at pip positions
;; and use mesh-difference to subtract them from the cube.
```

---

## Coming Soon

### Custom Shapes from Points

```clojure
(def my-L
  (make-shape [[0 0] [30 0] [30 10] [10 10] [10 30] [0 30]]))

(extrude my-L (f 15))
```

### Face-Based Modeling

```clojure
;; Create box, select face, extrude on it
(box 50 50 20)
(pen :top)                     ; Select top face
(extrude (circle 15) (f 30))   ; Extrude up (add boss)
```
