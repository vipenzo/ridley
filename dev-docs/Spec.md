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

;; Bezier to named anchor (uses both headings for smooth connection)
(bezier-to-anchor :name)
(bezier-to-anchor :name :steps 24)
(bezier-to-anchor :name :tension 0.5)   ; Control point distance (default 0.33)
```

### Bezier Along Path

Smooth bezier approximation of an existing turtle path, with C1 continuity at segment junctions:

```clojure
(bezier-as my-path)                          ; One cubic bezier per path segment
(bezier-as my-path :tension 0.5)             ; Control point distance factor (default 0.33)
(bezier-as my-path :steps 32)                ; Resolution per bezier segment
(bezier-as my-path :cubic true)              ; Catmull-Rom spline tangents
(bezier-as my-path :max-segment-length 20)   ; Subdivide long segments first
```

Works both in direct turtle mode and inside `path` recordings.

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

Anchors are named poses (position + orientation) that the turtle can navigate to.
They are created by resolving **marks** embedded in paths via `with-path`.

```clojure
;; Navigate to anchor (adopts anchor's heading)
(goto :name)

;; Orient toward anchor (no movement)
(look-at :name)

;; Create path from current position to anchor
(path-to :name)           ; Returns a path for use in extrude

;; Get anchor data
(get-anchor :name)        ; {:position [...] :heading [...] :up [...]}
```

Marks are defined inside `path` recordings (see below). To use them, pin the
path at the current turtle pose with `with-path`:

```clojure
(def skeleton (path (f 30) (mark :shoulder) (th 45) (f 20) (mark :elbow)))

;; with-path resolves marks relative to current turtle pose
(with-path skeleton
  (goto :shoulder)
  (bezier-to-anchor :elbow))

;; Nesting is supported — inner scope shadows, outer scope restores
(with-path outer-path
  (with-path inner-path
    (goto :inner-mark))      ; inner anchors available here
  (goto :outer-mark))         ; outer anchors restored here
```

---

## Path Recording

Paths record turtle movements as abstract data for later replay in extrusions.
Paths are not directly renderable — they are used to define extrusion trajectories,
embed marks for navigation, and compose complex curves.

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

### Marks Inside Paths

Marks record named poses within a path. They have no effect on geometry —
they simply tag the turtle's position and orientation at that point.

```clojure
(def arm (path (f 30) (mark :elbow) (th 45) (f 20) (mark :hand)))
```

### Follow (Path Splicing)

`follow` splices another path's commands into the current recording:

```clojure
(def segment (path (f 10) (mark :joint)))
(def full (path (f 20) (follow segment) (th 90) (f 10)))
;; full contains: f 20, f 10, mark :joint, th 90, f 10
```

### Quick Path

`quick-path` (alias `qp`) creates paths from compact notation:

```clojure
(quick-path 20 90 30 -45 10)  ; alternating: forward, turn, forward, turn, ...
(qp 20 90 30 -45 10)          ; same, shorter
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

(polygon n radius)                ; Regular n-sided polygon (e.g., 6 for hexagon)

(star n-points outer-r inner-r)  ; Star shape (n tips)

(stroke-shape my-path width)             ; Stroke a path into a 2D outline
(stroke-shape my-path width              ; With options
  :start-cap :round
  :end-cap :flat
  :join :miter
  :miter-limit 4)

(path-to-shape my-path)                  ; Convert 3D path to 2D shape (XY projection)
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
(scale factor)                   ; Scale attached mesh (inside attach/attach!)

(rotate-shape shape angle-deg)   ; Rotate around origin

(translate shape dx dy)          ; Translate shape by [dx dy]
(translate-shape shape dx dy)    ; Alias with explicit name

(scale-shape shape sx sy)        ; Non-uniform scale (explicit name)

(reverse-shape shape)            ; Reverse winding order (flip normals)

(morph shape-a shape-b t)        ; Interpolate between shapes (t: 0-1)
                                 ; Both must have same point count

(resample shape n)               ; Resample to n points (for morph compatibility)
```

### Shape Preview (Stamp)

Visualize a 2D shape at the current turtle position/orientation as a semi-transparent surface.
Shows exactly where the initial face of an `extrude` or `revolve` would appear.
Useful for debugging shape placement before committing to an operation.

```clojure
(stamp shape)                    ; Show shape surface at current turtle pose
```

Stamps are rendered as semi-transparent orange surfaces (visible from both sides).
Shapes with holes are correctly triangulated. Stamps do not modify turtle position or heading.

**Visibility control:**
```clojure
(show-stamps)                    ; Show stamp outlines
(hide-stamps)                    ; Hide stamp outlines
(stamps-visible?)                ; Check visibility
```

A "Stamps" toggle button is also available in the viewport toolbar.

### 2D Shape Booleans

Combine or modify 2D shapes before extrusion using Clipper2 boolean operations.
Shapes with holes are fully supported — results preserve holes, and all extrusion
operations (extrude, loft, revolve) correctly handle shapes with holes.

```clojure
(shape-union a b)                ; Combined outline of both shapes
(shape-difference a b)           ; Shape A with shape B cut out
(shape-intersection a b)         ; Overlapping region only
(shape-xor a b)                  ; Non-overlapping regions (returns vector of shapes)
```

**Offset (expand/contract):**

```clojure
(shape-offset shape delta)                   ; Expand (positive) or contract (negative)
(shape-offset shape delta :join-type :round) ; Round corners (default)
(shape-offset shape delta :join-type :square); Square corners
(shape-offset shape delta :join-type :miter) ; Sharp corners
```

**Examples:**

```clojure
;; Hollow tube (washer profile)
(def washer (shape-difference (circle 20) (circle 14)))
(register tube (extrude washer (f 40)))

;; L-shaped tube with hollow center
(def outer (shape-union (rect 20 40) (rect 40 20)))
(def inner (shape-offset outer -3))
(def l-tube (shape-difference outer inner))
(register bracket (extrude l-tube (f 10) (th 45) (f 15)))

;; Rounded shape
(def rounded-rect (shape-offset (rect 30 20) 3 :join-type :round))
```

**Notes:**
- Results may contain holes (e.g., `shape-difference` of overlapping shapes)
- `shape-xor` returns a **vector of shapes** (since XOR can produce disconnected regions)
- Vectors of shapes are accepted by `shape-offset`, `extrude`, `loft`, `bloft`, `revolve`, and `stamp`
- Holes are automatically detected from winding direction
- All shape transforms (`scale`, `rotate-shape`, `translate`, `morph`) propagate holes
- Internally uses integer coordinates (×1000 scale) for precision

---

## 3D Primitives

Primitives return mesh data at current turtle position:

```clojure
(box size)                       ; Cube
(box w d l)                      ; Rectangular box: w=right, d=up, l=heading

(sphere radius)                  ; Sphere, uses resolution setting
(sphere radius segments rings)   ; Custom resolution

(cyl radius height)              ; Cylinder, height along UP axis
(cyl radius height segments)     ; Custom segments

(cone r1 r2 height)              ; Frustum, height along UP axis (r1=bottom, r2=top)
(cone r1 r2 height segments)     ; Use r2=0 for proper cone
```

**Orientation:** `box` extends along heading (like extrude). `cyl` and `cone` extend along the turtle's UP axis (upright by default). At default pose (heading +X, up +Z): box extends along X, cyl/cone extend along Z.

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

;; Two-shape loft: taper between two different shapes
(register taper
  (loft (circle 20)
    (circle 10)                     ; End shape (must be same point count)
    (f 40)))

;; With custom step count for smoother result
(register smooth-cone
  (loft-n 32 (circle 20)
    #(scale %1 (- 1 %2))
    (f 30)))
```

Default: 16 steps. Returns mesh without side effects.

### Bloft (Bezier-safe Loft)

For paths with tight curves (like `bezier-as`), regular `loft` can produce self-intersecting geometry. `bloft` handles this by detecting ring intersections and bridging them with convex hulls, then unioning all pieces into a manifold mesh.

```clojure
;; Basic usage - same signature as loft
(register tube
  (bloft (circle 4)
    identity
    (path (bezier-as my-curved-path))))

;; With taper
(register tapered-tube
  (bloft (circle 8)
    #(scale %1 (- 1 (* 0.5 %2)))    ; Taper to half size
    (path (bezier-as (branch-path 30)))))

;; More steps for smoother result
(register smooth-tube
  (bloft-n 64 (circle 4)
    identity
    my-bezier-path))
```

**When to use `bloft` vs `loft`:**
- Use `loft` for straight paths or gentle curves — faster
- Use `bloft` for tight bezier curves that might self-intersect — slower but correct

**Performance note:** `bloft` can take several seconds for complex paths at high resolution. The density of bezier sampling is controlled by `(resolution :n ...)`:
- Low values (e.g., `:n 10`) → fast draft preview (may show visual artifacts)
- High values (e.g., `:n 60`) → smooth final render (slower)

### Revolve

```clojure
(revolve shape)                  ; Full 360 deg revolution around turtle heading
(revolve shape angle)            ; Partial revolution (degrees)
```

The profile shape is interpreted as:
- 2D X = radial distance from axis (perpendicular to heading)
- 2D Y = position along axis (in heading direction)

Use `translate-shape` to offset the profile from the axis for hollow shapes (e.g., torus).

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

Resolve self-intersections (useful for loft/extrude that produce overlapping geometry):

```clojure
(solidify mesh)                  ; Pass through Manifold to clean self-intersections
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
(text-shape "Hello")                    ; Returns vector of shapes (with holes)

;; With size
(text-shape "Hello" :size 30)           ; Larger text

;; Extrude for 3D text (extrude handles vector of shapes)
(register title (extrude (text-shape "RIDLEY" :size 40) (f 5)))

;; Individual character shapes (one shape per character)
(text-shapes "ABC" :size 20)            ; Returns vector of shapes

;; Single character shape
(char-shape "A" font size)              ; Returns shape for one character

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

Named objects persist across evaluations. The registry holds two kinds of objects:

- **Renderable** (meshes, panels): have visibility, appear in the viewport
- **Abstract** (paths, shapes): data-only, no visibility concept

```clojure
;; Register: define var, add to registry, show on first registration
(register torus (extrude-closed (circle 5) square-path))
;; Creates 'torus' var, registers as :torus, makes visible

;; r is a short alias for register
(r torus (extrude-closed (circle 5) square-path))

;; Register with :hidden flag (registers but doesn't show)
(register torus (extrude-closed (circle 5) square-path) :hidden)

;; Register abstract objects (paths, shapes — no visibility)
(register skeleton (path (f 30) (mark :shoulder)))
(register profile (shape (f 10) (th 90) (f 5)))

;; Show/hide by name (keyword) or reference (renderable only)
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

;; Get info (includes bounds)
(info :torus)                    ; {:name :torus :visible true :vertices n :faces n :bounds {...}}
(info torus)                     ; By reference

;; Bounding box
(bounds :torus)                  ; {:min [x y z] :max [x y z] :center [x y z] :size [sx sy sz]}
(bounds torus)                   ; By reference

;; Dimension helpers
(height :torus)                  ; Z dimension (size)
(width :torus)                   ; X dimension (size)
(depth :torus)                   ; Y dimension (size)
(top :torus)                     ; Max Z coordinate
(bottom :torus)                  ; Min Z coordinate
(center-x :torus)                ; X of centroid
(center-y :torus)                ; Y of centroid
(center-z :torus)                ; Z of centroid

;; Get raw mesh data
(mesh :torus)                    ; By name
(mesh torus)                     ; Identity (returns mesh itself)
```

### Hierarchical Assemblies

Use `register` with a map literal inside `with-path` to create articulated assemblies with automatic qualified names and link inference:

```clojure
(def body-sk (path (mark :shoulder-r) (rt 7) (mark :shoulder-l) (rt -14)))
(def arm-sk (path (mark :top) (f 15) (mark :elbow)))

(with-path body-sk
  (register puppet
    {:torso (box 12 6 20)
     :r-arm (do (goto :shoulder-r)
                (with-path arm-sk
                  {:upper (cyl 3 15)
                   :lower (do (goto :elbow) (cyl 2.5 12))}))}))

;; Creates:
;;   :puppet/torso                              (root)
;;   :puppet/r-arm/upper → :puppet/torso at :shoulder-r
;;   :puppet/r-arm/lower → :puppet/r-arm/upper at :elbow
;; All links have :inherit-rotation true
;; Skeletons auto-attached to first mesh in each with-path frame
```

Show/hide supports prefix matching for assembly subtrees:

```clojure
(hide :puppet/r-arm)             ; Hide all meshes under :puppet/r-arm
(show :puppet)                   ; Show all puppet parts
(hide :puppet/r-arm/hand/thumb)  ; Hide single part
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

## Color and Material

Set color and material properties for subsequently created meshes:

```clojure
(color 0xff0000)                 ; Set color by hex value (red)
(color r g b)                    ; Set color by RGB components (0-255)

(material :metalness 0.8         ; Set material properties
          :roughness 0.2)

(reset-material)                 ; Reset to default material
```

Color and material are stored in turtle state and applied to all meshes created after the call (primitives, extrusions, etc.).

---

## 3D Panels (Text Billboards)

Panels are 3D text billboards positioned in the scene. They display text content and can be used for labels, debugging output, or UI elements.

```clojure
;; Create a panel at current turtle position
(register label (panel 40 20))

;; With options
(register label (panel 40 20
  :font-size 3
  :bg 0x333333cc
  :fg 0xffffff
  :padding 2
  :line-height 1.4))

;; Set content
(out :label "Hello World")
(out label "Hello World")        ; By reference

;; Append content
(append :label "\nMore text")

;; Clear content
(clear :label)
```

Panels support `show`/`hide`, `register`, `attach`/`attach!` like meshes.

---

## Mesh Transformation Macros

### attach

Transform a mesh or panel, returning a new mesh (functional — original unchanged):

```clojure
(register b (box 20))

;; Move the entire mesh (returns new mesh)
(register b (attach b (f 10) (th 45)))

;; Create a transformed copy with a different name
(register b2 (attach b (th 45) (f 10)))

;; Works with panels too
(register label (attach label (f 20) (th 90)))
```

### attach!

Transform a registered mesh in-place by keyword. Shortcut for `(register name (attach name ...))`:

```clojure
(register b (box 20))

;; Move the registered mesh (updates registry)
(attach! :b (f 10) (th 45))

;; Equivalent to:
(register b (attach b (f 10) (th 45)))
```

Only accepts keywords (registered names). Throws an error if the name is not registered.

Operations available inside `attach`/`attach!`: `f`, `th`, `tv`, `tr`, `move-to`, `play-path`.

### play-path

Replay a recorded path's movements inside `attach`/`attach!`. Solves the problem that functions returning paths capture global `f`/`th`/`tv` bindings, not the rebound attach versions:

```clojure
(defn branch-path [l]
  (path (tv 90) (f (/ l 8)) (tv -90) (f l)))

;; Use inside attach:
(register Y (attach (sphere 3) (play-path (branch-path 30))))

;; Combine with additional movements:
(attach! :Y (play-path my-path) (f 10) (th 45))
```

### move-to

Move to another object's position and adopt its orientation (inside `attach`/`attach!`):

```clojure
(move-to :name)            ; move to pose position + adopt heading/up (default)
(move-to :name :center)    ; move to centroid only, keep current heading
```

After `(move-to :A)`, the turtle is at A's position with A's orientation. "Forward" means A's forward, "up" means A's up. This makes relative positioning work correctly even if A has been rotated.

```clojure
(register base (box 40))
(attach! :base (th -90) (f 50) (th 90))   ; move base to X=50

(register sfera (sphere 10))
;; Place sphere on top of base (wherever base is now)
(attach! :sfera (move-to :base) (tv 90) (f 30) (tv -90))
```

Use `move-to` whenever positioning relative to another object. Use `:center` mode when you only need centroid alignment without orientation change.

---

## Lateral Movement

Pure translation along the turtle's local axes. No heading/up change, no ring generation.

| Command | Description |
|---------|-------------|
| `(u dist)` | Move along up axis |
| `(d dist)` | Move opposite to up axis |
| `(rt dist)` | Move along right axis (heading × up) |
| `(lt dist)` | Move opposite to right axis |

Blocked inside `path`, `extrude`, `loft` (would produce degenerate rings). Allowed at top level, inside `attach`/`attach!`, and in animation spans.

```clojure
(f 50) (u 30) (th 180)        ; forward, up, face back
(attach! :gear (rt 10))        ; slide gear right
```

---

## Animation System

Define timeline-based animations that preprocess turtle commands into per-frame pose arrays for O(1) playback.

### Defining Animations

```clojure
;; (anim! :name duration :target [options] spans...)
;; Options: :loop, :loop-reverse, :loop-bounce, :fps N

;; Simple rotation
(anim! :spin 3.0 :gear
  (span 1.0 :linear (tr 360)))

;; Multi-span with easing
(anim! :entrance 8.0 :gear
  (span 0.10 :out (f 6))
  (span 0.80 :linear (tr 720))
  (span 0.10 :in (f -6)))

;; Camera animation (orbital mode — automatic when target is :camera)
;; Commands are reinterpreted as cinematic camera operations:
;;   rt/lt  = orbit horizontally around pivot (degrees)
;;   u/d    = orbit vertically around pivot (degrees)
;;   f      = dolly toward/away from pivot (distance)
;;   th/tv  = pan/tilt look direction (degrees)
;;   tr     = roll (degrees)
;; Pivot = current OrbitControls target at registration time
(anim! :cam-orbit 5.0 :camera
  (span 1.0 :in-out (rt 360)))

;; Loop modes
(anim! :spin-forever 2.0 :gear :loop          ;; forward: 0→1, 0→1, ...
  (span 1.0 :linear (tr 360)))
(anim! :unwind 2.0 :gear :loop-reverse        ;; reverse: 1→0, 1→0, ...
  (span 1.0 :linear (tr 360)))
(anim! :sway 2.0 :gear :loop-bounce           ;; bounce: 0→1→0→1, ...
  (span 1.0 :in-out (tr 90)))
```

### Span

A timeline segment with weight, easing, and turtle commands:

```clojure
(span weight easing & commands)
(span weight easing :ang-velocity N & commands)
```

- **weight**: Fraction of total duration (spans are normalized to sum to 1.0)
- **easing**: `:linear`, `:in`, `:out`, `:in-out`, `:in-cubic`, `:out-cubic`, `:in-out-cubic`, `:spring`, `:bounce`
- **:ang-velocity N**: Controls rotation timing. Default 1 (rotations are visible). 0 = instantaneous. N > 0 means 360° takes as long as `(f N)`.
- **commands**: `(f dist)`, `(th angle)`, `(tv angle)`, `(tr angle)`, `(u dist)`, `(d dist)`, `(rt dist)`, `(lt dist)`, `(parallel cmd1 cmd2 ...)`

### Parallel Commands

Wrap commands in `parallel` to execute them simultaneously over the same frames:

```clojure
;; Sequential: first orbit, then elevate
(span 1.0 :linear (rt 360) (u 90))

;; Parallel: diagonal orbit (both at the same time)
(span 1.0 :linear (parallel (rt 360) (u 90)))
```

A parallel group's frame allocation = max of its sub-commands. All sub-commands are applied at the same fractional progress for each frame.

### Procedural Animations

Procedural animations call a mesh-generating function every frame. The function receives eased `t` (0→1) and returns a new mesh:

```clojure
;; (anim-proc! :name duration :target easing gen-fn)
;; (anim-proc! :name duration :target easing :loop gen-fn)
;; (anim-proc! :name duration :target easing :loop-reverse gen-fn)
;; (anim-proc! :name duration :target easing :loop-bounce gen-fn)

;; Sphere that grows
(register blob (sphere 1))
(anim-proc! :grow 3.0 :blob :out
  (fn [t] (sphere (+ 1 (* 19 t)))))

;; Arm that bends
(register arm (extrude (circle 2) (f 15) (f 12)))
(anim-proc! :bend 2.0 :arm :in-out
  (fn [t] (extrude (circle 2) (f 15) (th (* t 90)) (f 12))))

;; Pulsing box (looping)
(anim-proc! :pulse 1.0 :heart :in-out :loop
  (fn [t]
    (let [s (+ 1.0 (* 0.3 (sin (* t PI 2))))]
      (box (* 10 s) (* 10 s) (* 10 s)))))

;; Bounce: smooth grow/shrink (t goes 0→1→0→1...)
(anim-proc! :breathe 2.0 :blob :in-out :loop-bounce
  (fn [t] (sphere (+ 5 (* 15 t)))))
```

Performance: `gen-fn` runs every frame (60fps). Keep it fast — avoid `mesh-union`/`mesh-difference` per frame. Keep face count constant when possible for the fast path.

### Mesh Anchors

Associate a path's marks as named anchor points on a mesh:

```clojure
;; Define a path with marks
(def arm-sk (path (mark :top) (f 15) (mark :elbow) (f 12) (mark :wrist)))

;; Register mesh and attach anchors
(register upper (extrude (circle 1.5) (f 15)))
(attach-path :upper arm-sk)
;; :upper now has :anchors {:top {...} :elbow {...} :wrist {...}}
```

Anchors store position, heading, and up vectors relative to the mesh's creation-pose. They are resolved on-demand at playback time.

### Target Linking

Link a child target to a parent so the child inherits the parent's position delta at playback:

```clojure
(link! :camera :box)        ; camera follows box's movement
(link! :moon :planet)       ; moon inherits planet's translation
(unlink! :camera)           ; remove link

;; Enhanced: link at specific parent anchor
(link! :lower :upper :at :elbow)

;; With child attachment point
(link! :lower :upper :at :elbow :from :top)

;; With rotation inheritance (child rotates with parent)
(link! :lower :upper :at :elbow :inherit-rotation true)
```

Preprocessing is unchanged — child frames are computed as if the parent is stationary. The link adds translation (and optionally rotation) at runtime. Targets are processed in topological order (parents before children).

### Playback Control

```clojure
(play! :spin)           ; start playing
(play!)                 ; play all
(pause! :spin)          ; pause
(stop! :spin)           ; stop and reset
(stop-all!)             ; stop all
(seek! :spin 0.5)       ; jump to 50%
(anim-list)             ; list animations with status/type
```

### Easing

```clojure
(ease :in-out 0.5)      ; => eased value (0-1)
```

---

## STL Export

Export meshes to STL files (triggers browser download):

```clojure
(export :torus)                  ; By registered name
(export torus)                   ; By mesh reference
(export :torus :cube)            ; Multiple by name
(export torus cube)              ; Multiple by reference
(export parts)                   ; Export all meshes in vector/map
(export parts 2)                 ; Export specific element by index
(export robot :hand)             ; Export specific element by key
```

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
(turtle-position)                ; Current position [x y z]
(turtle-heading)                 ; Current heading [x y z]
(turtle-up)                      ; Current up vector [x y z]
(attached?)                      ; true if turtle is attached to a mesh
(last-mesh)                      ; Most recently created mesh

;; Print output (captured and shown in REPL output)
(println "Value:" x)             ; Print with newline
(print "no newline")             ; Print without newline
(prn {:a 1})                     ; Print data structure
(log "debug" x)                  ; Output to browser console only
(T "label" expr)                 ; Tap: prints "label: value", returns value
```

### Math Functions

Standard math functions are available without import:

```clojure
PI                               ; 3.14159...
(sin x) (cos x)                  ; Trigonometric (radians)
(sqrt x) (pow x n)               ; Power functions
(abs x)                          ; Absolute value
(ceil x) (floor x) (round x)    ; Rounding
(atan2 y x)                     ; Two-argument arctangent
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
- OBJ/3MF export (STL export is available via `export`)
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
├── clipper/core.cljs        # Clipper2 2D shape booleans + offset
├── manifold/core.cljs       # Manifold WASM booleans + hull
├── export/stl.cljs          # STL export
├── scene/
│   ├── registry.cljs        # Named object registry
│   └── panel.cljs           # 3D text panel data
└── voice/
    ├── core.cljs            # Voice input orchestrator
    ├── speech.cljs           # Web Speech API wrapper
    ├── state.cljs            # Voice state (mode, language)
    ├── parser.cljs           # Deterministic command parser
    ├── i18n.cljs             # IT/EN voice command dictionary
    ├── modes/
    │   └── structure.cljs    # Structure mode commands
    └── panel.cljs            # Voice status panel UI
```
