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
```

**Behavior:**
- `attach` and `attach-face` automatically push state (like `push-state`)
- `detach` pops state and clears attachment
- When attached to a **mesh**: `(f dist)` translates all vertices
- When attached to a **face**: `(f dist)` extrudes the face along its normal
- Face heading = outward normal, so positive `f` extrudes outward

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

(translate shape dx dy)          ; Move shape

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
- Pen control (:on, :off)
- State stack (push-state, pop-state, clear-stack)
- Anchors (mark, goto, look-at, path-to)
- Attachment system (attach, attach-face, detach)
- Path recording
- 2D shapes (circle, rect, polygon, star)
- Shape transforms (scale, rotate, translate, morph, resample)
- 3D primitives (box, sphere, cyl, cone)
- Extrude (open and closed)
- Loft with transforms
- Sweep between shapes
- Joint modes (flat, round, tapered)
- Boolean operations (via Manifold WASM)
- Scene registry
- Face operations
- Face highlighting (highlight-face, flash-face)
- Camera control (fit-camera)
- STL export

### Not Yet Implemented
- Dense syntax parser (string notation)
- Backward movement (b)
- Curves (arc-h, arc-v, bezier-to)
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
│   └── path.cljs            # Path utilities
├── geometry/
│   ├── primitives.cljs      # Box, sphere, cyl, cone
│   ├── operations.cljs      # Revolve, legacy sweep/loft
│   └── faces.cljs           # Face metadata
├── viewport/
│   ├── core.cljs            # Three.js rendering
│   └── xr.cljs              # WebXR/VR
├── manifold/core.cljs       # Manifold WASM booleans
├── export/stl.cljs          # STL export
└── scene/registry.cljs      # Named object registry
```
