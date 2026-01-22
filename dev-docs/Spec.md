# Ridley — DSL Specification

## Overview

Ridley scripts are valid Clojure code executed in a pre-configured environment using SCI (Small Clojure Interpreter). All DSL functions are available without imports.

**Evaluation model:**
- REPL commands are executed in a persistent environment
- Turtle pose (position, heading, up) persists between commands
- Turtle lines (pen traces) are cleared on each command
- Meshes must be explicitly registered with `register` to be visible

---

## Turtle Commands

### Movement

| Function | Description |
|----------|-------------|
| `(f dist)` | Move forward (negative for backward) |

### Rotation

| Function | Description |
|----------|-------------|
| `(th angle)` | Turn horizontal (yaw), degrees |
| `(tv angle)` | Turn vertical (pitch), degrees |
| `(tr angle)` | Turn roll, degrees |

### Arc Commands

Draw smooth arcs by combining movement with rotation:

```clojure
(arc-h radius angle)             ; Horizontal arc (turns around up axis)
(arc-h radius angle :steps 24)   ; With explicit step count

(arc-v radius angle)             ; Vertical arc (turns around right axis)
(arc-v radius angle :steps 24)   ; With explicit step count
```

- `arc-h`: turtle moves in a circular arc horizontally
- `arc-v`: turtle moves in a circular arc vertically
- Positive angle = standard rotation direction
- Steps default to resolution setting

**Examples:**
```clojure
(arc-h 10 90)                    ; Quarter circle turning left, radius 10
(arc-h 10 -90)                   ; Quarter circle turning right

;; S-curve
(arc-h 10 90)
(arc-h 10 -90)
```

### Bezier Commands

Draw smooth bezier curves to target positions:

```clojure
;; Auto-generated control points (starts tangent to current heading)
(bezier-to [x y z])
(bezier-to [x y z] :steps 24)

;; Quadratic bezier (1 control point)
(bezier-to [x y z] [cx cy cz])

;; Cubic bezier (2 control points)
(bezier-to [x y z] [c1x c1y c1z] [c2x c2y c2z])

;; Bezier to named anchor
(bezier-to-anchor :name)
(bezier-to-anchor :name :steps 24)
```

### Pen Control

| Function | Description |
|----------|-------------|
| `(pen :off)` | Stop drawing (pen up) |
| `(pen :on)` | Draw lines (default) |

### State Stack

```clojure
(push-state)              ; Save position, heading, up, pen-mode
(pop-state)               ; Restore most recent saved state
(clear-stack)             ; Clear stack without restoring
```

### Reset

```clojure
(reset)                              ; Reset to origin, facing +X, up +Z
(reset [x y z])                      ; Reset to position, default orientation
(reset [x y z] :heading [hx hy hz])  ; Position + heading
(reset [x y z] :heading [...] :up [ux uy uz])  ; Full control
```

Resets turtle pose without clearing accumulated geometry/meshes.

### Resolution (Curve Quality)

Control the resolution of curves and circular primitives globally:

```clojure
(resolution :n 32)               ; Fixed number of segments (default 16)
(resolution :a 5)                ; Maximum angle per segment (degrees)
(resolution :s 0.5)              ; Maximum segment length (units)
```

**Affected operations:**
- `arc-h`, `arc-v` — arc step count
- `bezier-to` — bezier step count
- `circle` — circle segment count
- `sphere`, `cyl`, `cone` — circumferential segments
- Round joints during extrusion

**Override for specific calls:**
```clojure
(arc-h 10 90 :steps 32)          ; Override resolution for this arc
(circle 5 64)                    ; Circle with explicit 64 segments
(sphere 10 32 16)                ; Sphere with explicit segments/rings
```

### Anchors & Navigation

```clojure
;; Define anchor at current position+orientation
(mark :name)

;; Navigate to anchor (adopts anchor's heading)
(goto :name)

;; Orient toward anchor (no movement)
(look-at :name)

;; Create path from current position to anchor
(path-to :name)           ; Returns a path for use in extrude
```

---

## Path Recording

Paths record turtle movements for later replay in extrusions:

```clojure
(def my-path
  (path
    (f 100)
    (th 45)
    (f 50)))

;; With arbitrary Clojure code
(def square-path
  (path
    (dotimes [_ 4]
      (f 20)
      (th 90))))

;; Paths can be used directly in extrude
(register tube (extrude (circle 5) my-path))
```

### Path Utilities

```clojure
;; Execute a path on the turtle (draws lines if pen is on)
(follow-path my-path)
```

---

## 2D Shapes

Shapes are 2D profiles used for extrusion:

```clojure
(circle radius)                  ; Circle, uses resolution setting
(circle radius segments)         ; Custom resolution

(rect width height)              ; Rectangle centered at origin

(polygon [[x1 y1] [x2 y2] ...])  ; Custom polygon from points

(star n-points outer-r inner-r)  ; Star shape (n tips)
```

### Custom Shapes from Turtle

Create arbitrary 2D shapes using turtle movements:

```clojure
;; Triangle (equilateral)
(def tri (shape
  (f 10) (th 120)
  (f 10) (th 120)
  (f 10)))

;; Right triangle - auto-closes to starting point
(def right-tri (shape
  (f 4) (th 90)
  (f 3)))                        ; Closes automatically

;; L-shape
(def l-shape (shape
  (f 10) (th 90)
  (f 5) (th 90)
  (f 5) (th -90)
  (f 5) (th 90)
  (f 5)))

;; Use in extrusion
(register prism (extrude tri (f 20)))
```

The `shape` macro uses a 2D turtle starting at origin facing +X. Only `f` (forward) and `th` (turn horizontal) are available. The shape is automatically closed.

### Shape Transformations

For loft operations that morph shapes:

```clojure
(scale shape factor)             ; Uniform scale
(scale shape fx fy)              ; Non-uniform scale

(rotate-shape shape angle-deg)   ; Rotate around origin

(translate shape dx dy)          ; Translate shape by [dx dy]

(morph shape-a shape-b t)        ; Interpolate between shapes (t: 0-1)
                                 ; Both must have same point count

(resample shape n)               ; Resample to n points (for morph compatibility)
```

---

## 3D Primitives

Primitives return mesh data at current turtle position:

```clojure
(box size)                       ; Cube
(box sx sy sz)                   ; Rectangular box

(sphere radius)                  ; Sphere, uses resolution setting
(sphere radius segments rings)   ; Custom resolution

(cyl radius height)              ; Cylinder, uses resolution setting
(cyl radius height segments)     ; Custom segments

(cone r1 r2 height)              ; Frustum (r1=bottom, r2=top)
(cone r1 r2 height segments)     ; Use r2=0 for proper cone
```

**Important:** Primitives create meshes at the current turtle position but do NOT modify turtle state. Use `register` to make them visible:

```clojure
(register my-box (box 20))
(f 30)
(register my-sphere (sphere 10))
```

---

## Generative Operations

### Extrude

Extrude sweeps a 2D shape along a path. Returns a mesh without side effects:

```clojure
;; Single movement
(register tube (extrude (circle 15) (f 30)))

;; Multiple movements (auto-wrapped in path)
(register bent-tube
  (extrude (circle 15)
    (f 20)
    (th 45)
    (f 20)))

;; Along a recorded path
(register tube (extrude (circle 15) my-path))

;; Along path to anchor
(register connector (extrude (circle 5) (path-to :target)))
```

**Pure operation:** `extrude` does not modify turtle state. The mesh starts at current turtle position.

#### Joint Modes

Control corner geometry during extrusion:

```clojure
(joint-mode :flat)      ; Default - sharp corners
(joint-mode :round)     ; Smooth rounded corners
(joint-mode :tapered)   ; Beveled/tapered corners
```

### Extrude Closed

For closed loops (torus-like shapes):

```clojure
;; Path should return to starting point
(def square-path (path (dotimes [_ 4] (f 20) (th 90))))

(register torus (extrude-closed (circle 5) square-path))
```

Creates a manifold mesh with no caps — last ring connects to first.

### Loft

Extrude with shape transformation based on progress:

```clojure
;; Transform function receives (shape t) where t: 0->1
(register cone
  (loft (circle 20)
    #(scale %1 (- 1 %2))           ; Scale from 1 to 0
    (f 30)))

;; Twist while extruding
(register twisted
  (loft (rect 20 10)
    #(rotate-shape %1 (* %2 90))   ; 90 deg twist
    (f 30)))

;; With custom step count for smoother result
(register smooth-cone
  (loft-n 32 (circle 20)
    #(scale %1 (- 1 %2))
    (f 30)))
```

Default: 16 steps. Returns mesh without side effects.

### Sweep (Between Two Shapes)

Create mesh between two shapes at different positions:

```clojure
(register transition
  (sweep (circle 5)
    (do
      (f 10)
      (th 90)
      (circle 8))))      ; Returns second shape at new position
```

First shape stamped at current position, body moves turtle, last expression returns second shape.

### Revolve

```clojure
(revolve shape axis)             ; Full 360 deg revolution
```

---

## Boolean Operations

Requires Manifold WASM integration:

```clojure
(mesh-union a b)
(mesh-union a b c d)             ; Variadic

(mesh-difference base tool)
(mesh-difference base t1 t2 t3)  ; Subtract multiple

(mesh-intersection a b)
```

Check mesh status:

```clojure
(manifold? mesh)                 ; true if manifold (watertight)
(mesh-status mesh)               ; Detailed status info
```

---

## Convex Hull

Compute the convex hull of one or more meshes:

```clojure
(register s1 (sphere 10))
(f 30)
(register s2 (sphere 10))
(register capsule (mesh-hull s1 s2))    ; Creates capsule shape

;; Can also pass a vector
(mesh-hull [s1 s2 s3])
```

---

## Text Shapes

Convert text to 2D shapes using opentype.js font parsing:

```clojure
;; Basic text shape (uses default Roboto font)
(text-shape "Hello")                    ; Returns 2D shape

;; With size
(text-shape "Hello" :size 30)           ; Larger text

;; Extrude for 3D text
(register title (extrude (text-shape "RIDLEY" :size 40) (f 5)))

;; Individual character shapes
(text-shapes "ABC" :size 20)            ; Returns vector of shapes

;; Load custom font
(load-font! "/path/to/font.ttf")        ; Returns promise
(load-font! :roboto-mono)               ; Built-in monospace font

;; Check if font is ready
(font-loaded?)                          ; true when default font loaded
```

**Built-in fonts:**
- `:roboto` - Roboto Regular (default)
- `:roboto-mono` - Roboto Mono (monospace)

---

## Text on Path

Place 3D text along a curved path:

```clojure
;; Define a curved path
(def curve (path (dotimes [_ 40] (f 2) (th 3))))

;; Place text along the curve
(register curved-text (text-on-path "Hello" curve :size 15 :depth 3))
```

### Options

| Option | Default | Description |
|--------|---------|-------------|
| `:size` | 10 | Font size in units |
| `:depth` | 5 | Extrusion depth |
| `:font` | Roboto | Custom font object |
| `:spacing` | 0 | Extra letter spacing (can be negative) |
| `:align` | `:start` | Alignment: `:start`, `:center`, `:end` |
| `:overflow` | `:truncate` | What to do when text is longer than path |

### Overflow Modes

- `:truncate` — Stop placing letters when path ends (default)
- `:wrap` — Continue from start (for closed paths)
- `:scale` — Scale text to fit path length

---

## Scene Registry

Named objects persist across evaluations:

```clojure
;; Register: define var, add to registry, show on first registration
(register torus (extrude-closed (circle 5) square-path))
;; Creates 'torus' var, registers as :torus, makes visible

;; Show/hide by name (keyword) or reference
(show :torus)
(hide :torus)
(show torus)                     ; By var reference
(hide torus)

;; Bulk operations
(show-all)
(hide-all)
(show-only-objects)              ; Hide anonymous meshes

;; Query registry
(objects)                        ; List visible names
(registered)                     ; List all registered names
(scene)                          ; All meshes (registered + anonymous)

;; Get info
(info :torus)                    ; {:name :torus :visible true :vertices n :faces n}
(info torus)                     ; By reference

;; Get raw mesh data
(mesh :torus)                    ; By name
(mesh torus)                     ; Identity (returns mesh itself)
```

---

## Face Operations

Query face information for meshes with face-groups (primitives):

```clojure
(list-faces mesh)
;; => [{:id :top :normal [0 1 0] :heading [1 0 0] :center [0 10 0] ...}
;;     {:id :bottom ...}
;;     ...]

(face-ids mesh)                  ; => (:top :bottom :front :back :left :right)

(get-face mesh :top)             ; Basic face info
(face-info mesh :top)            ; Detailed: includes area, edges, vertex positions
```

### Face Highlighting

```clojure
(highlight-face mesh :top)                    ; Permanent orange highlight
(highlight-face mesh :top 0xff0000)           ; Custom color (hex)

(flash-face mesh :top)                        ; 2-second temporary highlight
(flash-face mesh :top 3000)                   ; Custom duration (ms)
(flash-face mesh :top 2000 0x00ff00)          ; Duration + color

(clear-highlights)                            ; Remove all highlights
```

### Semantic Face Names (Primitives)

| Primitive | Face IDs |
|-----------|----------|
| Box | `:top`, `:bottom`, `:front`, `:back`, `:left`, `:right` |
| Cylinder/Cone | `:top`, `:bottom`, `:side` |
| Sphere | `:surface` |

---

## Functional Face Operations

Modify faces using functional macros that return the modified mesh:

### attach-face

Move face vertices directly (no extrusion):

```clojure
(register b (box 20))

;; Move the top face up by 5 units
(register b (attach-face b :top (f 5)))

;; Multiple operations
(register b
  (attach-face b :top
    (f 10)
    (th 45)))
```

### clone-face

Extrude a face, creating new geometry:

```clojure
(register b (box 20))

;; Extrude top face outward
(register b (clone-face b :top (f 10)))

;; Create stepped extrusion
(register b
  (-> b
      (clone-face :top (f 5))
      (clone-face :top (inset 3) (f 5))))
```

Operations available inside attach-face/clone-face:
- `(f dist)` — move along face normal
- `(inset dist)` — shrink face inward
- `(scale factor)` — scale face uniformly

---

## Viewport Control

### Camera

```clojure
(fit-camera)                     ; Fit viewport to all visible geometry
```

### Visibility Toggles

From REPL:
```clojure
(show-lines)                     ; Show construction lines
(hide-lines)                     ; Hide construction lines
(lines-visible?)                 ; Check visibility
```

Desktop toolbar and VR panel have buttons for:
- Grid toggle
- Axes toggle
- Turtle indicator toggle
- Construction lines toggle

---

## Variables and Functions

Full Clojure available via SCI:

```clojure
(def wall-thickness 2)
(def radius 30)

(defn tube [outer inner height]
  (mesh-difference
    (cyl outer height)
    (cyl inner (+ height 1))))

(defn hole-pattern [n radius]
  (for [i (range n)]
    (do
      (reset)
      (th (* i (/ 360 n)))
      (f radius)
      (:position (get-turtle)))))

;; Access current turtle state
(get-turtle)                     ; Full turtle state map
(last-mesh)                      ; Most recently created mesh
```

---

## Complete Example

### Parametric Torus with Anchors

```clojure
;; Define parameters
(def tube-radius 5)
(def torus-radius 30)

;; Create square path for torus
(def square-path
  (path
    (dotimes [_ 4]
      (f (* 2 torus-radius))
      (th 90))))

;; Move to torus center, set joint mode
(reset [0 0 0])
(f torus-radius)                 ; Offset to path start
(joint-mode :round)              ; Smooth corners

;; Create torus
(register my-torus
  (extrude-closed (circle tube-radius) square-path))

;; Inspect faces
(list-faces my-torus)
(flash-face my-torus :side)
```

### Twisted Extrusion

```clojure
;; Star that twists 180 deg over its length
(register twisted-star
  (loft-n 64
    (star 5 20 8)
    #(rotate-shape %1 (* %2 180))
    (f 100)))
```

### Branching with State Stack

```clojure
;; Tree-like structure
(defn branch [depth length]
  (when (pos? depth)
    (f length)
    (push-state)
    (th 30)
    (branch (dec depth) (* length 0.7))
    (pop-state)
    (push-state)
    (th -30)
    (branch (dec depth) (* length 0.7))
    (pop-state)))

(reset)
(branch 5 20)
```

---

## Not Yet Implemented

- Dense syntax parser (string notation like "F20 TH90")
- Fillet/chamfer modifiers in paths
- Boolean ops with fillet/chamfer
- STL/OBJ/3MF export from REPL
- Backward movement command `(b dist)` — use `(f -dist)` instead

---

## File Structure

```
src/ridley/
├── editor/
│   ├── repl.cljs            # SCI evaluator + macros
│   └── codemirror.cljs      # Editor integration
├── turtle/
│   ├── core.cljs            # Turtle state + movement
│   ├── shape.cljs           # 2D shape definitions
│   ├── transform.cljs       # Shape transformations
│   └── text.cljs            # Text to shape conversion
├── geometry/
│   ├── primitives.cljs      # Box, sphere, cyl, cone
│   ├── operations.cljs      # Revolve
│   └── faces.cljs           # Face metadata
├── viewport/
│   ├── core.cljs            # Three.js rendering
│   └── xr.cljs              # WebXR/VR
├── manifold/core.cljs       # Manifold WASM booleans + hull
├── export/stl.cljs          # STL export
└── scene/registry.cljs      # Named object registry
```
