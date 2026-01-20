# Ridley — DSL Specification

## Overview

Ridley scripts are valid Clojure code executed in a pre-configured environment using SCI (Small Clojure Interpreter). All DSL functions are available without imports.

**Two-phase evaluation:**
1. **Explicit phase**: Full Clojure for definitions, functions, data
2. **Implicit phase**: Turtle commands that mutate global state

---

## Dense Syntax (Not Yet Implemented)

> **Note**: Dense syntax is specified but not yet implemented. Use functional form only.

For paths and shapes, a compact string notation is planned alongside the functional form.

### Planned Equivalence

```clojure
;; Functional form (IMPLEMENTED)
(path
  (f 20)
  (th 90)
  (f 10))

;; Dense form (NOT YET IMPLEMENTED)
(path "F20 TH90 F10")

;; Mixed form (NOT YET IMPLEMENTED)
(path
  "F20"
  (th angle)  ; computed value
  "F10")
```

### Planned Dense Syntax Grammar

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

---

## Turtle Commands

### Movement

| Function | Description |
|----------|-------------|
| `(f dist)` | Move forward |
| `(b dist)` | Move backward (not yet implemented) |

### Rotation

| Function | Description |
|----------|-------------|
| `(th angle)` | Turn horizontal (yaw), degrees |
| `(tv angle)` | Turn vertical (pitch), degrees |
| `(tr angle)` | Turn roll, degrees |

All rotation commands support **pending rotations in extrude mode** for automatic fillet generation at corners.

### Arc Commands

Draw smooth arcs by combining movement with rotation:

```clojure
(arc-h radius angle)             ; Horizontal arc (turns around up axis)
(arc-h radius angle :steps 24)   ; With explicit step count

(arc-v radius angle)             ; Vertical arc (turns around right axis)
(arc-v radius angle :steps 24)   ; With explicit step count
```

- `arc-h`: turtle moves in a circular arc horizontally, like `f` + `th` combined
- `arc-v`: turtle moves in a circular arc vertically, like `f` + `tv` combined
- Positive angle = standard rotation direction (left for arc-h, up for arc-v)
- Arc length = radius × angle_radians
- Steps default to resolution setting (see Resolution below)

**Examples:**
```clojure
(arc-h 10 90)                    ; Quarter circle turning left, radius 10
(arc-h 10 -90)                   ; Quarter circle turning right

;; S-curve
(arc-h 10 90)
(arc-h 10 -90)

;; Spiral
(extrude (circle 3)
  (dotimes [_ 8]
    (arc-h 20 90)))
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

- With 0 control points: auto-generates smooth curve starting along current heading
- With 1 control point: quadratic bezier
- With 2 control points: cubic bezier
- Steps default to resolution setting

**Examples:**
```clojure
;; Smooth curve to a point
(bezier-to [30 30 0])

;; Curve to anchor
(mark :target [50 0 20])
(reset)
(bezier-to-anchor :target)

;; With explicit control points
(bezier-to [30 0 0] [10 20 0] [20 20 0])   ; S-curve
```

### Pen Control

| Function | Description |
|----------|-------------|
| `(pen :off)` | Stop drawing (pen up) |
| `(pen :on)` | Draw lines (default) |
| `(pen-up)` | Legacy alias for `(pen :off)` |
| `(pen-down)` | Legacy alias for `(pen :on)` |

### State Stack

```clojure
(push-state)              ; Save position, heading, up, pen-mode
(pop-state)               ; Restore most recent saved state
(clear-stack)             ; Clear stack without restoring
```

Useful for branching or temporary movements. Meshes and geometry created between push and pop are kept.

### Reset

```clojure
(reset)                              ; Reset to origin, facing +X, up +Z
(reset [x y z])                      ; Reset to position, default orientation
(reset [x y z] :heading [hx hy hz])  ; Position + heading
(reset [x y z] :heading [...] :up [ux uy uz])  ; Full control
```

Resets turtle pose without clearing accumulated geometry/meshes.

### Resolution (Curve Quality)

Control the resolution of curves and circular primitives globally, inspired by OpenSCAD's `$fn`, `$fa`, `$fs`:

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
- Round joints during extrusion — interpolation steps

**Workflow:**
```clojure
;; Fast iteration with low resolution
(resolution :n 8)
;; ... design ...

;; High quality for final export
(resolution :n 32)
```

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

### Attachment System

Attach to meshes or faces to manipulate them with turtle commands:

```clojure
;; Attach to a mesh's creation pose
(def b (stamp (box 20)))
(attach b)                ; Push state, move to mesh origin
(f 10)                    ; Move the entire mesh
(th 45)                   ; Rotate turtle (affects subsequent operations)
(detach)                  ; Pop state, return to previous position

;; Attach to a specific face
(attach-face b :top)      ; Push state, move to face center, heading = normal
(f 10)                    ; Extrude face outward by 10 units
(f 5)                     ; Continue extruding (stacks)
(detach)                  ; Return to previous position

;; Inward extrusion (negative distance)
(attach-face b :top)
(f -5)                    ; Extrude inward (creates pocket)
(detach)

;; Check attachment status
(attached?)               ; Returns true if attached

;; Inset: create smaller face with surrounding border
(attach-face b :top)
(inset 3)                 ; Create smaller face 3 units from edges
(f 5)                     ; Extrude the inset face
(detach)
```

**Behavior:**
- `attach` and `attach-face` automatically push state (like `push-state`)
- `detach` pops state and clears attachment
- When attached to a **mesh**: `(f dist)` translates all vertices
- When attached to a **face**: `(f dist)` extrudes the face along its normal
- Face heading = outward normal, so positive `f` extrudes outward
- `(inset dist)` creates a smaller face inside, connected by trapezoid sides

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
(extrude (circle 5) my-path)
```

---

## 2D Shapes

Shapes are 2D profiles used for extrusion:

```clojure
(circle radius)                  ; Circle, 32 segments default
(circle radius segments)         ; Custom resolution

(rect width height)              ; Rectangle centered at origin

(polygon [[x1 y1] [x2 y2] ...])  ; Custom polygon from points

(star n-points outer-r inner-r)  ; Star shape (n tips)
```

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

Primitives return mesh data at origin. Use `stamp` to place at turtle position:

```clojure
;; Create mesh data (at origin)
(box size)                       ; Cube
(box sx sy sz)                   ; Rectangular box

(sphere radius)                  ; Sphere, 16x12 default resolution
(sphere radius segments rings)   ; Custom resolution

(cyl radius height)              ; Cylinder, 24 segments default
(cyl radius height segments)     ; Custom segments

(cone r1 r2 height)              ; Frustum (r1=bottom, r2=top)
(cone r1 r2 height segments)     ; Use r2=0 for proper cone
```

### Placing Primitives

```clojure
;; stamp: place at turtle position and show in viewport
(stamp (box 10))                 ; Returns transformed mesh

;; make: place at turtle position without showing (for boolean ops)
(make (box 10))                  ; Returns transformed mesh, hidden
```

> **Note**: `stamp` and `make` only work with primitives. For extrusion results, use `register`.

---

## Generative Operations

### Extrude

Extrude stamps a 2D shape and sweeps it along movements:

```clojure
;; Single movement
(extrude (circle 15) (f 30))

;; Multiple movements (auto-wrapped in path)
(extrude (circle 15)
  (f 20)
  (th 45)
  (f 20))

;; Along a recorded path
(extrude (circle 15) my-path)

;; Along path to anchor
(extrude (circle 5) (path-to :target))
```

**Returns the created mesh** — can be bound with `def` or passed to `register`.

#### Joint Modes

Control corner geometry during extrusion:

```clojure
(joint-mode :flat)      ; Default - sharp corners
(joint-mode :round)     ; Smooth rounded corners (4 interpolation steps)
(joint-mode :tapered)   ; Beveled/tapered corners
```

### Extrude Closed

For closed loops (torus-like shapes):

```clojure
;; Path should return to starting point
(def square-path (path (dotimes [_ 4] (f 20) (th 90))))

(extrude-closed (circle 5) square-path)
```

Creates a manifold mesh with no caps — last ring connects to first.

### Loft

Extrude with shape transformation based on progress:

```clojure
;; Transform function receives (shape t) where t: 0→1
(loft (circle 20)
  #(scale %1 (- 1 %2))           ; Cone: scale from 1 to 0
  (f 30))

;; Twist while extruding
(loft (rect 20 10)
  #(rotate-shape %1 (* %2 90))   ; 90° twist
  (f 30))

;; With custom step count for smoother result
(loft-n 32 (circle 20)
  #(scale %1 (- 1 %2))
  (f 30))
```

Default: 16 steps. Returns the created mesh.

### Sweep (Between Two Shapes)

Create mesh between two shapes at different positions:

```clojure
(sweep (circle 5)
  (do
    (f 10)
    (th 90)
    (circle 8)))      ; Returns second shape at new position
```

First shape stamped at current position, body moves turtle, last expression returns second shape.

### Revolve (Legacy)

```clojure
(revolve shape axis)             ; Full 360° revolution
```

> **Note**: Located in `geometry/operations`, may have different API.

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

> **Not yet implemented**: `:fillet` and `:chamfer` parameters on boolean ops

---

## Convex Hull

Compute the convex hull of one or more meshes:

```clojure
;; Hull of multiple meshes - creates smallest convex shape containing all
(def s1 (stamp (sphere 10)))
(f 30)
(def s2 (stamp (sphere 10)))
(mesh-hull s1 s2)                ; Creates a "capsule" shape

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
(stamp (extrude (text-shape "RIDLEY" :size 40) (f 5)))

;; Individual character shapes
(text-shapes "ABC" :size 20)            ; Returns vector of shapes

;; Single character
(char-shape "A" (get-default-font) 50)

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

Place 3D text along a curved path, with each letter oriented tangent to the curve:

```clojure
;; Define a curved path
(def curve (path (dotimes [_ 40] (f 2) (th 3))))

;; Place text along the curve
(text-on-path "Hello" curve :size 15 :depth 3)
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

### Examples

```clojure
;; Centered text on arc
(def arc (path (dotimes [_ 12] (f 8) (th 15))))
(text-on-path "CENTERED" arc :size 12 :depth 4 :align :center)

;; Text around a circle (wrapping)
(def circle-path (path (dotimes [_ 36] (f 5) (th 10))))
(text-on-path "RIDLEY • RIDLEY • " circle-path
  :size 8 :depth 2 :overflow :wrap)

;; Scaled to fit
(def short-path (path (f 50)))
(text-on-path "LONG TEXT HERE" short-path
  :size 10 :depth 2 :overflow :scale)
```

### Path Utilities

```clojure
;; Visualize a path (draws it with the turtle)
(follow-path curve)

;; Get total arc length of a path
(path-total-length curve)           ; => 80.0 (sum of forward distances)

;; Sample path at a specific distance
(sample-path-at-distance curve 40)
;; => {:position [x y z] :heading [hx hy hz] :up [ux uy uz]}
```

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

## Viewport Control

### Camera

```clojure
(fit-camera)                     ; Fit viewport to all visible geometry
```

**Behavior notes:**
- Camera no longer resets on REPL commands (stamp, show, hide)
- Camera only resets on Cmd+Enter/Play (full rebuild)
- Use `fit-camera` for explicit camera reset

### Visibility

```clojure
(show mesh-or-name)              ; Add to viewport
(hide mesh-or-name)              ; Remove from viewport
(show-all)                       ; Show everything
(hide-all)                       ; Hide everything
```

---

## Export

```clojure
;; Export by registered name
(export :torus)                  ; Saves "torus.stl"

;; Export by mesh reference
(export torus)                   ; Saves "export.stl"

;; Export multiple
(export :torus :cube)            ; Saves "torus-cube.stl"
(export torus cube)              ; Saves "export.stl"

;; Export all visible
(export (objects))               ; Export all visible registered objects
```

Currently only STL format (binary).

> **Not yet implemented**: ASCII STL option, OBJ, 3MF formats

---

## Variables and Functions

Full Clojure available via SCI:

```clojure
(def wall-thickness 2)
(def radius 30)

(defn tube [outer inner height]
  (mesh-difference
    (stamp (cyl outer height))
    (stamp (cyl inner (+ height 1)))))

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

;; Export
(export :my-torus)
```

### Twisted Extrusion

```clojure
;; Star that twists 180° over its length
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

## Implementation Status

### Fully Implemented
- Turtle movement (f, th, tv, tr)
- Arc commands (arc-h, arc-v)
- Bezier commands (bezier-to, bezier-to-anchor)
- Resolution control (resolution :n/:a/:s)
- Pen control (:on, :off)
- State stack (push-state, pop-state, clear-stack)
- Anchors (mark, goto, look-at, path-to)
- Attachment system (attach, attach-face, detach, inset)
- Path recording
- Path utilities (follow-path, path-total-length, sample-path-at-distance)
- 2D shapes (circle, rect, polygon, star, text-shape)
- Text shapes via opentype.js (text-shape, text-shapes, char-shape)
- Text on path (text-on-path)
- Shape transforms (scale, rotate, translate, morph, resample)
- 3D primitives (box, sphere, cyl, cone)
- Extrude (open and closed)
- Loft with transforms
- Sweep between shapes
- Joint modes (flat, round, tapered)
- Boolean operations (via Manifold WASM)
- Convex hull (mesh-hull)
- Scene registry
- Face operations
- Face highlighting (highlight-face, flash-face)
- Camera control (fit-camera)
- STL export

### Not Yet Implemented
- Dense syntax parser (string notation)
- Backward movement (b)
- Fillet/chamfer modifiers in paths
- Boolean ops with fillet/chamfer
- Face-based drawing (pen :3d, pen with face selection)
- Export formats: OBJ, 3MF
- VR/AR DSL configuration (set-vr function for scale, grid, ruler, table-height)

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
│   ├── path.cljs            # Path utilities
│   └── text.cljs            # Text to shape conversion
├── geometry/
│   ├── primitives.cljs      # Box, sphere, cyl, cone
│   ├── operations.cljs      # Revolve, legacy sweep/loft
│   └── faces.cljs           # Face metadata
├── viewport/
│   ├── core.cljs            # Three.js rendering
│   └── xr.cljs              # WebXR/VR
├── manifold/core.cljs       # Manifold WASM booleans + hull
├── export/stl.cljs          # STL export
└── scene/registry.cljs      # Named object registry
```
