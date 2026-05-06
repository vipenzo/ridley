# Ridley DSL Specification

## 1. Overview

Ridley scripts are valid Clojure code executed in a pre-configured environment using SCI (Small Clojure Interpreter). All DSL functions are available without imports.

**Evaluation model:**
- REPL commands are executed in a persistent environment.
- Turtle pose (position, heading, up) persists between commands.
- Turtle lines (pen traces) are cleared on each command.
- Meshes must be explicitly registered with `register` to be visible.

---

## 2. Turtle

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

### Arcs

Draw smooth arcs by combining movement with rotation:

```clojure
(arc-h radius angle)             ; Horizontal arc (turns around up axis)
(arc-h radius angle :steps 24)   ; With explicit step count

(arc-v radius angle)             ; Vertical arc (turns around right axis)
(arc-v radius angle :steps 24)   ; With explicit step count
```

- `arc-h`: turtle moves in a circular arc horizontally.
- `arc-v`: turtle moves in a circular arc vertically.
- Positive angle = standard rotation direction.
- Steps default to resolution setting.

**Examples:**

```clojure
(arc-h 10 90)                    ; Quarter circle turning left, radius 10
(arc-h 10 -90)                   ; Quarter circle turning right

;; S-curve
(arc-h 10 90)
(arc-h 10 -90)
```

### Bezier

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

**Bezier along path.** Smooth bezier approximation of an existing turtle path, with C1 continuity at segment junctions:

```clojure
(bezier-as my-path)                          ; One cubic bezier per path segment
(bezier-as my-path :tension 0.5)             ; Control point distance factor (default 0.33)
(bezier-as my-path :steps 32)                ; Resolution per bezier segment
(bezier-as my-path :cubic true)              ; Catmull-Rom spline tangents
(bezier-as my-path :max-segment-length 20)   ; Subdivide long segments first
```

Works both in direct turtle mode and inside `path` recordings.

### Pen control

| Function | Description |
|----------|-------------|
| `(pen :off)` | Stop drawing (pen up) |
| `(pen :on)` | Draw lines (default) |

### Reset

```clojure
(reset)                              ; Reset to origin, facing +X, up +Z
(reset [x y z])                      ; Reset to position, default orientation
(reset [x y z] :heading [hx hy hz])  ; Position + heading
(reset [x y z] :heading [...] :up [ux uy uz])  ; Full control
```

Resets turtle pose without clearing accumulated geometry/meshes.

### Resolution

Control the resolution of curves and circular primitives globally:

```clojure
(resolution :n 32)               ; Fixed number of segments (default 16)
(resolution :a 5)                ; Maximum angle per segment (degrees)
(resolution :s 0.5)              ; Maximum segment length (units)
```

**Affected operations:**
- `arc-h`, `arc-v`: arc step count.
- `bezier-to`, `bezier-to-anchor`: bezier step count.
- `bezier-as`: per-segment step count when smoothing a path.
- `circle`: circle segment count.
- `sphere`, `cyl`, `cone`: circumferential segments.
- `revolve`: number of revolution segments (and rings for shape-fn revolves).
- `bloft`: ring count along the path (combines path length and total angle).
- Round joints during extrusion (`extrude`, `loft`).
- SDF meshing: voxels-per-unit derived from the turtle resolution (denser meshes for higher values, automatically boosted for thin features).

Plain `loft` and `extrude` do not derive their step count from `resolution` (the loft step count defaults to 16 and is only overridable via `loft-n`; `extrude` produces one ring per `f` segment).

**Override for specific calls:**

```clojure
(arc-h 10 90 :steps 32)          ; Override resolution for this arc
(circle 5 64)                    ; Circle with explicit 64 segments
(sphere 10 32 16)                ; Sphere with explicit segments/rings
```

### Turtle scope

The `turtle` macro creates an isolated turtle scope. The child turtle inherits the parent's full state (position, heading, up, settings) but operates on its own copy. Changes inside the scope do not affect the outer turtle. Lines and meshes created inside the scope are visible (shared scene accumulator).

```clojure
;; Basic scope: child inherits parent's pose and settings
(turtle
  (f 20)
  (th 45)
  (f 10))
;; Outer turtle is unchanged

;; :reset: fresh turtle at origin with default settings
(turtle :reset
  (f 30))

;; :preserve-up: keep up vector stable (no roll accumulation)
(turtle :preserve-up
  (dotimes [_ 85] (f 3) (th 8.6) (tv 0.5)))

;; Nesting: each level is isolated
(turtle
  (f 10)
  (turtle
    (f 20)
    (turtle :reset (f 5))))
```

**Options:**

| Option | Description |
|--------|-------------|
| `:reset` | Start from origin with default settings (ignores parent state) |
| `:preserve-up` | Enable preserve-up mode (see below) |
| `[x y z]` | Set position (positional vector argument) |
| `{:pos [x y z]}` | Override position |
| `{:heading [x y z]}` | Override heading |
| `{:up [x y z]}` | Override up vector |

**Use cases:**
- Branching constructions (L-systems, trees), replacing `push-state`/`pop-state`.
- Temporary exploration without affecting the main turtle.
- Isolation of settings (resolution, joint-mode, material) within a scope.

**Preserve-up mode.** `th` (yaw) and `tv` (pitch) are intrinsic rotations: each one is applied around the turtle's *current* up or right axis. When you alternate them many times (climbing a helix, walking a 3D spiral), the up vector composes in 3D and drifts off the world up direction. The visible symptom is implicit roll: cross-sections, letters, or attached children tilt sideways as the turtle progresses, even though no `tr` was ever called.

`:preserve-up` cancels that drift. At scope entry the current up is captured as the reference up; after every rotation, up is reprojected onto the plane perpendicular to the heading, as close to the reference as possible. The heading itself is unchanged, so paths, extrusions, and turtle position behave exactly as before. Only the roll of the local frame is corrected.

```clojure
;; Standard mode: up drifts after many th+tv combinations
(dotimes [_ 85] (f 3) (th 8.6) (tv 0.5))
;; up may drift to [-0.26, -0.57, 0.78] (unexpected roll)

;; Preserve-up mode: up stays stable
(turtle :preserve-up
  (dotimes [_ 85] (f 3) (th 8.6) (tv 0.5)))
;; up stays close to [0, 0, 1] (reference-up)
```

Use it whenever the local roll matters more than the strict intrinsic-rotation semantics: text on 3D curves, climbing handrails, helices where attached features must stay vertical. The online manual ships a "Text on spiral" example (`text-on-spiral`) that demonstrates the canonical case:

```clojure
(def spiral (path (tv 7) (dotimes [_ 500] (f 1) (th 3))))

(register spiral-text
  (turtle :preserve-up
    (text-on-path "spiral text" spiral :size 6 :depth 1.2)))
```

The pitch is set once with `tv` before the loop; the loop is purely horizontal turns. Without `:preserve-up`, letters would gradually roll out of vertical as the turtle climbed; with it, every letter stays upright.

`tr` (roll) still works normally inside preserve-up scopes: it sets a deliberate roll relative to the corrected up.

### Anchors & Navigation

Anchors are named poses (position + orientation) that the turtle can navigate to. They are created by resolving **marks** embedded in paths via `with-path`.

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

Marks are defined inside `path` recordings (see [Paths](#3-paths)). To use them, pin the path at the current turtle pose with `with-path`:

```clojure
(def skeleton (path (f 30) (mark :shoulder) (th 45) (f 20) (mark :elbow)))

;; with-path resolves marks relative to current turtle pose
(with-path skeleton
  (goto :shoulder)
  (bezier-to-anchor :elbow))

;; Nesting is supported: inner scope shadows, outer scope restores
(with-path outer-path
  (with-path inner-path
    (goto :inner-mark))      ; inner anchors available here
  (goto :outer-mark))         ; outer anchors restored here
```

---

## 3. Paths

### Path recording

Paths record turtle movements as abstract data for later replay in extrusions. Paths are not directly renderable: they are used to define extrusion trajectories, embed marks for navigation, and compose complex curves.

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

### Marks

Marks record named poses within a path. They have no effect on geometry: they simply tag the turtle's position and orientation at that point.

```clojure
(def arm (path (f 30) (mark :elbow) (th 45) (f 20) (mark :hand)))
```

### Follow (splicing)

`follow` splices another path's commands into the current recording:

```clojure
(def segment (path (f 10) (mark :joint)))
(def full (path (f 20) (follow segment) (th 90) (f 10)))
;; full contains: f 20, f 10, mark :joint, th 90, f 10
```

### Side-trip (scoped sub-path)

`side-trip` runs its body as a sub-path that does NOT advance the spine: on replay, the turtle's position/heading/up are saved, the body runs (marks, moves, etc.), then the pose is restored. Anchors created inside the body are kept; only the spine cursor is rewound.

```clojure
(def skel
  (path
    (mark :start)         ; (0 0 0)
    (f 50)                ; spine at (50 0 0)
    (side-trip
      (th 90) (f 27)      ; off to the side
      (tv -90) (f 37)     ; and down
      (mark :branch))     ; mark dropped at (50 27 -37)
    (mark :after)         ; back at (50 0 0) — spine never moved
    (f 10) (mark :end)))  ; (60 0 0)
```

This is the natural primitive for "drop a mark off the side and keep walking the main axis". A common pattern is to wrap each side-trip in a small helper:

```clojure
(defn arm [side depth mname]
  (path
    (side-trip
     (th (if (pos? side) 90 -90))
     (f (if (pos? side) side (- side)))
     (tv -90) (f depth) (tv 90)
     (mark mname))))

(def skel
  (path
    (mark :pin-axis)
    (f 50) (follow (arm  27 37 :left-1)) (follow (arm -27 37 :right-1))
    (f -80) (follow (arm  27 37 :left-2)) (follow (arm -27 37 :right-2))))
```

Without `side-trip` the helper would have to manually undo every `(th)`, `(f)`, `(tv)` it issued before marking — making each arm fragile and verbose. `side-trip` lets a sub-path "branch off" of the spine, drop its marks, and snap back automatically.

Nesting and `follow` both work as expected: `(side-trip (follow X) (mark :Y))` is fine; the inner `(follow X)` just splices into the side-trip's sub-path, and only that sub-path is scoped.

### Quick path

`quick-path` (alias `qp`) creates paths from compact notation:

```clojure
(quick-path 20 90 30 -45 10)  ; alternating: forward, turn, forward, turn, ...
(qp 20 90 30 -45 10)          ; same, shorter
```

### Poly path

Create paths from coordinate pairs (like `poly` for shapes):

```clojure
(poly-path x1 y1 x2 y2 ...)       ; Open path from coordinate pairs
(poly-path [x1 y1 x2 y2 ...])     ; Vector form

(poly-path-closed x1 y1 x2 y2 ...)  ; Closed path (returns to start)
```

### Path utilities

```clojure
;; Execute a path on the turtle (draws lines if pen is on)
(follow-path my-path)

;; Get mark positions within a path (2D)
(mark-pos path :mark-name)          ; Returns [x y]
(mark-x path :mark-name)            ; X coordinate only
(mark-y path :mark-name)            ; Y coordinate only

;; 2D bounding box
(bounds-2d path)                     ; {:min [x y] :max [x y] :center [cx cy] :size [w h]}

;; Extract portion of path by height
(subpath-y path from-h to-h)         ; Clip path vertically, output starts at Y=0

;; Shift path horizontally
(offset-x path dx)                   ; Move profile relative to revolve axis

;; Scale path or shape to fit target dimensions
(fit path :y 180)                    ; Scale Y extent to 180, keep X
(fit shape :x 200 :y 130)           ; Scale both axes independently
```

---

## 4. 2D Shapes

### Built-in shapes

Shapes are 2D profiles used for extrusion:

```clojure
(circle radius)                  ; Circle, uses resolution setting
(circle radius segments)         ; Custom resolution

(rect width height)              ; Rectangle centered at origin

(polygon n radius)                ; Regular n-sided polygon (e.g., 6 for hexagon)

(poly x1 y1 x2 y2 ...)          ; Arbitrary polygon from coordinate pairs
(poly [x1 y1 x2 y2 ...])        ; Same, from a vector
(poly coords-var)                ; Same, from a variable

(star n-points outer-r inner-r)  ; Star shape (n tips)

(stroke-shape my-path width)             ; Stroke a path into a 2D outline
(stroke-shape my-path width              ; With options
  :start-cap :round
  :end-cap :flat
  :join :miter
  :miter-limit 4)

(path-to-shape my-path)                  ; Convert 3D path to 2D shape (XY projection)
```

### Custom shapes from coordinates

Create arbitrary 2D shapes from cartesian coordinate pairs. The origin `[0,0]` is anchored to the turtle's position:

```clojure
;; Arrow shape (flat args)
(register arrow (extrude (poly -3 -2  5 0  -3 2) (f 8)))

;; From a vector
(def diamond-pts [0 5  5 0  0 -5  -5 0])
(register gem (extrude (poly diamond-pts) (f 10)))
```

### Custom shapes from turtle

Create arbitrary 2D shapes using turtle movements:

```clojure
;; Triangle
(register bar
  (extrude
    (shape (th 120) (f 15) (th 150) (f 8) (th -90) (f 20) (th -90) (f 8) (th 150) (f 15))
    (f 40)))

;; Right triangle (auto-closes to starting point)
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
(register prism (extrude right-tri (f 20)))
```

The `shape` macro uses a 2D turtle starting at origin facing +X. Only `f` (forward) and `th` (turn horizontal) are available. The shape is automatically closed.

### Anchoring flags

When a 2D shape is projected onto the turtle's plane (in `extrude`, `stamp`, `revolve`, `loft`, `slice-mesh`), the system computes an offset to apply to the points before transforming them to 3D. Three flags on the shape map control that offset:

| Flag | Default | Effect on projection |
|------|---------|----------------------|
| `:centered?` | `true` for `circle`, `rect`, `polygon`, `star`; `false` otherwise | Shape's 2D origin coincides with the turtle. The points are placed as-is around the turtle's position (centroid of `circle`/`rect` lands on the turtle). |
| `:preserve-position?` | `false` | Points are placed at their raw 2D coordinates relative to the turtle, with no re-anchoring. Same offset math as `:centered?` but expresses a different intent (the shape is already in plane-local coordinates). |
| (default, neither flag set) | applies to `(shape ...)`, `(poly ...)`, `(stroke-shape ...)` outputs | The first point of the contour is translated to the turtle's position. Useful when constructing shapes whose first vertex is the natural anchor. |

`:centered? true` and `:preserve-position? true` produce the same numerical offset (`[0 0]`); the distinction is documentary intent. Use `:centered?` for shapes that are geometrically symmetric around their 2D origin; use `:preserve-position?` for shapes whose 2D coordinates are already meaningful in some plane-local frame and must not be rebased.

In normal use you do not set these flags directly: the built-in constructors pick the right default. Two cases where `:preserve-position? true` matters explicitly:

- **Letters from `text-on-path`** carry per-letter offsets that encode their position along the word. Re-anchoring each letter to the turtle would collapse them to a single point.
- **Shapes returned by `slice-mesh`** are in plane-local coordinates (origin = turtle, X = right, Y = up) and the points already encode their absolute position in that frame. Without the flag, the slice would visually drift relative to the source mesh when fed to `stamp`.

A fourth flag, `:align-to-heading?`, swaps the plane axes so 2D x maps to the turtle's heading direction (used internally by `text-on-path` to make letters progress along the curve). It is not normally set by user code.

### Shape transformations

```clojure
(scale shape factor)               ; Uniform scale (polymorphic, see §9)
(scale shape sx sy)                ; Non-uniform scale
(scale-shape shape …)              ; Type-specific alias (kept for back-compat)

(rotate shape angle-deg)           ; Rotate around origin (Z axis implicit)
(rotate shape :z angle-deg)        ; Same, axis explicit
(rotate-shape shape angle-deg)     ; Type-specific alias

(translate shape dx dy)            ; Translate (polymorphic)
(translate-shape shape dx dy)      ; Type-specific alias

(reverse-shape shape)              ; Reverse winding order (flip normals)

(morph-shape shape-a shape-b t)    ; Interpolate between shapes (t: 0-1)
                                   ; Both must have same point count

(resample-shape shape n)           ; Resample to n points (for morph compatibility)
```

`translate`, `scale`, `rotate` are **polymorphic** (mesh / SDF / 2D shape — see [Top-level transforms](#top-level-transforms)). The `*-shape` aliases continue to work; pick whichever form reads better in context.

**`rotate` on a 2D shape only accepts `:z` (or no axis at all).** Calling `(rotate shape :x α)` or `(rotate shape :y α)` throws — a 2D shape has no out-of-plane geometry to rotate. To position a shape obliquely in 3D space, set the turtle's heading before consuming the shape (e.g. `(tv 30) (extrude shape (f 20))`). For a Y-foreshortening effect, write `(scale shape 1 (cos angle))` explicitly — that's what the math reduces to, and it makes the lossy projection visible at the call site.

### Shape functions (shape-fn)

Shape functions are shapes that vary along the extrusion path. Instead of passing a separate transform function to `loft`, shape-fns carry the transformation logic inside the shape itself, enabling composable, reusable profiles.

A shape-fn is a function `(fn [t] -> shape)` with metadata `{:type :shape-fn}`. At each point along a loft path (or revolution step), `t` goes from 0 to 1. Shape-fns work with `loft`, `bloft`, and `revolve`.

```clojure
;; Static shape: use extrude (fast, no per-ring evaluation)
(extrude (circle 20) (f 30))

;; Shape-fn: use loft (evaluates shape at each step)
(loft (tapered (circle 20) :to 0) (f 30))
```

**Built-in shape-fns:**

| Function | Description |
|----------|-------------|
| `(tapered shape :to ratio)` | Scale from 1 (or `:from`) to `:to` along path |
| `(twisted shape :angle deg)` | Rotate progressively (default 360) |
| `(rugged shape :amplitude a :frequency f)` | Radial sin displacement (constant along t) |
| `(fluted shape :flutes n :depth d)` | Longitudinal cos grooves |
| `(displaced shape (fn [p t] -> offset))` | Custom per-vertex radial displacement |
| `(morphed shape-a shape-b)` | Interpolate between two shapes (same point count) |
| `(noisy shape :amplitude a :scale s)` | Noise-based displacement (see options below) |
| `(woven shape :warp n :weft m)` | Interlocking over/under woven fabric pattern |
| `(heightmap shape hm :amplitude a)` | Displacement from a rasterized heightmap |
| `(profile shape path)` | Scale cross-section to match a path silhouette |
| `(shell shape :thickness n :style s)` | Hollow extrusion with wall pattern (`:solid` `:voronoi` `:lattice` `:checkerboard` `:weave`) |
| `(shell shape :thickness n :fn f)` | Hollow extrusion with custom thickness function |
| `(woven-shell shape :thickness n ...)` | Shell with radial offset for true over/under weave |

**Composition** via `->` threading:

```clojure
;; Fluted column that tapers
(-> (circle 15 48) (fluted :flutes 20 :depth 1.5) (tapered :to 0.85))

;; Twisted rectangle that shrinks
(-> (rect 30 10) (twisted :angle 180) (tapered :to 0.3))
```

**Profile shape-fn:**

```clojure
;; Define a silhouette path (X = radius, Y = height)
(def vase-sil (path (f 5) (th 80) (f 15) (arc-h 5 -160) (f 15)))

;; Loft a circle scaled to match the silhouette
(register vase (loft (-> (circle 20 64) (profile vase-sil)) (f 40)))
```

The path's X coordinates represent the radius at each point along the extrusion. Works best with bezier-smoothed paths for smooth results.

**Custom shape-fn:**

```clojure
(shape-fn (circle 20)
  (fn [shape t]
    (scale-shape shape (+ 0.6 (* 0.4 (sin (* t PI)))))))
```

**`noisy` options:** `:amplitude` (1.0), `:scale` (3.0), `:scale-x`, `:scale-y`, `:octaves` (1), `:seed` (0).

**`woven` options:** `:warp` (6), `:weft` (4), `:amplitude` (1.0), `:thread` (0.42, thread width as fraction of cell, 0..0.5).

**`heightmap` options:** `:amplitude` (1.0), `:tile-x` (1), `:tile-y` (1), `:offset-x` (0), `:offset-y` (0), `:center` (false; when true, shifts sample range to [-0.5, 0.5]).

**Noise and heightmap functions** (available globally):

| Function | Description |
|----------|-------------|
| `(noise x y)` | 2D deterministic continuous noise, returns ~[-1, 1] |
| `(fbm x y)` | Fractal Brownian Motion (layered noise, 4 octaves default) |
| `(fbm x y octaves)` | fbm with custom octave count |
| `(fbm x y octaves lacunarity gain)` | fbm with full control |
| `(mesh-to-heightmap mesh :resolution n)` | Rasterize mesh z-values into a 2D grid |
| `(weave-heightmap :threads 4 :spacing 5 :radius 2 :resolution 128)` | Analytical weave heightmap generator |
| `(sample-heightmap hm u v)` | Sample heightmap with bilinear interpolation (auto-tiles) |
| `(heightmap-to-mesh hm)` | Convert heightmap to a flat XY mesh with Z from values |
| `(mesh-bounds mesh)` | 3D bounding box `{:min [x y z] :max [x y z] :center [cx cy cz] :size [sx sy sz]}` |

**`weave-heightmap` options:** `:threads` (4), `:spacing` (5), `:radius` (2), `:lift` (same as radius), `:resolution` (128), `:profile` (`:round` or `:flat`), `:thickness` (radius * 0.5, for `:flat` profile).

**`mesh-to-heightmap` options:** `:resolution` (128), `:bounds` `[x0 y0 x1 y1]`, `:offset-x`, `:offset-y`, `:length-x`, `:length-y` (custom sampling window).

**`heightmap-to-mesh` options:** `:z-scale` (1.0; amplify Z), `:size` (fit into NxN square at origin).

**Helpers:**

| Function | Description |
|----------|-------------|
| `(shape-fn base transform-fn)` | Create shape-fn from base + `(fn [shape t] -> shape)` |
| `(shape-fn? x)` | Check if x is a shape-fn |
| `(angle [x y])` | Angle (radians) of 2D point from origin |
| `(displace-radial shape offset-fn)` | Displace points radially from centroid |

**Shell shape-fn.** Variable-thickness hollow extrusion with openings:

```clojure
;; Built-in styles
(shell (circle 20 64) :thickness 2 :style :solid)                          ; Solid walls (default)
(shell (circle 20 64) :thickness 2 :style :voronoi :cells 8 :rows 6)      ; Voronoi openings
(shell (circle 20 64) :thickness 2 :style :lattice :openings 8 :rows 12)  ; Grid openings
(shell (circle 20 64) :thickness 2 :style :checkerboard :cols 8 :rows 8)  ; Checkerboard
(shell (circle 20 64) :thickness 2 :style :weave :strands 6 :frequency 8) ; Woven pattern

;; Custom thickness function
(shell (circle 20 64) :thickness 3
  :fn (fn [a t] (max 0 (sin (+ (* a 8) (* t PI 6))))))
```

The thickness function `(fn [angle t] -> 0..1)` maps each point to a wall thickness:
- `1.0` = full wall thickness, `0.0` = no wall (opening).
- `angle` = angular position on profile (radians), `t` = path progress (0..1).
- Values below `:threshold` (default 0.05) snap to 0.

**`shell` options:** `:thickness` (2), `:style` (`:solid`), `:fn` (custom, overrides style), `:threshold` (0.05), `:cap-top`, `:cap-bottom`.

Wall is symmetric: outer ring displaced outward by `thickness/2`, inner ring displaced inward by `thickness/2`. Where thickness is 0, both rings coincide (opening).

**Style-specific options:**

| Style | Options |
|-------|---------|
| `:solid` | (none) |
| `:lattice` | `:openings` (8), `:rows` (12), `:shift` (0.5) |
| `:checkerboard` | `:cols` (8), `:rows` (8) |
| `:weave` | `:strands` (6), `:frequency` (8), `:width` (0.3) |
| `:voronoi` | `:cells` (6), `:rows` (6), `:seed` (42), `:wall-width` (0.3) |

**Shell caps.** Close the ends of a shell with a solid or patterned cap:

```clojure
;; Solid cap (simple thickness value)
(shell shape :thickness 2 :style :voronoi :cells 8 :rows 6
  :cap-top 3)

;; Patterned cap (automatic: uses actual shape at cap position, expanded to outer wall)
(shell shape :thickness 2 :style :voronoi :cells 8 :rows 6
  :cap-top {:thickness 3 :style :voronoi :cells 25 :wall 1.5})

;; Grid cap
(shell shape :thickness 2 :style :lattice :openings 8 :rows 12
  :cap-top {:thickness 3 :style :grid :spacing [5 5] :hole 1.5})

;; Both caps
(shell shape :thickness 2 :style :voronoi :cells 8 :rows 6
  :cap-top {:thickness 3 :style :voronoi :cells 25 :wall 1.5}
  :cap-bottom 2)
```

Cap styles automatically match the shape at the cap's position (accounting for shape-fn transforms like tapering) and expand to the outer wall radius. Available cap styles:

| Cap style | Options |
|-----------|---------|
| `:voronoi` | `:cells` (20), `:wall` (1.5), `:seed` (0), `:relax` (2), `:resolution` (16) |
| `:grid` | `:spacing` `[sx sy]` ([5 5]), `:hole` (1.5), `:inset` (0) |
| `:solid` | (none; same as passing a number) |

**Woven shell.** Thickness + radial offset for true 3D over/under. Unlike `shell` (thickness only), `woven-shell` shifts the wall center radially so threads can pass in front of / behind each other. At crossings, both threads are combined into a single thicker wall.

```clojure
;; Diagonal weave (default)
(woven-shell (circle 20 128) :thickness 3 :strands 8)
(woven-shell (circle 20 128) :thickness 3 :strands 8 :width 0.15 :lift 1.5)

;; Orthogonal weave (basket/wicker)
(woven-shell (circle 20 128) :thickness 3
  :mode :orthogonal :warp 8 :weft 20
  :warp-width 0.2 :weft-width 0.12)

;; Custom fn returning {:thickness 0..1, :offset number}
(woven-shell (circle 20 128) :thickness 3
  :fn (fn [a t] {:thickness 0.8 :offset (* 0.5 (sin (* a 4)))}))
```

**`woven-shell` options:**
- `:mode`: `:diagonal` (default) or `:orthogonal`.
- `:strands` (8): threads per direction (diagonal mode).
- `:width` (0.12): thread width as fraction of cell (diagonal mode).
- `:warp` (8), `:weft` (30): thread counts per direction (orthogonal mode).
- `:warp-width` (0.2), `:weft-width` (0.1): thread widths (orthogonal mode).
- `:lift` (thickness/2): radial offset amplitude at crossings.
- `:fn`: custom function `(fn [angle t] -> {:thickness v :offset o})`.
- `:cap-top`, `:cap-bottom`: same cap syntax as `shell`.

Shell and woven-shell compose with other shape-fns:

```clojure
(-> (circle 20 64) (shell :thickness 2 :style :voronoi :cells 8 :rows 6) (tapered :to 0.5))
(-> (circle 20 128) (woven-shell :thickness 3 :strands 6) (twisted :angle 90))
```

**Resolution considerations:**
- Circumferential detail: use `(circle r n)` or `(resample-shape shape n)`. Points per ring should be at least 2 x frequency.
- Longitudinal detail: use `loft-n` with higher step count.

### Shape preview (stamp)

Visualize a 2D shape at the current turtle position/orientation as a semi-transparent surface. Shows exactly where the initial face of an `extrude` or `revolve` would appear. Useful for debugging shape placement before committing to an operation.

```clojure
(stamp shape)                    ; Show shape surface at current turtle pose
(stamp shape :color 0xff0000)    ; Custom color (hex)
```

Stamps are rendered as semi-transparent surfaces (default orange, visible from both sides). Shapes with holes are correctly triangulated. Stamps do not modify turtle position or heading.

**Visibility control:**

```clojure
(show-stamps)                    ; Show stamp outlines
(hide-stamps)                    ; Hide stamp outlines
(stamps-visible?)                ; Check visibility
```

A "Stamps" toggle button is also available in the viewport toolbar.

### 2D Booleans

Combine or modify 2D shapes before extrusion using Clipper2 boolean operations. Shapes with holes are fully supported: results preserve holes, and all extrusion operations (extrude, loft, revolve) correctly handle shapes with holes.

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

**Fillet & chamfer (corner rounding/cutting):**

```clojure
(fillet-shape shape radius)                        ; Round all corners with circular arcs
(fillet-shape shape radius :segments 16)           ; Smoother arcs (default 8)
(fillet-shape shape radius :indices [0 2])         ; Only specific vertices
(chamfer-shape shape distance)                     ; Cut all corners flat
(chamfer-shape shape distance :indices [0 1])      ; Only specific vertices
```

**Examples:**

```clojure
;; Rounded rectangle
(register pill (extrude (fillet-shape (rect 40 20) 5) (f 10)))

;; Chamfered hexagon
(register hex (extrude (chamfer-shape (polygon 6 20) 3) (f 15)))

;; Selective: only round two corners of a rect
(register tab (extrude (fillet-shape (rect 30 15) 4 :indices [2 3]) (f 8)))
```

**Cap fillet (round edges at extrusion caps):**

```clojure
(loft (capped shape radius) path)                      ; Fillet both caps (quarter-circle easing)
(loft (capped shape radius :mode :chamfer) path)       ; Chamfer both caps (linear)
(loft (capped shape radius :start false) path)         ; Fillet end cap only
(loft (capped shape radius :end false) path)           ; Fillet start cap only
(loft (capped shape radius :fraction 0.15) path)       ; Override auto-fraction
```

The transition `fraction` is auto-calculated as `radius / path-length` (geometrically correct fillet). Override with `:fraction` if needed. Radius is clamped to the shape's inradius to prevent degenerate geometry.

**Examples:**

```clojure
;; Fully rounded box: 2D corner rounding + 3D cap rounding
(register rounded-box (loft (-> (rect 40 20) (fillet-shape 5) (capped 3)) (f 50)))

;; Tapered with rounded caps
(register drop (loft (-> (circle 20) (tapered :to 0.3) (capped 2)) (f 40)))
```

**Notes:**
- `fillet-shape` / `chamfer-shape` operate on 2D corners (edges along extrusion direction).
- `capped` operates on 3D cap edges (where profile meets top/bottom face).
- Both compose freely with all shape-fns and with each other.
- Cap transition uses centroid scaling: shape proportions (including fillet radii) are preserved.
- Works on any shape including shapes with holes.

**Convex hull (`shape-hull`).** Compute the 2D convex hull of N input shapes. Useful for fairing complex outlines from a few seed circles or for capsules and lozenge profiles.

```clojure
(shape-hull a b)             ; Hull of two shapes
(shape-hull a b c)           ; Variadic, any number of shapes
```

`shape-hull` is variadic and takes shapes as positional arguments only (**there is no `:segments` option**). The output is the true convex hull of the union of all input points: it picks only the points that lie on the convex boundary and connects them with straight edges. Output point count = number of hull vertices.

To control the resolution of the result:

- **More hull vertices**: use input shapes with more segments (e.g., `(circle r 256)` instead of `(circle r 32)`). Only the points already on the boundary survive, so denser inputs give a smoother hull along their convex arcs. The straight tangent segments between two distinct shapes are always 2-vertex lines, regardless of input density.
- **Exact point count**: wrap the result in `(resample-shape hull n)` to redistribute the hull edge uniformly to `n` points. This is the recommended way to get a predictable point count for downstream loft/extrude operations.

```clojure
;; Capsule from two circles
(register pill (extrude (shape-hull (circle 10) (translate (circle 10) 30 0)) (f 5)))

;; Pistachio outline from three circles, normalized to 256 hull points
(def s1 (translate (circle 50 128) 130 20))
(def s2 (circle 25 128))
(def s3 (translate (circle 16 128) 130 -20))
(def base (resample-shape (shape-hull s3 s1 s2) 256))
(register bowl (loft base (f 50)))
```

**Notes:**
- Holes on input shapes are ignored. Only the outer contour participates in the hull.
- Returned shape is centered (`:centered? true`) regardless of input position.
- For 3D mesh hulls, see [Convex hull](#convex-hull) under [Mesh Operations](#7-mesh-operations).

**Pattern tiling.** Tile a pattern shape across a target shape and subtract, producing a shape with holes. The pattern is repeated on a grid covering the target's bounding box.

```clojure
(pattern-tile target pattern :spacing [sx sy])   ; Tile pattern on grid, subtract from target
(pattern-tile target pattern :spacing [8 8])      ; 8x8 grid of holes
(pattern-tile target pattern :spacing [6 6] :inset 0.5) ; Shrink each tile copy by 0.5
```

| Option | Default | Description |
|--------|---------|-------------|
| `:spacing` | pattern bbox size | Tile period `[sx sy]` |
| `:inset` | 0 | Shrink each pattern copy before subtraction |

The pattern can be a single shape or a vector of shapes. Works with any shape: circles, SVG imports, custom polygons.

```clojure
;; Grid of circular holes
(pattern-tile (circle 30 64) (circle 2 16) :spacing [6 6])

;; Tiled SVG motif
(def motif (svg-shape (svg "<svg>...</svg>")))
(pattern-tile (rect 40 40) motif :spacing [12 12])

;; Use as shell cap style (see Shell caps)
;; :cap-top {:thickness 3 :style :grid :spacing [6 6] :hole 2}
```

**Notes:**
- Results may contain holes (e.g., `shape-difference` of overlapping shapes).
- `shape-xor` returns a **vector of shapes** (since XOR can produce disconnected regions).
- Vectors of shapes are accepted by `shape-offset`, `extrude`, `loft`, `bloft`, `revolve`, and `stamp`.
- Holes are automatically detected from winding direction.
- All shape transforms (`scale`, `rotate-shape`, `translate`, `morph`) propagate holes.
- Internally uses integer coordinates (x1000 scale) for precision.

### Voronoi shell

Generate a perforated 2D shape with Voronoi cell pattern. Cell borders become material, cell interiors become holes. The result is a standard shape with `:holes`, compatible with `extrude`, `loft`, `revolve`, and all shape-fns (`tapered`, `twisted`, `noisy`, etc.).

```clojure
(voronoi-shell shape)                                    ; Default: 20 cells, 1.5 wall
(voronoi-shell shape :cells 40 :wall 1.5)                ; Custom cell count and wall thickness
(voronoi-shell shape :cells 30 :wall 2 :seed 42)         ; Reproducible pattern
(voronoi-shell shape :cells 25 :wall 1.5 :relax 3)       ; More uniform cells (Lloyd relaxation)
(voronoi-shell shape :cells 20 :wall 1 :resolution 24)   ; Higher-res holes for smoother loft
```

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `:cells` | 20 | Number of Voronoi cells |
| `:wall` | 1.5 | Wall thickness between cells |
| `:seed` | 0 | Random seed for reproducibility |
| `:relax` | 2 | Lloyd relaxation iterations (higher = more uniform cells) |
| `:resolution` | 16 | Points per hole (affects loft smoothness) |

**Examples:**

```clojure
;; Perforated tube
(register voro-tube (extrude (voronoi-shell (circle 20) :cells 40 :wall 1.5) (f 50)))

;; Tapered voronoi cone
(register voro-cone
  (loft (tapered (voronoi-shell (circle 20) :cells 30 :wall 2) :to 0.3) (f 80)))

;; Twisted voronoi vase
(register voro-vase
  (loft (-> (voronoi-shell (circle 15 64) :cells 25 :wall 1.5 :seed 42)
            (twisted :angle 45)
            (tapered :from 0.8 :to 1.2))
    (f 60)))

;; Decorative face panel
(register voro-face (extrude (voronoi-shell (rect 30 30) :cells 25 :wall 1.5) (f 2)))

;; Voronoi revolve vase
(register voro-rev
  (revolve (voronoi-shell (shape (f 15) (th 90) (f 30) (th 90) (f 15)) :cells 20 :wall 1.5)))
```

**Notes:**
- `voronoi-shell` is a 2D operation (not a shape-fn): it takes a shape and returns a shape with holes.
- The result composes with shape-fns via `->` threading: `(-> (voronoi-shell ...) (tapered ...) (twisted ...))`.
- Same `:seed` always produces the same pattern; different seeds produce different patterns.
- `:relax 0` gives raw Voronoi cells; higher values make cells more uniform via Lloyd relaxation.
- Uses d3-delaunay for Voronoi computation and Clipper2 for cell clipping/inset.

---

## 5. 3D Primitives

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

Primitives carry semantic face IDs (`:top`, `:bottom`, etc.). See [Semantic face names (primitives)](#semantic-face-names-primitives) under [Faces](#8-faces) for the full table and how to address faces by name.

---

## 6. Generative Operations

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

**Return value.** Always a single mesh.

- Single shape input → single mesh.
- Vector-of-shapes input (e.g. `text-shape` output, where composite glyphs and multi-letter strings produce multiple shapes) → single combined mesh. The shapes are extruded independently along the same path, then merged into one mesh so downstream boolean ops (`mesh-difference`, `mesh-union`, …) just work without manual `concat-meshes`.

The same convention applies to `extrude-closed`, `loft`, `bloft`, and `revolve`.

**Joint modes.** Control corner geometry during extrusion:

```clojure
(joint-mode :flat)      ; Default (sharp corners)
(joint-mode :round)     ; Smooth rounded corners
(joint-mode :tapered)   ; Beveled/tapered corners
```

### Extrude-closed

For closed loops (torus-like shapes):

```clojure
;; Path should return to starting point
(def square-path (path (dotimes [_ 4] (f 20) (th 90))))

(register torus (extrude-closed (circle 5) square-path))
```

Creates a manifold mesh with no caps: last ring connects to first.

### Loft

Extrude with shape transformation based on progress. Supports two modes.

**Shape-fn mode** (preferred): pass a shape-fn as first argument, remaining args are movements:

```clojure
;; Cone (tapers to zero)
(register cone (loft (tapered (circle 20) :to 0) (f 30)))

;; Twist while extruding
(register twisted (loft (twisted (rect 20 10) :angle 90) (f 30)))

;; Composed: fluted column that tapers
(register column
  (loft (-> (circle 15 48) (fluted :flutes 20 :depth 1.5) (tapered :to 0.85))
    (f 80)))

;; With custom step count
(register smooth-cone (loft-n 32 (tapered (circle 20) :to 0) (f 30)))
```

**Legacy mode**: pass a plain shape + transform function + movements:

```clojure
;; Transform function receives (shape t) where t: 0->1
(register cone
  (loft (circle 20)
    #(scale-shape %1 (- 1 %2))     ; Scale from 1 to 0
    (f 30)))

;; Twist while extruding
(register twisted
  (loft (rect 20 10)
    #(rotate-shape %1 (* %2 90))   ; 90 deg twist
    (f 30)))
```

### Loft-between (two-shape loft)

Taper between two different shapes:

```clojure
;; Two-shape loft: end shape must have same point count as start
(register taper
  (loft (circle 20)
    (circle 10)                     ; End shape
    (f 40)))
```

Default: 16 steps. Returns mesh without side effects.

### Bloft (bezier-safe loft)

For paths with tight curves (like `bezier-as`), regular `loft` can produce self-intersecting geometry. `bloft` handles this by detecting ring intersections and bridging them with convex hulls, then unioning all pieces into a manifold mesh.

```clojure
;; Basic usage (same signature as loft)
(register tube
  (bloft (circle 4)
    identity
    (path (bezier-as my-curved-path))))

;; With taper
(register tapered-tube
  (bloft (circle 8)
    #(scale-shape %1 (- 1 (* 0.5 %2)))  ; Taper to half size
    (path (bezier-as (branch-path 30)))))

;; More steps for smoother result
(register smooth-tube
  (bloft-n 64 (circle 4)
    identity
    my-bezier-path))
```

**When to use `bloft` vs `loft`:**
- Use `loft` for straight paths or gentle curves (faster).
- Use `bloft` for tight bezier curves that might self-intersect (slower but correct).

**Performance note:** `bloft` can take several seconds for complex paths at high resolution. The density of bezier sampling is controlled by `(resolution :n ...)`:
- Low values (e.g., `:n 10`): fast draft preview (may show visual artifacts).
- High values (e.g., `:n 60`): smooth final render (slower).

### Revolve

```clojure
(revolve shape)                  ; Full 360 deg revolution around turtle heading
(revolve shape angle)            ; Partial revolution (degrees)
```

The profile shape is interpreted as:
- 2D X = radial distance from axis (perpendicular to heading).
- 2D Y = position along axis (in heading direction).

Use `translate-shape` to offset the profile from the axis for hollow shapes (e.g., torus).

Shapes with vertices at x < 0 are auto-clipped at the revolution axis to prevent self-intersecting geometry.

**Pivot option:** For corner/bend modeling, `:pivot` shifts the shape so one edge sits on the revolution axis, then compensates the mesh position:

```clojure
(revolve shape 30 :pivot :left)   ; Left edge becomes pivot
(revolve shape 30 :pivot :right)  ; Right edge becomes pivot
(revolve shape 30 :pivot :up)     ; Top edge becomes pivot
(revolve shape 30 :pivot :down)   ; Bottom edge becomes pivot
```

The pivot direction is relative to the shape's 2D coordinate frame (X = right, Y = up in the turtle frame). Use `:pivot` for bend/corner geometry: it keeps the shape's holes intact (no clipping).

**Shape-fn support:** When a shape-fn is passed instead of a static shape, the profile is evaluated at each revolution step with `t` going from 0 (first ring) to 1 (last ring):

```clojure
(revolve (tapered (circle 20) :to 0.5))           ; Profile shrinks during revolution
(revolve (twisted (rect 20 10) :angle 90))         ; Profile rotates as it revolves
(revolve (noisy (circle 15 64) :amplitude 2))      ; Organic surface
(revolve (morphed (square 20) (circle 15 4)) 180)  ; Morph during half-revolution
```

### Chaining (extrude+, revolve+, transform->)

Variants of `extrude` and `revolve` that return `{:mesh :end-face}` for chaining multi-segment geometry. The `:end-face` contains the shape and pose of the final face, which can be used as input for the next operation.

**extrude+ / revolve+**

```clojure
;; extrude+ returns {:mesh <mesh> :end-face {:shape <shape> :pose {...}}}
(def seg1 (extrude+ shape (f 20)))
(:mesh seg1)                       ; The mesh
(:end-face seg1)                   ; {:shape <shape> :pose {:pos :heading :up}}

;; revolve+ with :pivot for corner bends
(def corner (turtle (:pose (:end-face seg1))
              (revolve+ (:shape (:end-face seg1)) 30 :pivot :left)))

;; Chain: next segment from previous end-face
(def seg2 (turtle (:pose (:end-face corner))
            (extrude+ (:shape (:end-face corner)) (f 30))))

;; Combine
(register tutto (mesh-union (:mesh seg1) (:mesh corner) (:mesh seg2)))
```

**transform->**

Macro that automates the chaining pattern. Takes an initial shape (or an end-face map from a previous `extrude+`/`revolve+`) and a sequence of steps. Each step receives the shape and pose from the previous step's end-face. All meshes are combined via `mesh-union`.

```clojure
(register frame
  (transform-> (shape-difference (rect 40 40) (rect 30 30))
    (extrude+ (f 20))              ; Straight segment
    (revolve+ 30 :pivot :left)     ; Corner bend (30 degrees)
    (extrude+ (f 30))              ; Another straight segment
    (revolve+ -30 :pivot :right)   ; Bend back
    (extrude+ (f 20))))            ; Final segment
```

The first argument can also be an end-face from a previous operation:

```clojure
;; Chain from a previous extrude+
(def base (extrude+ frame-shape (f 10)))
(register result
  (mesh-union (:mesh base)
    (transform-> (:end-face base)
      (revolve+ 30 :pivot :left)
      (extrude+ (f 20)))))
```

Notes:
- Inside `transform->`, operations do NOT take a shape argument: it is passed automatically.
- `:pivot` on `revolve+` determines which edge of the shape sits on the revolution axis.
- The standard `extrude`/`revolve` remain unchanged (return just the mesh).

---

## 7. Mesh Operations

### Boolean

Requires Manifold WASM integration:

```clojure
(mesh-union a b)
(mesh-union a b c d)             ; Variadic
(mesh-union [a b c d])           ; From a vector

(mesh-difference base tool)
(mesh-difference base t1 t2 t3)  ; Subtract multiple
(mesh-difference [base t1 t2])   ; From a vector (first is base)

(mesh-intersection a b)
(mesh-intersection a b c d)      ; Variadic
(mesh-intersection [a b c d])    ; From a vector
```

The vector form is convenient for results of `(for ...)` or `map` without splatting. It composes well with `concat-meshes` (see below) when you want to skip the per-pair CSG cost of an internal union.

Resolve self-intersections (useful for loft/extrude that produce overlapping geometry):

```clojure
(solidify mesh)                  ; Pass through Manifold to clean self-intersections
```

Check mesh status:

```clojure
(manifold? mesh)                 ; true if manifold (watertight)
(mesh-status mesh)               ; Detailed status info
```

Concatenate meshes without boolean operations (no Manifold required):

```clojure
(concat-meshes m1 m2 m3)        ; Variadic
(concat-meshes [m1 m2 m3])      ; From a vector
```

Simply merges vertices and faces. Not manifold-valid on its own, but useful for heightmap sampling, visualization, and as a fast way to combine the result of a `(for ...)` of `attach` calls into a single argument for a boolean operation. Manifold accepts the concatenated geometry as a tool, so you skip the cost of pairwise unions when the pieces are going to be subtracted (or unioned) wholesale anyway.

```clojure
;; Drill a ring of N holes through a disk
(register plate
  (mesh-difference
    (cyl 30 5)
    (concat-meshes
      (for [i (range 12)]
        (attach (cyl 2 8) (th (* i 30)) (f 20))))))
```

The `for` returns a vector of 12 cylinder meshes. `concat-meshes` stitches them into one mesh in linear time; `mesh-difference` then subtracts that single tool from the base disk. Compared to `(mesh-union (for ...))`, you skip 11 sequential CSG unions, typically an order of magnitude faster for ring/grid hole patterns.

Same trick for additive patterns: pass `(concat-meshes (for ...))` as one operand of `mesh-union` to merge a flock of pieces into a base in a single CSG call rather than N.

### 3D Chamfer & Fillet

Post-processing operations that detect sharp edges by dihedral angle and modify them via CSG. See [FilletChamfer3D.md](FilletChamfer3D.md) for full documentation.

```clojure
(-> mesh (chamfer :top 2))                          ; Flat bevel on top edges
(-> mesh (chamfer :all 1.5 :angle 60))              ; All edges > 60deg

(-> mesh (fillet :top 3 :segments 8))               ; Rounded top edges
(-> mesh (fillet :all 2 :segments 8))               ; All sharp edges
(-> mesh (fillet :top 3 :blend-vertices true))      ; With spherical corner blend
```

Direction selectors: `:top` `:bottom` `:up` `:down` `:left` `:right` `:all`.

### Cross-section (slice)

Slice a mesh at the plane defined by the turtle's current position and heading. Returns a vector of 2D shapes (cross-section contours) in the plane's local coordinates.

```clojure
(slice-mesh mesh)                   ; Slice mesh reference at turtle plane
(slice-mesh :bowl)                  ; Slice registered mesh by name
```

The heading vector acts as the plane normal. The resulting shapes use the turtle's right/up as local X/Y axes.

**Typical usage with stamp:**

```clojure
(register cup (revolve (shape (f 20) (th -90) (f 30) (th -90) (f 15))))
(tv 90) (f 15)                      ; Position turtle at slice plane
(stamp (slice-mesh :cup))           ; Visualize the cross-section
```

Shapes returned have `:preserve-position? true` so they render at absolute plane-local coordinates when fed to `stamp`. See [Anchoring flags](#anchoring-flags) for how the flag changes shape projection.

Both `slice-mesh` and `project-mesh` accept a mesh map, a registered mesh keyword, or an SDF node (auto-materialized via the current `*sdf-resolution*`).

### Silhouette (project)

Project a mesh onto the plane orthogonal to the turtle's heading, returning the silhouette outline as a vector of 2D shapes. Whereas `slice-mesh` gives the cross-section *at* a plane, `project-mesh` gives the shadow *of* the mesh as seen looking along the heading.

```clojure
(project-mesh mesh)                  ; Silhouette of mesh
(project-mesh :neck)                 ; Silhouette of registered mesh
(project-mesh (sdf-blend a b 5))     ; Silhouette of an SDF (auto-materialized)
```

Same conventions as `slice-mesh`: heading is the projection direction, output shapes use turtle right/up as local X/Y, holes are preserved, and `:preserve-position? true` is set so `stamp` renders at absolute plane-local coordinates. Useful for pulling a 2D footprint from a 3D shape — e.g. to extrude a slightly larger silhouette as a negative for a clearance pocket.

```clojure
;; Top-down silhouette of a tilted neck, used as a cutter
(def cut
  (turtle (tv 90) (f 20)
    (let [s (project-mesh (chitarra-sdf))
          bigger (scale-shape s 1.05)]
      (extrude bigger (f 10)))))
```

### Convex hull

Compute the convex hull of one or more meshes:

```clojure
(register s1 (sphere 10))
(f 30)
(register s2 (sphere 10))
(register capsule (mesh-hull s1 s2))    ; Creates capsule shape

;; Can also pass a vector
(mesh-hull [s1 s2 s3])
```

### Mesh smoothing & refinement

Round off non-sharp edges of a 3D mesh using Manifold's tangent-based subdivision. Useful when you want to fillet every crease softer than a chosen dihedral angle while keeping intentionally sharp design edges (typically applied after a CSG pipeline, since boolean operations produce mostly right-angle corners that look synthetic).

```clojure
(mesh-smooth m)                                  ; defaults: sharp-angle 100, refine 3
(mesh-smooth m :sharp-angle 120 :refine 4)       ; smooth more, denser
(mesh-smooth m :sharp-angle 60)                  ; preserve all corners > 60deg
(mesh-smooth m :sharp-angle 180)                 ; round absolutely everything

;; Lower-level: refine without smoothing (just denser triangulation)
(mesh-refine m 2)                                ; each triangle -> 4 sub-triangles
```

| Option | Default | Description |
|--------|---------|-------------|
| `:sharp-angle` | 100 | Edges with dihedral angle GREATER than this stay sharp; the rest get smoothed. Manifold's stock default is 60, but for procedural meshes 90..120 typically gives better results because right-angle wall corners become smooth instead of preserved. Set to 180 to smooth everything. |
| `:smoothness` | 0 | 0..1 fillet at the edges that survive as sharp. 0 leaves them perfectly sharp; 1 turns them fully smooth. |
| `:refine` | 3 | Subdivision count after smoothing. Each triangle becomes n^2 sub-triangles. Higher = visually smoother but quadratically more triangles. |

**How it works:** `smoothOut` stores Bezier-tangent vectors on each halfedge based on the dihedral angle of its adjacent faces (without changing geometry); `refine` then subdivides each triangle into n^2 sub-triangles, placing the new vertices on the tangent curves rather than along straight lines. The result is a denser mesh whose surface is C1-continuous wherever the dihedral was <= `:sharp-angle`.

```clojure
;; CSG box with rounded edges (the canonical use case)
(register rounded-widget
  (-> (mesh-difference (box 40 40 20) (cyl 12 30))
      (mesh-smooth :sharp-angle 100 :refine 3)))

;; Loft solid with smoothed creases
(register smooth-bead
  (-> (loft (rect 20 10) (f 40) (th 90) (f 30))
      (mesh-smooth :sharp-angle 80 :refine 3)))
```

**Important: `mesh-smooth` requires a manifold (watertight, closed) input.**

Manifold's `smoothOut` is the underlying operation, and it rejects meshes that have open edges (edges belonging to only one triangle).

- Works on: primitives (`box`, `sphere`, `cyl`, `cone`), solid extrudes/lofts/revolves, results of `mesh-union`/`mesh-difference`/`mesh-intersection`/`mesh-hull`, SDF-materialized meshes.
- Does NOT work on: meshes with intentional apertures. `shell` with `:style :voronoi` / `:lattice` / `:checkerboard` (or any custom thickness fn that drops to 0) produces walls with literal holes. Manifold rejects those with `status 2 (NotManifold)`.

**This is a constraint of `mesh-smooth` (and of further boolean operations on the smoothed result), not a verdict on the mesh's usability.** A perforated shell mesh:

- Renders correctly in the viewport.
- Exports to STL or 3MF cleanly. The file lists every triangle and most slicers (Cura, PrusaSlicer, Bambu Studio, OrcaSlicer) accept open-edge geometry: they fill, repair, or ignore tiny gaps and slice the result without intervention. The user usually does not notice a difference vs a watertight mesh on print quality.
- Cannot be the input of another `mesh-smooth`, or of `mesh-union`/`mesh-difference`/`mesh-intersection` (Manifold rejects it).

If you need to smooth or further boolean a perforated shell, the options are:

1. **Rebuild as a CSG of solid pieces.** Construct each wall fragment between apertures as a closed solid and union them. Slow to write but yields a manifold mesh that smooths cleanly.
2. **Go through SDF.** `sdf-shell` produces a manifold by construction; cut the apertures with `sdf-difference` of solids. Materialization gives you a clean manifold mesh. Desktop only (see [SDF Modeling](#11-sdf-modeling)).
3. **Smooth before perforating.** If the smoothing target is the outer envelope, smooth a solid version first, then carve the apertures via boolean.
4. **Run `mesh-diagnose`** on the result to see exactly what Manifold is complaining about (open-edge count, non-manifold edges, degenerate faces). It is a reliable triage tool when you are not sure why the input fails.

There is no current plan to add an automatic mesh-repair pass that closes open edges in place: the safer fix is one of the four routes above. Slicer repair handles the case where you only need a printable STL.

**Notes:**
- `mesh-smooth` is a heavy operation: vertex/face count grows by `refine^2`. Start with the defaults and only crank `:refine` if you still see facets.
- The smoothing happens in 3D world coordinates, so it works equally on extrudes, lofts, revolves, and CSG results.
- For sharper control over which edges to round (by direction or position), use `fillet` instead. It operates only on edges you select rather than every non-sharp edge in the mesh, and works on non-manifold input too.
- Calling `mesh-refine` without a preceding `mesh-smooth` produces planar subdivision (the shape is unchanged, just denser). Useful only if you intend to feed the result into another operation that needs the extra density.

### Mesh diagnostics

`mesh-diagnose` computes topological invariants of a mesh without mutating it. Use it to triage why Manifold rejects a mesh, to verify watertightness before export, or to compare two construction strategies.

```clojure
(mesh-diagnose mesh)
;; => {:n-verts 1230
;;     :n-faces 2456
;;     :n-edges 3690
;;     :edge-incidence-distribution {2 3615, 1 75}
;;     :open-edges 75
;;     :non-manifold-edges 0
;;     :degenerate-faces 0
;;     :euler-characteristic 64
;;     :is-watertight? false}
```

**Keys:**

| Key | Meaning |
|-----|---------|
| `:n-verts`, `:n-faces`, `:n-edges` | Raw counts |
| `:edge-incidence-distribution` | `{n-incident-faces -> edge-count}`. Healthy manifold: `{2 N}` only |
| `:open-edges` | Edges shared by exactly one face (boundary holes) |
| `:non-manifold-edges` | Edges shared by 3 or more faces (T-junctions, duplicate walls) |
| `:degenerate-faces` | Triangles with area below `1e-10` |
| `:euler-characteristic` | `V - E + F`. Closed manifold without holes: 2 (sphere), 0 (torus). Each handle subtracts 2 |
| `:is-watertight?` | `true` iff `:open-edges` and `:non-manifold-edges` are both zero |

Pure ClojureScript: no Manifold WASM or Rust server, runs anywhere. Cheap enough to call inline during development.

**Companion mesh utilities:**

| Function | Description |
|----------|-------------|
| `(merge-vertices mesh)` | Collapse near-duplicate vertices (epsilon `1e-6`). Fixes non-manifold issues from CSG that produced coincident vertices |
| `(merge-vertices mesh epsilon)` | Custom merge tolerance |
| `(mesh-simplify mesh ratio)` | Edge-collapse decimation; `ratio` is target fraction of original triangles (0..1) |
| `(mesh-laplacian mesh)` | Selective Taubin smoothing: only moves vertices at sharp creases (dihedral angle below `:feature-angle`), preserving large flat regions. Topology preserving, useful to soften staircase aliasing on perforated shells |
| `(mesh-laplacian mesh :iterations n :lambda l :mu m :feature-angle deg)` | Custom schedule. Defaults: `:iterations 10`, `:lambda 0.5`, `:mu -0.53`, `:feature-angle 150` |

`merge-vertices` is the most common quick fix: a CSG result that fails `mesh-smooth` with `:non-manifold-edges > 0` often becomes manifold after a merge pass. `mesh-laplacian` is useful when you have an aesthetically rough mesh you cannot make manifold (typically a shell with apertures): smoothing reduces the visual aliasing without changing topology, and the result is still printable.

---

## 8. Faces

### Face selection

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

For meshes without pre-defined face-groups (e.g. CSG results), faces can be selected by geometric queries. Face groups are auto-detected by coplanar adjacency.

```clojure
;; Find faces by direction (relative to mesh creation-pose)
(find-faces mesh :top)           ; Faces aligned with heading direction
(find-faces mesh :bottom)        ; Opposite heading
(find-faces mesh :up)            ; Aligned with up
(find-faces mesh :all)           ; All face groups
(find-faces mesh :top :threshold 0.9)  ; Stricter alignment (default 0.7)
(find-faces mesh :top :where #(> (:area %) 100))  ; With predicate

;; Find face by position
(face-at mesh [0 0 0])           ; Face whose plane passes closest to point
(face-nearest mesh [10 0 0])     ; Face whose centroid is nearest to point

;; Find largest face
(largest-face mesh)              ; Largest face overall
(largest-face mesh :top)         ; Largest face in a direction

;; Auto face grouping
(auto-face-groups mesh)          ; Group triangles by coplanar adjacency
(ensure-face-groups mesh)        ; Add :face-groups if missing
```

All selection functions return face info maps with `:id` that can be passed to `attach-face`, `clone-face`, etc.

### face-shape

Extract a face boundary as a 2D shape for re-extrusion. Pure function that does not modify turtle state.

```clojure
(def top (face-shape mesh (:id (largest-face mesh :top))))
;; => {:shape <ridley-shape>
;;     :pose {:pos [x y z] :heading [hx hy hz] :up [ux uy uz]}}

;; Use with turtle scope for positioning
(turtle (:pose top)
  (extrude (:shape top) (f 20)))
```

The pose uses `:pos` (not `:position`) for compatibility with the `turtle` macro. The up vector is derived from the mesh's creation-pose, projected perpendicular to the face normal.

### Face highlighting

```clojure
(highlight-face mesh :top)                    ; Permanent orange highlight
(highlight-face mesh :top 0xff0000)           ; Custom color (hex)

(flash-face mesh :top)                        ; 2-second temporary highlight
(flash-face mesh :top 3000)                   ; Custom duration (ms)
(flash-face mesh :top 2000 0x00ff00)          ; Duration + color

(clear-highlights)                            ; Remove all highlights
```

### Measurement

Query distances, areas, and bounding boxes:

```clojure
;; Distance between mesh centroids
(distance :box1 :box2)

;; Distance between face centers
(distance :box1 :top :box2 :bottom)

;; Distance between arbitrary points
(distance [0 0 0] [100 0 0])

;; Mixed: face center to point
(distance :box1 :top [0 0 50])

;; Bounding box
(bounds :box1)
;; => {:min [x y z] :max [x y z] :size [x y z] :center [x y z]}

;; Face area
(area :box1 :top)
```

**Visual rulers.** `ruler` has the same argument forms as `distance` but adds a visual overlay:

```clojure
(ruler :box1 :box2)                ; ruler between centroids
(ruler :box1 :top [0 0 50])       ; ruler from face to point
(ruler [0 0 0] [100 0 0])         ; ruler between points

(clear-rulers)                     ; remove all rulers
```

Rulers show a line with endpoint markers and a floating distance label.

**Interactive measurement (Shift+Click):**
- **Shift+Click** on a mesh surface: place a measurement marker.
- **Shift+Click** again on another point: create a ruler between the two points.
- **Esc**: clear pending marker and all rulers.

**Lifecycle:**
- Rulers persist across REPL commands (so you can inspect while experimenting).
- Rulers are cleared automatically on code re-evaluation (Cmd+Enter).
- Call `(clear-rulers)` to remove them manually.

**Live rulers in tweak mode.** Rulers update live when using `tweak` on a registered mesh:

```clojure
(register A (box 30))
(register B (attach (box 30) (f 140) (u 50)))

;; In REPL:
(ruler :A :B)
(tweak :all :B)    ; rulers follow B as you drag sliders
```

When tweaking `:B`, rulers that reference `:B` re-resolve on each slider change. On confirm, rulers use the final registered mesh; on cancel, they revert.

### Semantic face names (primitives)

| Primitive | Face IDs |
|-----------|----------|
| Box | `:top`, `:bottom`, `:front`, `:back`, `:left`, `:right` |
| Cylinder/Cone | `:top`, `:bottom`, `:side` |
| Sphere | `:surface` |

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

Operations available inside `attach-face`/`clone-face`: `f`, `inset`, `scale`.

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

Operations available inside `attach-face`/`clone-face`:
- `(f dist)`: move along face normal.
- `(inset dist)`: shrink face inward.
- `(scale factor)`: scale face uniformly.

---

## 9. Positioning & Assembly

### Top-level transforms

`translate`, `scale`, `rotate` are **polymorphic**: they dispatch on the type of the first argument and work uniformly across mesh, SDF, and 2D shape.

```clojure
(translate thing dx dy [dz])       ; mesh / SDF take 3 args; shape takes 2
(scale thing s)                    ; uniform
(scale thing sx sy [sz])           ; per-axis (3 for mesh/SDF, 2 for shape)
(rotate thing :x|:y|:z angle-deg)  ; cardinal axis (mesh / SDF)
(rotate thing [ax ay az] angle-deg); arbitrary axis (mesh / SDF)
(rotate shape angle-deg)           ; 2D shape: implicit Z axis
```

**Pivot conventions** differ by type and match each type's natural reference:

| Type | translate | scale around | rotate around |
|------|-----------|--------------|----------------|
| Mesh | world axes | mesh centroid | mesh centroid |
| SDF | world axes | world origin | world origin |
| 2D shape | shape's local frame | shape centroid | shape origin (0, 0) |

For an SDF that's already off-origin and you want to scale or rotate "in place", compose with translation: `(translate (rotate (translate sdf -dx -dy -dz) :y 30) dx dy dz)`.

**Arbitrary-axis rotation on SDF** is implemented internally as a ZYX Tait-Bryan decomposition into three cardinal-axis rotations. This is invisible to the caller (you just get the rotation you asked for), but worth knowing if you hit numerical edge cases near gimbal lock (pitch ≈ ±90° with non-zero yaw or roll).

**Type-specific aliases** are still bound (`mesh-translate`, `mesh-scale`, `translate-shape`, `scale-shape`, `rotate-shape`) for backward compatibility and for code that wants to declare type intent at the call site. They route to the same implementations as the polymorphic forms.

**Legacy `scale` inside attach.** Inside an `attach` body, `(scale 1.5)` (with a single number, no thing) keeps its historical meaning of scaling the currently-attached mesh — see [scale (attach context)](#scale-attach-context). The polymorphic `scale` recognises the number-first form and dispatches to that legacy path.

### attach

Transform a mesh, panel, or SDF, returning a new value (functional, original unchanged):

```clojure
(register b (box 20))

;; Move the entire mesh (returns new mesh)
(register b (attach b (f 10) (th 45)))

;; Create a transformed copy with a different name
(register b2 (attach b (th 45) (f 10)))

;; Works with panels too
(register label (attach label (f 20) (th 90)))

;; And with SDF nodes — the path commands replay on a fresh turtle, and
;; the resulting rigid transform (rotation + translation) is applied to
;; the SDF. Useful for placing an SDF at a turtle pose without manually
;; composing translate / rotate.
(register cap (attach (sdf-sphere 5) (tv 60) (tr 30) (f 30)))
```

SDF attach is **incremental**: the path is walked one command at a time, and each command transforms the SDF tree directly. Movement (`f`, `rt`, `u`, `lt`), rotation (`th`, `tv`, `tr`, `set-heading`), creation-pose shifts (`cp-f`, `cp-rt`, `cp-u`), `mark`, and `move-to` (with or without `:align`) all work on SDFs the same way they do on meshes.

| Command | Effect on SDF |
|---------|---------------|
| `f`, `rt`, `u`, `lt` | Translate the SDF; advance the turtle |
| `th`, `tv`, `tr` | Rotate the SDF around the current turtle position by the corresponding axis (up / right / heading) |
| `set-heading` | Replace turtle heading/up; SDF unchanged |
| `cp-f`, `cp-rt`, `cp-u` | Translate the SDF in the *opposite* direction (anchor stays, geometry slides) |
| `mark :name` | Record the current turtle pose as a named anchor on the SDF |
| `move-to … [:align]` | Snap turtle to target anchor / centroid / pose; with `:align`, also rotate the SDF to match the anchor's frame |

The anchors recorded by `mark` survive through subsequent transforms and through SDF booleans (the second argument's anchors are merged in, first-wins on name collision). They also cross the SDF→mesh boundary: when an SDF is materialized, its anchors carry over to the resulting mesh.

Two commands remain rejected with an explanatory error:

| Command | Reason |
|---------|--------|
| `inset` | Mesh-face-specific, no SDF analogue |
| `(scale n)` | The legacy turtle-mesh form; for SDF use top-level `(scale sdf n)` outside the attach |

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

### Operations available inside attach / attach!

The body of `attach` and `attach!` is a turtle path (the macros wrap it in `(path ...)`), so most turtle-path commands are available. Some are turtle-frame transformations (move/rotate/curve), some are attach-specific (operate on the attached mesh), and some shift the mesh's creation-pose without moving the geometry.

**Movement (turtle frame):**

| Command | Description |
|---------|-------------|
| `(f dist)` | Forward along heading |
| `(u dist)` / `(d dist)` | Up/down along the up axis (`d` = down) |
| `(rt dist)` / `(lt dist)` | Right/left along the right axis (heading x up) |

**Rotation (turtle frame):**

| Command | Description |
|---------|-------------|
| `(th angle)` | Yaw (turn around up) |
| `(tv angle)` | Pitch (turn around right) |
| `(tr angle)` | Roll (turn around heading) |

**Curves (decompose into f + rotation):**

| Command | Description |
|---------|-------------|
| `(arc-h r ang)` / `(arc-v r ang)` | Horizontal/vertical arcs |
| `(bezier-to ...)` / `(bezier-to-anchor ...)` | Cubic bezier moves |
| `(bezier-as path)` | Bezier-smooth a recorded path |

**Attach-specific (act on the attached mesh):**

| Command | Description |
|---------|-------------|
| `(move-to target)` | Snap to another object's pose (see below) |
| `(play-path p)` | Replay a recorded path's movements (see below) |
| `(scale factor)` | Uniformly scale the attached mesh in place |

**Creation-pose shift (slide the geometry under a stationary anchor so a chosen feature point coincides with it):**

| Command | Description |
|---------|-------------|
| `(cp-f dist)` / `(cp-rt dist)` / `(cp-u dist)` | Re-anchor at the point `+dist` along heading / right / up: geometry slides by `-dist` along that axis, anchor unchanged |

The `cp-*` commands re-pick which point of the mesh coincides with its anchor. The anchor's world position stays put; the geometry translates so the chosen local point now sits on it. Useful when later `attach`/`move-to` should land things on a face/edge/feature instead of the centroid, or when you want a rotation pivot at a specific feature point.

### move-to

Move to another object's position and adopt its orientation (inside `attach`/`attach!`):

```clojure
(move-to :name)              ; move to creation-pose + adopt its heading/up (default)
(move-to :name :center)      ; move to centroid only, keep current heading
(move-to :name :at :anchor)  ; move to a named anchor (from attach-path) + adopt its heading/up
```

After `(move-to :A)`, the turtle is at A's position with A's orientation. "Forward" means A's forward, "up" means A's up. This makes relative positioning work correctly even if A has been rotated.

```clojure
(register base (box 40))
(attach! :base (th -90) (f 50) (th 90))   ; move base to X=50

(register sfera (sphere 10))
;; Place sphere on top of base (wherever base is now)
(attach! :sfera (move-to :base) (tv 90) (f 30) (tv -90))
```

Use `move-to` whenever positioning relative to another object. Use `:center` mode when you only need centroid alignment without orientation change. Use `:at :anchor` to snap to a named anchor previously associated to the mesh via `attach-path` (see [Scene → Mesh anchors](#13-scene)) — throws if the anchor doesn't exist.

```clojure
(register upper (extrude (circle 1.5) (f 15)))
(attach-path :upper (path (mark :top) (f 15) (mark :tip)))

(register lower (extrude (circle 1.2) (f 10)))
;; Snap lower's origin to upper's :tip anchor; turtle adopts :tip's frame
(attach! :lower (move-to :upper :at :tip))
```

Like the default form, `:at` moves the mesh and re-orients the turtle frame for chained ops; the mesh itself is translated, not rotated. To rotate the child to match the anchor's orientation, add the `:align` flag (see below) or prefer the path-driven assembly form.

#### `:align` — translate AND rotate the mesh

By default `move-to … :at :anchor` only translates the mesh; the turtle's heading/up adopt the anchor's pose for subsequent ops, but the mesh's vertices keep their construction orientation. Add `:align` after the anchor name to also rotate the mesh so its current frame snaps onto the anchor's frame:

```clojure
(attach! :child (move-to :parent :at :slot))         ; translate only
(attach! :child (move-to :parent :at :slot :align))  ; translate + rotate
```

The rotation is computed in two steps:
1. rotate the mesh so its current heading aligns with the anchor's heading,
2. then rotate around the new heading so the mesh's up aligns with the anchor's up.

This is the natural primitive when the anchor's orientation is meaningful — e.g. a path mark whose heading was set by an `(th 180)` to flag a flipped slot, or a skeleton-driven assembly where each mark records "which way this part should face". The cerniera2_C example uses `:align` to snap symmetric brackets onto skeleton marks whose heading encodes the outward-facing side.

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

### Static assembly via anchors

`link!` (see [Scene → Mesh anchors](#13-scene)) only takes effect during animation playback — it adds the parent's runtime delta but does not snap meshes at construction time. For static (non-animated) assembly using anchors, two patterns work today.

**Path-driven turtle.** Build both meshes inside the same `with-path` scope, using `goto` to position the turtle at a mark before extruding:

```clojure
(def arm-sk (path (mark :shoulder) (f 15) (mark :elbow) (f 12) (mark :wrist)))

(register upper
  (with-path arm-sk
    (extrude (circle 1.5) (path-to :elbow))))

(register lower
  (with-path arm-sk
    (goto :elbow)                      ; adopt elbow's position + heading + up
    (extrude (circle 1.2) (path-to :wrist))))
```

Both meshes are built directly in world coordinates against the same skeleton, with the lower segment's vertices generated along the elbow→wrist direction. No post-hoc snapping required. For a more compact, hierarchical form see [Hierarchical assemblies](#hierarchical-assemblies).

**Snap-to-anchor with `move-to :at`.** When the child mesh already exists, snap it to a named anchor of the parent:

```clojure
(register upper (extrude (circle 1.5) (f 15)))
(attach-path :upper (path (mark :top) (f 15) (mark :tip)))

(register lower (extrude (circle 1.2) (f 10)))
(attach! :lower (move-to :upper :at :tip))      ; lower's origin -> :tip
```

This is convenient when the children come from independent sources, but the child mesh is only translated — its own heading/up don't rotate to match the anchor (consistent with the default `move-to`). If you need the child's geometry to follow the anchor's orientation, prefer the path-driven approach above, where the extrusion direction follows from `goto`.

### scale (attach context)

Inside an `attach` / `attach!` body, `(scale factor)` (a number-first call, no value argument) scales the currently-attached mesh in place. `(scale [sx sy sz])` or `(scale sx sy sz)` does a non-uniform scale along the local axes.

```clojure
(register b (box 20))

;; Half size in place
(attach! :b (scale 0.5))

;; Combine with other ops: move, then double size, then rotate
(attach! :b (f 10) (scale 2) (th 45))
```

This is a special case of the polymorphic `scale` — the dispatcher recognises the leading-number form and routes here. `(scale value …)` with a non-number first argument does the regular value transform on a mesh, SDF, or 2D shape (see [Top-level transforms](#top-level-transforms)). For 2D shapes specifically, the `scale-shape` alias still works.

### Lateral movement

Pure translation along the turtle's local axes. No heading/up change, no ring generation.

| Command | Description |
|---------|-------------|
| `(u dist)` | Move along up axis |
| `(d dist)` | Move opposite to up axis |
| `(rt dist)` | Move along right axis (heading x up) |
| `(lt dist)` | Move opposite to right axis |

Blocked inside `path`, `extrude`, `loft` (would produce degenerate rings). Allowed at top level, inside `attach`/`attach!`, and in animation spans.

```clojure
(f 50) (u 30) (th 180)        ; forward, up, face back
(attach! :gear (rt 10))        ; slide gear right
```

For named multi-mesh assemblies (puppets, robots, articulated bodies), see [Hierarchical assemblies](#hierarchical-assemblies) under [Scene](#13-scene).

---

## 10. Spatial Deformation (warp)

Deform mesh vertices inside a volume. The volume shape (sphere, box, cylinder, cone) determines the deformation zone. Positioned via `attach`.

```clojure
(warp mesh volume deform-fn)
(warp mesh volume deform-fn1 deform-fn2)       ; Chain multiple deformations
(warp mesh volume (inflate 3) :subdivide 2)     ; Subdivide before deforming
```

**Deform-fn signature:** `(fn [pos local-pos dist normal vol] -> new-pos)`
- `pos`: world position `[x y z]`.
- `local-pos`: normalized position in volume `[-1, 1]` per axis.
- `dist`: normalized distance from center (0=center, 1=boundary).
- `normal`: estimated vertex normal.
- `vol`: volume bounds map.

**Options:**
- `:subdivide n`: midpoint-subdivide triangles inside volume n times before deforming (each pass: 1 triangle becomes 4, edges split at midpoints). Useful for low-poly meshes that need smooth deformation. Note: drops `:face-groups` metadata.

### Preset deformations

| Function | Description |
|----------|-------------|
| `(inflate amount)` | Push vertices outward along normals |
| `(dent amount)` | Push vertices inward (opposite of inflate) |
| `(attract strength)` | Pull toward volume center (0=none, 1=full) |
| `(twist angle)` | Rotate around axis (auto-detected for cyl/cone) |
| `(twist angle :x)` | Twist around explicit axis (:x :y :z) |
| `(squash axis)` | Flatten toward plane through center |
| `(squash axis amount)` | Partial flatten (0=flat, 1=no effect) |
| `(roughen amplitude)` | Noise displacement along normals |
| `(roughen amplitude frequency)` | With spatial frequency control |

All presets use smooth falloff (hermite: `3t^2 - 2t^3`). `smooth-falloff` is available as a standalone function.

### Examples

```clojure
;; Organic bump on a box
(register b (warp (box 40) (sphere 25) (inflate 5) :subdivide 2))

;; Dent on a sphere
(register s (warp (sphere 30 32 16) (attach (sphere 10) (f 15)) (dent 3)))

;; Twisted cylinder
(register c (warp (cyl 10 40 32) (cyl 12 40) (twist 90)))

;; Roughened surface
(register r (warp (sphere 20 32 16) (sphere 22) (roughen 2 3)))
```

---

## 11. SDF Modeling

> **Desktop only.** SDF operations require the libfive backend (Rust server). Not yet available in the browser version.

SDF nodes are **pure data**: lightweight descriptions of implicit surfaces. No geometry is computed until meshing is needed (at `register`, boolean, or export boundaries). This enables smooth blending, morphing, and shelling that are impossible with mesh-based CSG.

### Architecture

```
Clojure DSL  ->  SDF tree (maps)  ->  JSON  ->  Rust/libfive  ->  triangle mesh
```

SDF trees are immutable Clojure maps:

```clojure
{:op "sphere" :r 5.0}
{:op "union" :a {:op "sphere" :r 5.0} :b {:op "box" :sx 8.0 :sy 8.0 :sz 8.0}}
```

Meshing is **lazy**: it happens automatically when an SDF meets a mesh boundary (e.g. `register`, boolean with a mesh, export).

### Primitives

```clojure
(sdf-sphere r)                  ; Sphere centered at origin
(sdf-box sx sy sz)              ; Axis-aligned box with dimensions sx x sy x sz
(sdf-cyl r h)                   ; Cylinder along Z axis with radius r and height h
(sdf-rounded-box sx sy sz r)    ; Box with rounded corners (true SDF)
(sdf-torus R r)                 ; Torus in the XY plane around Z. R = major, r = minor
```

Prefer `sdf-rounded-box` over `(sdf-offset (sdf-box ...) r)` when combining with other SDFs (see [SDF-specific operations](#sdf-specific-operations) below for why offset is not a true SDF).

### Booleans

```clojure
(sdf-union a b)                 ; Combine two SDF shapes
(sdf-difference a b)            ; Subtract b from a
(sdf-intersection a b)          ; Keep only the overlap of a and b
```

### SDF-specific operations

These operations leverage the implicit representation and have no direct mesh equivalent:

| Function | Description |
|----------|-------------|
| `(sdf-blend a b k)` | Smooth union between a and b. k controls the blend radius (higher values produce a wider, smoother transition) |
| `(sdf-blend-difference a b k)` | Smooth subtraction: removes b from a with a soft concavity of radius k. Dual to `sdf-blend` for the union case |
| `(sdf-half-space)` / `(sdf-half-space :cut-ahead)` | Half-space defined by the turtle's pose. The cut plane passes through the turtle position with normal equal to the heading. Default keeps the half *behind* the heading; `:cut-ahead` keeps the half *ahead*. See below |
| `(sdf-clip shape)` | Convenience: `(sdf-intersection shape (sdf-half-space))`. Clips `shape` against the turtle's plane, keeping the half behind the heading |
| `(sdf-shell a thickness)` | Hollow shell with uniform wall thickness |
| `(sdf-offset a amount)` | Expand (positive) or contract (negative) the surface by amount. Note: `sdf-offset` shifts the field by `amount`, which produces a non-SDF away from the surface. For rounded boxes prefer `sdf-rounded-box`; for shell-like operations the result may not combine cleanly with `sdf-intersection` of other SDFs. |
| `(sdf-morph a b t)` | Interpolate between shapes a and b. t ranges from 0 (= a) to 1 (= b) |
| `(sdf-displace node formula)` | Displace surface by a spatial formula (quoted expression using x, y, z) |

**Half-space and clip.** `sdf-half-space` returns the half-space defined by the current turtle pose: the cut plane passes through the turtle position with normal equal to the heading. By default it keeps the side *behind* the heading — the side the turtle came from. The convention matches `extrude`: after extruding a solid the turtle ends on the far face, with the material behind it; `(sdf-half-space)` at that pose returns the half-space containing the material.

For the rare case where you want the front half:

```clojure
(sdf-intersection shape (sdf-half-space :cut-ahead))
```

`sdf-clip` is a one-arg shortcut for the common case:

```clojure
;; Keep the lower half of a cylinder. Turtle at origin facing +Z.
(tv 90)
(sdf-clip (rotate (sdf-cyl r l) :y 90))

;; Same idea inside a (turtle ...) scope to leave global turtle state untouched.
(turtle (tv 90)
  (sdf-clip (rotate (sdf-cyl r l) :y 90)))
```

`sdf-displace` adds the formula's value to the distance field at each point. Positive values push the surface inward, negative values push outward:

```clojure
;; Wavy sphere
(register wavy (sdf-displace (sdf-sphere 10) '(* 1.5 (sin (* x 2)) (sin (* y 2)))))

;; Displace any SDF (works with blends, booleans, etc.)
(register organic
  (sdf-displace
    (sdf-blend (sdf-sphere 10) (sdf-box 14 14 14) 2)
    '(* 0.5 (sin (* x 3)) (cos (* z 3)))))
```

### Transforms

SDFs use the **polymorphic** transforms `translate`, `scale`, `rotate`, the same names as for meshes and 2D shapes (full description in [Top-level transforms](#top-level-transforms)). The `sdf-move`, `sdf-scale`, `sdf-rotate` names of earlier versions are gone.

```clojure
(translate node dx dy dz)       ; Translate an SDF node
(rotate node axis angle-deg)    ; Rotate. axis = :x | :y | :z, or [ax ay az] (arbitrary)
(scale node s)                  ; Uniform scale
(scale node sx sy sz)           ; Per-axis scale
(sdf-revolve node-2d)           ; Revolve a 2D SDF (X=radius, Y=height) around Z
```

Cardinal-axis rotations dispatch directly to libfive's `rotate_x/y/z`. Arbitrary-axis rotations decompose into a ZYX Tait-Bryan triple; the decomposition can lose one degree of freedom near gimbal lock (pitch ≈ ±90°), but the visible rotation remains consistent. SDFs rotate around the **world origin**, not their bounding-box centroid — to rotate an off-origin SDF in place, sandwich the rotation between matched translations.

### Materialization

```clojure
(sdf->mesh node)                            ; Convert SDF tree to triangle mesh
(sdf->mesh node bounds resolution)          ; With custom bounds and resolution
;; bounds: [[xmin xmax] [ymin ymax] [zmin zmax]]
;; resolution: voxels per unit
```

Materialization is normally automatic. Call `sdf->mesh` only when you need explicit control over bounds or resolution.

**Resolution**: a global meshing resolution governs auto-meshing of SDF nodes (default 15, "turtle-style" — same scale as `(resolution :n N)` for curves). Bump it with `(sdf-resolution! 60)` before `register` to get a finer mesh. Higher = finer but slower; total voxel count is also capped to keep meshes printable. When the tree contains thin features (`sdf-shell`, small `sdf-offset`), resolution is automatically boosted to guarantee at least 3 voxels across the thinnest part.

For full control, call `sdf->mesh` directly with explicit `bounds` and `resolution` (voxels per unit) — bypasses the auto-resolution and auto-bounds entirely.

### Custom formulas

`sdf-formula` compiles a quoted Clojure math expression into an SDF tree. The variables `x`, `y`, `z` represent spatial coordinates. Since `sdf-formula` is a function (not a macro), expressions are composable: you can build them with functions, store them in variables, and pass them around.

```clojure
(sdf-formula 'expr)           ; quoted literal
(sdf-formula (build-expr))    ; from a function
```

**Available operations:**
- Arithmetic: `+`, `-`, `*`, `/`.
- Trig: `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`.
- Math: `sqrt`, `abs`, `exp`, `log`, `pow`, `mod`, `square`, `neg`.
- Comparison: `min`, `max`.

**Gotcha (`pow` with negative bases):** libfive computes `pow(a, b)` as `exp(b * log(a))`, which returns NaN when `a < 0`. For squaring an expression that may be negative (e.g. `(- (mod x p) p/2)`), use `(* expr expr)` instead of `(pow expr 2)`. NaN propagates silently and produces hollow / broken meshes.

**Coordinate variables:**

| Variable | Description |
|----------|-------------|
| `x`, `y`, `z` | Cartesian coordinates |
| `r` | Distance from origin: `sqrt(x^2 + y^2 + z^2)` |
| `rho` | Cylindrical radius: `sqrt(x^2 + y^2)` |
| `theta` | Azimuthal angle around Z: `atan2(y, x)` |
| `phi` | Polar angle from Z: `atan2(sqrt(x^2+y^2), z)` |

Spherical/cylindrical variables are synthetic: they expand to sub-trees of x, y, z. Useful for patterns that follow curved surfaces:

```clojure
;; Radial displacement that follows the sphere's curvature
(register bumpy
  (sdf-displace (sdf-sphere 10)
    '(* 1.5 (sin (* theta 6)) (sin (* phi 6)))))

;; Cylindrical fluting
(register fluted
  (sdf-displace (sdf-cyl 8 20) '(* 0.5 (cos (* theta 12)))))
```

Formulas produce infinite implicit surfaces. Intersect with a bounding shape to get a finite solid:

```clojure
;; Wave surface (quoted literal)
(register wave
  (sdf-intersection
    (sdf-formula '(- z (* 2 (sin (* x 0.5)) (cos (* y 0.5)))))
    (sdf-box 30 30 10)))

;; Composable: build formulas with functions
(defn mk-wave [freq amp]
  (list '- 'z (list '* amp (list 'sin (list '* 'x freq))
                              (list 'cos (list '* 'y freq)))))

(register wave2
  (sdf-intersection
    (sdf-formula (mk-wave 0.3 4))
    (sdf-box 40 40 10)))
```

### TPMS (Triply Periodic Minimal Surfaces)

Pre-built lattice structures for organic/structural infills:

```clojure
(sdf-gyroid period thickness)       ; Gyroid (most common TPMS for 3D printing)
(sdf-schwarz-p period thickness)    ; Schwarz-P (cubic channels)
(sdf-diamond period thickness)      ; Diamond / Schwarz-D (tetrahedral cells)
```

- `period` = cell size (larger = coarser lattice).
- `thickness` = wall thickness.

TPMS are infinite. Intersect with a shape to bound them:

```clojure
;; Gyroid-filled sphere
(register infill (sdf-intersection (sdf-sphere 20) (sdf-gyroid 8 0.5)))

;; Schwarz-P cube
(register lattice (sdf-intersection (sdf-box 30 30 30) (sdf-schwarz-p 10 0.8)))
```

### Periodic patterns

Infinite repeating patterns for perforations, lattices, and structural infills:

| Function | Description |
|----------|-------------|
| `(sdf-slats axis period thickness)` | Infinite parallel flat walls perpendicular to axis (`:x` `:y` `:z`) |
| `(sdf-slats axis period thickness phase)` | With phase offset along axis (e.g. period/2 = stagger) |
| `(sdf-bars axis period radius)` | Infinite parallel cylindrical bars along axis (period can be a number or `[pa pb]` for different periods on the two perpendicular axes) |
| `(sdf-bars axis period radius phase-a phase-b)` | With phase offsets on the two perpendicular axes |
| `(sdf-bar-cage sx sy sz n radius)` | Cage of `n x n` bars per direction aligned to a centered box, with bars on all edges/corners. Options: `:axes [:x :y :z]` to choose directions, `:blend k` for smooth joints (caveat: not a true SDF) |
| `(sdf-grid period thickness)` | 3D grid lattice with sharp edges (union of three slat sets) |
| `(sdf-grid period thickness blend-k)` | Grid with smooth blended joints (see warning below) |

- `period` = center-to-center distance.
- `thickness` / `radius` = wall thickness or bar radius.
- `phase` = positional offset (omit or 0 = pattern centered at origin).
- `blend-k` = blend radius for smooth joints (omit for sharp edges).

**Warning**: The blend version uses libfive's exponential blend, which does not produce a valid SDF. The gradient can invert at joint regions, causing flipped face normals when combined with `sdf-intersection` / `sdf-difference`. For printable parts always prefer the sharp-edge 2-arg version.

These are infinite. Use `sdf-difference` to punch holes, or `sdf-intersection` to bound:

```clojure
;; Punch slat holes through a shell
(register vase
  (sdf-difference container (sdf-slats :x 8 2)))

;; Grid lattice bounded by a sphere (sharp edges)
(register ball (sdf-intersection (sdf-sphere 20) (sdf-grid 8 1.5)))

;; Perforated box (grid carved out of a solid box)
(register perforated
  (sdf-intersection (sdf-box 40 40 40) (sdf-grid 10 2)))

;; Bar cage basket: rounded container with a 5x5 cage of bars on all 3 axes,
;; hollowed out with an open top
(register basket
  (sdf-difference
    (sdf-intersection
      (sdf-rounded-box 60 60 90 6)
      (sdf-bar-cage 60 60 90 5 1.5))
    ;; Hollow interior (open at top)
    (translate (sdf-rounded-box 56 56 100 6) 0 0 4)))
```

### Examples

```clojure
;; Simple sphere
(register s (sdf-sphere 10))

;; Box with rounded edges via offset
(register rounded (sdf-offset (sdf-box 18 18 18) 2))

;; Smooth blend of sphere and box
(register blob (sdf-blend (sdf-sphere 10) (sdf-box 14 14 14) 3))

;; Hollow shell
(register shell (sdf-shell (sdf-sphere 15) 1))

;; Composed: blended union with cutout
(register part
  (sdf-difference
    (sdf-blend (sdf-sphere 12) (sdf-cyl 8 20) 2)
    (translate (sdf-box 6 6 30) 0 0 0)))

;; Rotated box
(register tilted (rotate (sdf-box 20 10 5) :z 45))

;; Scaled cylinder (elliptical cross-section)
(register ellip (scale (sdf-cyl 10 20) 2 1 1))

;; Torus via sdf-revolve: circle profile at distance 10 from Z axis
(register torus
  (sdf-revolve
    (sdf-formula '(- (sqrt (+ (* (- x 10) (- x 10)) (* y y))) 3))))

;; Bowl/vase via sdf-revolve: curved profile as 2D SDF
;; X = radial distance, Y = height
(register vase
  (sdf-shell
    (sdf-revolve
      (sdf-formula '(- x (+ 30 (* 10 (cos (* y 0.05)))))))
    2))

;; Explicit high-res meshing
(register hires (sdf->mesh (sdf-sphere 10) [[-12 12] [-12 12] [-12 12]] 30))
```

---

## 12. Text

### Text shapes

Convert text to 2D shapes using opentype.js font parsing:

```clojure
;; Basic text shape (uses default Roboto font)
(text-shape "Hello")                    ; Returns vector of shapes (with holes)

;; With size
(text-shape "Hello" :size 30)           ; Larger text

;; Extrude for 3D text — single mesh out, ready for booleans.
(register title (extrude (text-shape "RIDLEY" :size 40) (f 5)))

;; Individual character shapes (one shape per character, no composite handling)
(text-shapes "ABC" :size 20)            ; Returns vector of shapes

;; Single character shape
(char-shape "A" font size)              ; Returns shape for one character

;; Load custom font
(load-font! "/path/to/font.ttf")        ; Returns promise
(load-font! :roboto-mono)               ; Built-in monospace font

;; Check if font is ready
(font-loaded?)                          ; true when default font loaded
```

**Return value of `text-shape`.** A vector of shapes, with one entry per **outer contour** found in the string — not strictly one per character. Composite glyphs produce multiple shapes:

| Glyph                     | Outer contours | Shapes emitted |
|---------------------------|----------------|----------------|
| `I`, `O`, `c`, `n` …      | 1              | 1              |
| `i`, `j`                  | 2 (stem + tittle) | 2          |
| `à`, `è`, `é`, `ì`, `ò`, `ù` | 2 (letter + accent) | 2     |
| `ä`, `ö`, `ü`, `ñ`        | 3 (letter + 2 marks / tilde) | 3 |

Holes (counters inside `o`, `a`, `B`, etc.) are attributed to the smallest containing outer, so `ä` correctly yields a body-with-counter plus two solid dots.

Pass the whole vector to `extrude` — it combines the per-shape extrusions into a single mesh.

**Built-in fonts:**
- `:roboto`: Roboto Regular (default).
- `:roboto-mono`: Roboto Mono (monospace).

### Text on path

Place 3D text along a curved path:

```clojure
;; Define a curved path
(def curve (path (dotimes [_ 40] (f 2) (th 3))))

;; Place text along the curve
(register curved-text (text-on-path "Hello" curve :size 15 :depth 3))
```

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `:size` | 10 | Font size in units |
| `:depth` | 5 | Extrusion depth |
| `:font` | Roboto | Custom font object |
| `:spacing` | 0 | Extra letter spacing (can be negative) |
| `:align` | `:start` | Alignment: `:start`, `:center`, `:end` |
| `:overflow` | `:truncate` | What to do when text is longer than path |

**Overflow modes:**
- `:truncate`: stop placing letters when path ends (default).
- `:wrap`: continue from start (for closed paths).
- `:scale`: scale text to fit path length.

---

## 13. Scene

### Registry

Named objects persist across evaluations. The registry holds two kinds of objects:

- **Renderable** (meshes, panels): have visibility, appear in the viewport.
- **Abstract** (paths, shapes): data-only, no visibility concept.

```clojure
;; Register: define var, add to registry, show on first registration
(register torus (extrude-closed (circle 5) square-path))
;; Creates 'torus' var, registers as :torus, makes visible

;; r is a short alias for register
(r torus (extrude-closed (circle 5) square-path))

;; Register with :hidden flag (registers but does not show)
(register torus (extrude-closed (circle 5) square-path) :hidden)

;; Register abstract objects (paths, shapes, no visibility)
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

### Hierarchical assemblies

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
;;   :puppet/r-arm/upper -> :puppet/torso at :shoulder-r
;;   :puppet/r-arm/lower -> :puppet/r-arm/upper at :elbow
;; All links have :inherit-rotation true
;; Skeletons auto-attached to first mesh in each with-path frame
```

Show/hide supports prefix matching for assembly subtrees:

```clojure
(hide :puppet/r-arm)             ; Hide all meshes under :puppet/r-arm
(show :puppet)                   ; Show all puppet parts
(hide :puppet/r-arm/hand/thumb)  ; Hide single part
```

### 3D Panels

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

### Color and material

Set color and material properties for subsequently created meshes:

```clojure
(color 0xff0000)                 ; Set color by hex value (red)
(color r g b)                    ; Set color by RGB components (0-255)

(material :metalness 0.8         ; Set material properties
          :roughness 0.2)

(reset-material)                 ; Reset to default material
```

Color and material are stored in turtle state and applied to all meshes created after the call (primitives, extrusions, etc.).

**Per-mesh color and material.** Change color or material on a specific registered mesh (without re-creating it):

```clojure
(color :my-mesh 0xff0000)        ; Set color on registered mesh (hex)
(color :my-mesh 255 0 0)         ; Set color on registered mesh (RGB)

(material :my-mesh :metalness 0.8 :roughness 0.2)  ; Per-mesh material
```

The first argument is the registered name (keyword). Remaining arguments follow the same format as the global versions.

**Pure color and material on mesh values.** `color` and `material` can also take a mesh value (map with `:vertices` and `:faces`) as first argument. In this case they return a **new mesh** with the material properties merged in, without mutating any state:

```clojure
(color my-mesh 0xff8800)         ; Returns mesh with color set
(color my-mesh 255 128 0)        ; Returns mesh with color set (RGB)

(material my-mesh :opacity 0.3)  ; Returns mesh with opacity
(material my-mesh :opacity 0.3 :color 0xff0000)  ; Multiple properties
```

This is useful for inline use with `register` or `tweak`:

```clojure
(register bowl (material (make-bowl 30 20) :opacity 0.3))
(tweak (material (sphere 20) :opacity 0.5))
```

### Viewport control

**Camera:**

```clojure
(fit-camera)                     ; Fit viewport to all visible geometry
```

**Visibility toggles.** From REPL:

```clojure
(show-lines)                     ; Show construction lines
(hide-lines)                     ; Hide construction lines
(lines-visible?)                 ; Check visibility
```

The desktop toolbar has buttons for:
- Grid toggle.
- Axes toggle.
- Turtle indicator toggle.
- Construction lines toggle.

---

## 14. Live & Interactive

### Tweaking

The `tweak` macro provides interactive parameter exploration with real-time preview. It evaluates an expression, displays the result in the viewport, and creates sliders for numeric literals.

```clojure
;; Default: slider for first literal only
(tweak (extrude (circle 15) (f 30)))                       ; edits 15

;; Specific index (0-based; literals collected depth-first, left-to-right)
(tweak 2 (extrude (circle 15) (f 30) (th 90) (f 20)))      ; indices: 0=15 1=30 2=90 3=20 ; edits 90

;; Negative index (from end, Python-style)
(tweak -1 (extrude (circle 15) (f 30) (th 90) (f 20)))     ; edits 20

;; Multiple indices
(tweak [0 -1] (extrude (circle 15) (f 30) (th 90) (f 20))) ; edits 15 and 20

;; All numeric literals
(tweak :all (extrude (circle 15) (f 30) (th 90) (f 20)))   ; edits 15, 30, 90, 20
```

**Registry-aware mode.** When `tweak` receives a keyword, it operates on the named registered mesh: hides the original during tweaking, re-registers the result on OK, and restores the original on Cancel.

```clojure
;; Tweak using stored source form (set by register)
(tweak :A)

;; Tweak with explicit expression
(tweak :A (attach (sphere 20) (f 10)))

;; With filter + registry name
(tweak :all :A)
(tweak -1 :A)
(tweak [0 2] :A)

;; Filter + registry name + explicit expression
(tweak :all :A (attach (sphere 20) (f 10)))
```

**Note:** `(tweak :A)` requires a stored source form. `register` stores the source automatically, but the form must be self-contained. If `(register A (make-a 1))` is used, `make-a` must still be defined as a function (not overwritten by `register`). Use distinct names for generator functions and registered meshes (e.g., `make-vase` and `vase`).

**Features:**
- Automatically detects result type (mesh, shape, path) and previews in viewport.
- Slider range: `[value * 0.1, value * 3]` (or `[-50, 50]` for zero).
- Zoom buttons (`-`/`+`) re-center and narrow/widen the slider range.
- OK confirms and prints the final expression; Cancel (or Escape) discards.
- Auto-cancels when a new REPL command is entered.
- Debounced re-evaluation (~100ms).

### Animation

Define timeline-based animations that preprocess turtle commands into per-frame pose arrays for O(1) playback.

**Defining animations:**

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

;; Camera animation (orbital mode, automatic when target is :camera)
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
(anim! :spin-forever 2.0 :gear :loop          ;; forward: 0->1, 0->1, ...
  (span 1.0 :linear (tr 360)))
(anim! :unwind 2.0 :gear :loop-reverse        ;; reverse: 1->0, 1->0, ...
  (span 1.0 :linear (tr 360)))
(anim! :sway 2.0 :gear :loop-bounce           ;; bounce: 0->1->0->1, ...
  (span 1.0 :in-out (tr 90)))
```

**Span.** A timeline segment with weight, easing, and turtle commands:

```clojure
(span weight easing & commands)
(span weight easing :ang-velocity N & commands)
```

- **weight**: fraction of total duration (spans are normalized to sum to 1.0).
- **easing**: `:linear`, `:in`, `:out`, `:in-out`, `:in-cubic`, `:out-cubic`, `:in-out-cubic`, `:spring`, `:bounce`.
- **:ang-velocity N**: controls rotation timing. Default 1 (rotations are visible). 0 = instantaneous. N > 0 means 360deg takes as long as `(f N)`.
- **:on-enter expr**: callback executed when playhead enters this span.
- **:on-exit expr**: callback executed when playhead exits this span.
- **commands**: `(f dist)`, `(th angle)`, `(tv angle)`, `(tr angle)`, `(u dist)`, `(d dist)`, `(rt dist)`, `(lt dist)`, `(parallel cmd1 cmd2 ...)`.

**Parallel commands.** Wrap commands in `parallel` to execute them simultaneously over the same frames:

```clojure
;; Sequential: first orbit, then elevate
(span 1.0 :linear (rt 360) (u 90))

;; Parallel: diagonal orbit (both at the same time)
(span 1.0 :linear (parallel (rt 360) (u 90)))
```

A parallel group's frame allocation = max of its sub-commands. All sub-commands are applied at the same fractional progress for each frame.

**Procedural animations.** Procedural animations call a mesh-generating function every frame. The function receives eased `t` (0->1) and returns a new mesh:

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

;; Bounce: smooth grow/shrink (t goes 0->1->0->1...)
(anim-proc! :breathe 2.0 :blob :in-out :loop-bounce
  (fn [t] (sphere (+ 5 (* 15 t)))))
```

Performance: `gen-fn` runs every frame (60fps). Keep it fast: avoid `mesh-union`/`mesh-difference` per frame. Keep face count constant when possible for the fast path.

**Mesh anchors.** Associate a path's marks as named anchor points on a mesh:

```clojure
;; Define a path with marks
(def arm-sk (path (mark :top) (f 15) (mark :elbow) (f 12) (mark :wrist)))

;; Register mesh and attach anchors
(register upper (extrude (circle 1.5) (f 15)))
(attach-path :upper arm-sk)
;; :upper now has :anchors {:top {...} :elbow {...} :wrist {...}}
```

Anchors store position, heading, and up vectors relative to the mesh's creation-pose. They are resolved on-demand at playback time.

`(anchors target)` returns named anchors as a `name → pose` map — useful when generating placements programmatically without having to remember (or re-derive) the mark names baked into the skeleton path. `target` can be a registered mesh name OR a path object directly:

```clojure
(anchors :upper)            ; => {:top {...} :elbow {...} :wrist {...}}
(keys (anchors :upper))     ; => (:top :elbow :wrist)

;; Or feed a path directly — its marks are resolved at the world origin:
(def skel (path (mark :pin) (f 50) (mark :tip)))
(anchors skel)              ; => {:pin {...} :tip {...}}
```

Both `move-to` and `anchors` accept a path object as `target`, so a skeleton path can be the assembly's source of truth without registering a carrier mesh:

```clojure
(def skel (path (mark :pin-axis) (f 50) (mark :slot)))

;; Snap a piece to a path mark:
(attach my-piece (move-to skel :at :slot :align))

;; Iterate over path marks by name:
(for [m (filter #(re-find #"bracket" (name %)) (keys (anchors skel)))]
  (attach bracket (move-to skel :at m :align)))
```

**Target linking.** Link a child target to a parent so the child inherits the parent's position delta at playback:

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

Preprocessing is unchanged: child frames are computed as if the parent is stationary. The link adds translation (and optionally rotation) at runtime. Targets are processed in topological order (parents before children).

**Playback control:**

```clojure
(play! :spin)           ; start playing
(play!)                 ; play all
(pause! :spin)          ; pause
(stop! :spin)           ; stop and reset
(stop-all!)             ; stop all
(seek! :spin 0.5)       ; jump to 50%
(anim-list)             ; list animations with status/type
```

**Easing:**

```clojure
(ease :in-out 0.5)      ; => eased value (0-1)
```

---

## 15. AI Describe (Accessibility)

Interactive AI-powered geometry description for screen reader users. See [Accessibility.md](Accessibility.md) for the full guide.

**Session commands:**

| Function | Description |
|----------|-------------|
| `(describe)` | Describe all visible geometry |
| `(describe :name)` | Describe a specific registered object |
| `(ai-ask "question")` | Ask a follow-up question in the active session |
| `(end-describe)` | Close the interactive session |
| `(cancel-ai)` | Cancel the in-progress AI call (session stays active) |
| `(ai-status)` | Check AI provider configuration |

**Workflow:**

```clojure
;; 1. Create some geometry
(register gear (mesh-difference
  (mesh-union (cyl 25 10)
    (mesh-union (for [i (range 12)]
      (attach (box 4 6 10) (th (* i 30)) (f 22)))))
  (cyl 6 12)))

;; 2. Start a describe session
(describe :gear)
;; -> Analyzing geometry... (generating views)
;; -> Sending to AI... (7 images + source code)
;; -> === Description of :gear ===
;; -> [AI-generated description]
;; -> ===

;; 3. Ask follow-up questions
(ai-ask "How thick are the gear teeth?")
(ai-ask "Would this print well without supports?")

;; 4. Close the session
(end-describe)
```

**Requirements:**
- A vision-capable AI provider must be configured (Gemini, Claude, GPT-4o).
- Configure via the Settings panel or from the REPL:

```clojure
(ai-status)  ;; Check current configuration
```

The describe pipeline relies on the same view-rendering primitives documented under [Export](#16-export) ([View capture](#view-capture-render-view-render-slice-save-views)). The 7 images sent to the AI are produced by `render-view` calls.

---

## 16. Export

### STL export

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

Pass `:3mf` as a trailing argument to export in 3MF format instead of STL:

```clojure
(export :torus :3mf)             ; torus.3mf
```

**Multi-material 3MF.** When two or more registered meshes have a color set
via `(color :name 0xRRGGBB)`, the 3MF file carries that information through
to the slicer:

- Each mesh becomes a separate `<object>` (no merging, unlike STL).
- A `<basematerials>` block holds one entry per distinct color, with the
  registered mesh name as label and the color as `displaycolor`.
- Each colored object points at the right entry via `pid`/`pindex`.

```clojure
(register :supporto (box 40 20 2))
(register :scritta  (extrude (text-shape "OK") :h 1))
(color :supporto 0xff0000)
(color :scritta  0xffffff)
(export :supporto :scritta :3mf)
```

In Bambu Studio / OrcaSlicer the two parts appear as distinct objects with
their colors preassigned, ready to be mapped to AMS slots. Identical colors
on multiple meshes share one material entry (same filament, distinct parts).
Meshes without a color produce the same plain-geometry output as before.

### View capture (render-view, render-slice, save-views)

Functions for rendering views of the scene to images. Useful for documentation, debugging, and the AI describe feature.

**Rendering views:**

| Function | Description |
|----------|-------------|
| `(render-view :front)` | Render an orthographic view (returns data URL) |
| `(render-view :perspective)` | Render a 3/4 perspective view |
| `(render-view [1 1 0.5])` | Render from a custom direction vector |
| `(render-all-views)` | Render all 6 ortho + 1 perspective views (returns map) |

Available orthographic views: `:front`, `:back`, `:left`, `:right`, `:top`, `:bottom`.

Options (keyword args):
- `:width`, `:height`: image dimensions (default 512x512).
- `:target`: keyword name of a specific object, or nil for all visible.

```clojure
(render-view :top :target :my-object :width 1024 :height 1024)
```

**Cross-section rendering:**

| Function | Description |
|----------|-------------|
| `(render-slice target axis position)` | Render a 2D cross-section at an axis-aligned plane |

- `target`: keyword name or mesh reference.
- `axis`: `:x`, `:y`, or `:z`.
- `position`: float, position along axis.

Returns a data URL (PNG) of the 2D contour outlines.

```clojure
(register cup (revolve (shape (f 20) (th -90) (f 30) (th -90) (f 15))))
(render-slice :cup :z 15)        ; Cross-section at Z=15
(render-slice :cup :x 0)         ; Sagittal slice through center
```

**Saving images:**

| Function | Description |
|----------|-------------|
| `(save-views)` | Download all 7 views as a ZIP archive |
| `(save-image data-url "name.png")` | Download any data URL as a PNG file |

```clojure
(save-views :target :my-object :prefix "cup-views")
(save-image (render-slice :cup :z 15) "cup-z15.png")
```

### Animation export (GIF, desktop only)

Render the current procedural (`anim-proc!`) animation to an animated GIF.
Available only in the Ridley Desktop build.

```clojure
(anim-export-gif "filename.gif"
                 :fps 15
                 :duration 6
                 :width 720)
```

| Option       | Default                  | Description                                                      |
|--------------|--------------------------|------------------------------------------------------------------|
| `:fps`       | 15                       | Frames per second                                                |
| `:duration`  | animation's own duration | Capture length in seconds                                        |
| `:width`     | 720                      | Output width in pixels; height matches the viewport aspect ratio |
| `:anim`      | auto-pick                | Animation name when more than one procedural animation exists    |
| `:overwrite` | false                    | Replace an existing file at the target path                      |

Capture is off-realtime: the live render loop is suspended and frames are
generated as fast as the system can compute the procedural mesh. The file is
written to `~/Documents/Ridley/exports/<filename>` (parent dirs created
automatically). A progress overlay covers the viewport during capture and
encoding.

Errors:
- non-procedural animation (keyframe `anim!`) is not supported
- multiple procedural animations require `:anim <name>`
- file collision raises an error unless `:overwrite true` is passed

### Manual export

Generate downloadable Markdown manuals from the online manual content:

```clojure
(export-manual-en)               ; Download Manual_en.md
(export-manual-it)               ; Download Manuale_it.md
(export-manual)                  ; Download both
```

Text-only (no screenshots). For manuals with screenshots, use the Python script:

```bash
python3 scripts/export-manual.py --lang en          # With screenshots
python3 scripts/export-manual.py --no-images        # Text only
python3 scripts/export-manual.py --check            # Non-regression test
```

---

## 17. Variables, Functions, Math

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

### Math functions

Standard math functions and the constant `PI` are exposed at the top level (no import needed). They wrap the host JavaScript `Math.*` API.

**Trigonometric (radians):**

| Function | Signature | Description |
|----------|-----------|-------------|
| `sin` | `(sin x)` | Sine of angle in radians |
| `cos` | `(cos x)` | Cosine of angle in radians |
| `tan` | `(tan x)` | Tangent of angle in radians |
| `asin` | `(asin x)` | Inverse sine, returns radians in [-PI/2, PI/2] |
| `acos` | `(acos x)` | Inverse cosine, returns radians in [0, PI] |
| `atan` | `(atan x)` | Inverse tangent, returns radians in (-PI/2, PI/2) |
| `atan2` | `(atan2 y x)` | Two-argument arctangent, returns radians in (-PI, PI] |
| `to-radians` | `(to-radians deg)` | Convert degrees to radians |
| `to-degrees` | `(to-degrees rad)` | Convert radians to degrees |

**Exponentials and logarithms:**

| Function | Signature | Description |
|----------|-----------|-------------|
| `exp` | `(exp x)` | e^x |
| `math-log` | `(math-log x)` | Natural logarithm. Note: bare `log` is bound to a debug printer (browser console), not the math function. |
| `pow` | `(pow base exp)` | base^exp |
| `sqrt` | `(sqrt x)` | Square root |

**Rounding:**

| Function | Signature | Description |
|----------|-----------|-------------|
| `floor` | `(floor x)` | Largest integer not greater than x |
| `ceil` | `(ceil x)` | Smallest integer not less than x |
| `round` | `(round x)` | Nearest integer (ties round to +inf, JavaScript convention) |
| `abs` | `(abs x)` | Absolute value |

**Comparison and clamping:**

| Function | Signature | Description |
|----------|-----------|-------------|
| `min` | `(min x y ...)` | Smallest of the arguments (variadic) |
| `max` | `(max x y ...)` | Largest of the arguments (variadic) |

These wrap `Math.min`/`Math.max` from the host. Any non-numeric argument propagates `NaN`.

**Constants:**

| Name | Value | Description |
|------|-------|-------------|
| `PI` | 3.141592... | Ratio of circumference to diameter |

No `TWO-PI`, `HALF-PI`, or `E` constants are predefined. Build them with `(* 2 PI)`, `(/ PI 2)`, `(exp 1)` or define your own with `def`.

**Standard Clojure numerics:**

Because Ridley scripts run inside SCI with `clojure.core` enabled, the usual Clojure numeric primitives are also available without any binding declaration: `+`, `-`, `*`, `/`, `mod`, `quot`, `rem`, `inc`, `dec`, `zero?`, `pos?`, `neg?`, `even?`, `odd?`, `compare`, `==`, `<`, `<=`, `>`, `>=`, plus the full sequence library (`map`, `reduce`, `for`, `range`, etc.).

**No built-in helpers for `to-rad`, `to-deg`, `lerp`, `clamp`, `map-range`.** Define them yourself when needed:

```clojure
(defn to-rad [deg] (* deg (/ PI 180)))
(defn to-deg [rad] (* rad (/ 180 PI)))
(defn lerp [a b t] (+ a (* (- b a) t)))
(defn clamp [x lo hi] (max lo (min hi x)))
```

#### Radians vs degrees

Trigonometric functions (`sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`) operate in **radians**. Turtle commands (`th`, `tv`, `tr`, `arc-h`, `arc-v`, `rotate-shape`, etc.) take **degrees**. The two coexist in the same script, so when feeding a degree quantity into a trig function, convert explicitly:

```clojure
;; Sine of 60 degrees
(sin (* 60 (/ PI 180)))

;; Or define a helper once:
(defn to-rad [deg] (* deg (/ PI 180)))
(sin (to-rad 60))
```

Conversely, when a trig result needs to feed a turtle rotation:

```clojure
;; Heading in degrees from a 2D direction vector
(defn heading-deg [[dx dy]] (* (atan2 dy dx) (/ 180 PI)))
```

---

## 18. Not Yet Implemented

- Fillet/chamfer vertex blending on 3D mesh edges (edge fillet/chamfer works, vertex blending is experimental, see [FilletChamfer3D.md](FilletChamfer3D.md)).
- OBJ export (STL and 3MF export are available via `export`).
- Backward movement command `(b dist)`. Use `(f -dist)` instead.
