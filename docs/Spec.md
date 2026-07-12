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

;; Local frame: with :local, the target and control points are read in the
;; turtle's local [right up heading] frame (origin = current turtle position)
;; instead of world coordinates, making the call pose-independent.
(bezier-to [x y z] [c1x c1y c1z] [c2x c2y c2z] :local)

;; Bezier to named anchor (uses both headings for smooth connection)
(bezier-to-anchor :name)
(bezier-to-anchor :name :steps 24)
(bezier-to-anchor :name :tension 0.5)   ; Control point distance (default 0.33, both handles)
(bezier-to-anchor :name :tension 0.5 :tension-end 0.2) ; asymmetric handles (directions stay locked to headings)
(bezier-to-anchor my-path :at :name)    ; resolve :name from a path inline (no with-path needed)
```

**Bezier along path.** Smooth bezier approximation of an existing turtle path, with C1 continuity at segment junctions:

```clojure
(bezier-as my-path)                          ; One cubic bezier per path segment
(bezier-as my-path :tension 0.5)             ; Control point distance factor (default 0.33)
(bezier-as my-path :steps 32)                ; Resolution per bezier segment
(bezier-as my-path :cubic true)              ; Catmull-Rom spline tangents
(bezier-as my-path :max-segment-length 20)   ; Subdivide long segments first
(bezier-as my-path :control true)            ; vertices = CONTROL points (see below)
```

**Control-polygon mode (`:control true`).** The default `bezier-as` interpolates — the curve passes *through* the path's vertices. With `:control true` the vertices become **off-curve control points** instead: the curve passes through each segment's *midpoint*, tangent to the polygon there, and is C1 (one quadratic per interior vertex; endpoints clamped, so the curve still starts at the first vertex and ends at the last). This is the dual of the default mode — it *rounds* the control polygon (like a quadratic B-spline / TrueType outline). Feeding a corner's two legs rounds the corner; the more vertices, the finer the control over the shape.

**Marks ride the smoothed curve.** A `(mark …)` in the path survives `bezier-as` (every variant — default, `:cubic`, `:control`) and lands **on the realized curve** with its **tangent** as heading, so the mark tracks the actual swept rail rather than the original polyline. In default mode a mark at a vertex stays exactly there (the vertex is on the curve); in `:control` mode an interior vertex is an off-curve control point, so its mark snaps to the nearest curve point (the apex of the piece bowing around it). Endpoint marks are exact in every mode (endpoints are clamped). To keep a mark *off* the rail by intent, drop it inside a `side-trip`.

Works both in direct turtle mode and inside `path` recordings.

### Pen control

| Function | Description |
|----------|-------------|
| `(pen :off)` | Stop drawing (pen up) |
| `(pen :on)` | Draw lines (default) |
| `(pen-up)` | Alias of `(pen :off)` — stop drawing |
| `(pen-down)` | Alias of `(pen :on)` — resume drawing |

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
(resolution :n 32)               ; Fixed number of segments (default 64)
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
| `:anchor-name` | Set pose from a named anchor on the current turtle (set by `with-path` or `mark`) |
| `[x y z]` | Set position (positional vector argument) |
| `{:pos [x y z]}` | Override position |
| `{:heading [x y z]}` | Override heading |
| `{:up [x y z]}` | Override up vector |
| `:pose <expr>` | Set pose from an explicit pose map expression |

Bare-keyword anchor shorthand: `(turtle :tip body…)` jumps to the anchor named `:tip` on the current turtle, then runs `body` in an isolated scope. Equivalent to `(turtle :pose (get-anchor :tip) body…)`. The control keywords `:reset`, `:preserve-up`, `:pose`, `:at` retain their special meaning and are not treated as anchor names.

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

### Planar paths (`path-2d`)

A plain `path` is a **3D rail** — a turtle in space, consumed in its own frame by `extrude`/`loft`. When you instead want a path as a **2D profile** (via `path-to-shape` → `extrude`/`revolve`), use `path-2d`. It records its trace in the same plane a shape stamps into — the turtle's `(right, up)` plane — so the profile is never "rotated" relative to how you drew it. The defining invariant:

```clojure
(follow-path P)  ≡  (stamp (path-to-shape P))      ; world points coincide
```

`path-2d` is pose-less like `path` (the consumer supplies absolute placement). A plane has only one in-plane turn and one strafe, and the turtle's native `right`/roll would leave the plane, so inside `path-2d` the commands collapse:

| You write | Means | Sign |
|-----------|-------|------|
| `th` = `tv` = `tr` | the single in-plane turn | `+` = left |
| `rt` = `u`         | strafe | `+` one way |
| `lt` = `down`      | strafe | `+` the other way |
| `arc-h` = `arc-v`  | the single in-plane arc | sign = direction |
| `f` / `b`          | forward / back | unchanged |

```clojure
(def L (path-2d (f 20) (th 90) (f 8) (th 90) (f 12) (th 90) (f 8)))
(register part (extrude (path-to-shape L) (f 5)))
```

The result is tagged `:species :2d`. Planar consumers (`path-to-shape`, `stroke-shape`, `bounds-2d`) normalize it through `ensure-path-2d`; **rail** consumers (`extrude`-along-path, `loft`) keep the 3D path, so `path-2d` is fully non-breaking — an ordinary `path` is `:3d` and behaves exactly as before.

### Marks

Marks record named poses within a path. They have no effect on geometry: they simply tag the turtle's position and orientation at that point.

```clojure
(def arm (path (f 30) (mark :elbow) (th 45) (f 20) (mark :hand)))
```

**Marks become mesh anchors automatically.** When a marked path is used to build a 2D *profile* — `path-to-shape`, `stroke-shape`, or `embroid` — and that profile is then `extrude`d / `loft`ed / `revolve`d, the marks are resolved in the profile's section plane and stamped onto the resulting mesh as `:anchors` (on the base section / θ=0 seam), keeping the mark's own heading (so a `side-trip`+`th` perpendicular mark stays perpendicular). They are then usable directly:

```clojure
(def rim (path (mark :foot-1) (f 30) (th 60) (mark :foot-2) (f 30) (th 60)
               (mark :foot-3) (f 30) (th 60) (mark :foot-4) (f 30) (th 60)
               (mark :foot-5) (f 30) (th 60) (mark :foot-6) (f 30)))
(register plate (extrude (path-to-shape rim) (f 4)))
(register foot (attach leg (move-to plate :at :foot-1 :align)))   ; one per corner
```

For `embroid` the anchor sits on the wall centerline, shifted by the wall `:offset`. To attach onto a wall **face** instead of the mid-thickness centerline, pass `:face :outer` / `:inner` — the anchor steps half the wall thickness onto that face, with `heading` = the outward face normal (opposite between the two faces). You do **not** rotate the mark in the path for this; just `(mark :name)` and pick the face:

```clojure
(register bracket (attach part (move-to wall :at :mount :face :outer :align)))
```

This is distinct from `with-path`, which resolves a path's marks as a *sweep rail* from the current turtle pose; here the path is the cross-section, so the marks land where the section is stamped on the mesh. The mesh also keeps `:section-anchors` (section-frame coordinates) and the swept `:rail-path` for re-evaluating a profile mark at any cross-section along the sweep.

Anchors are world poses, so they **survive boolean operations**: `mesh-union` / `mesh-difference` / `mesh-intersection` (and `hull`, `mesh-refine`, `mesh-smooth`) inherit the first operand's anchors along with its `:creation-pose` — the first operand's geometry is left in place, so its named features stay valid on the result. `(move-to (mesh-difference part hole) :at :foot-1)` works.

**Every operand's anchors survive too, index-tagged.** Beyond the first operand's bare names, `mesh-union` / `mesh-difference` / `mesh-intersection` also add an index-tagged copy of *every* operand's anchors: operand `i`'s `:foot` becomes `:i|foot` (e.g. `:0|foot`, `:1|foot`, …). These are additive — the bare names from operand 0 are still present, so existing `:at :foot` lookups keep working. Because the tags live in the keyword's *name* (`(name :2|foot)` → `"2|foot"`), select them all at once with a regex: `(on-anchors result #"\|foot" …)` hits every operand's `:foot`, while `(on-anchors result "0|" …)` isolates a single operand. Useful for radial assemblies — union N arms each carrying a `:tip`, then `(on-anchors arms #"\|tip" …)` to act on all N tips. (Note: for `mesh-difference` / `mesh-intersection` the tagged marks of the non-first operands point at geometry that was removed or clipped away — they remain valid world poses but may sit in empty space.)

**Composing a profile mark with the sweep (`:on`).** A profile mark gives *where in the cross-section*; the sweep rail gives *where along the extrusion*. `:on` combines them into one 3D pose. The rail location is either a rail `(mark …)` name or a **fraction** `t∈[0,1]` of the sweep (no rail marks needed — `t=0` is the base section, `t=1` the end; `:at … :on 0` ≡ `:at …`):

```clojure
(move-to plate :at :foot-1 :on :mid :align)   ; corner foot-1 at the rail's :mid mark
(move-to plate :at :foot-1 :on 0.5)            ; corner foot-1 at half the sweep
(move-to plate :on 0.5)                        ; sweep centerline at half (no profile mark)
```

Exact for `extrude` and uniform `loft`; under a scaling/twisting `loft` it uses the rail pose frame.

**Grid stamping (`on-anchors`).** An `on-anchors` clause whose pattern is a 2-vector `[rail-sel shape-pat]` stamps the body over the **product** of rail locations × matching profile marks. `rail-sel` is a fraction, a vector of fractions, or a pattern over rail marks; `shape-pat` matches the profile marks:

```clojure
(on-anchors plate
  [[0 0.5 1] "foot"] (cyl 2 5))   ; a peg at every foot × at 0, mid, end of the sweep
```

**Recovering the generative profile (`slice-mesh … :on`).** `(slice-mesh mesh :on t)` hands back the generative profile that was swept — **with its profile marks attached** — so re-extruding/lofting it reproduces a mesh carrying the same marks. `t` is a rail mark or fraction. For a morphing `loft` (`tapered`/`twisted`/…) it returns the cross-section **at t** (the actual scaled/rotated shape); for `extrude` the profile is constant. The marks track the morph automatically — for a `path-to-shape` profile each mark is stored as a reference to its **point index** (plus a heading offset off the local tangent), so when a shape-fn scales/rotates the points the mark rides along. (`stroke-shape`/`embroid` marks live on the centerline, not on the outline points, so they keep the simpler base resolution.) Plain `(slice-mesh mesh)` still returns the geometric cross-section cut at the turtle plane.

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

;; 2D contour measurement
(
  rimeter shape)              ; Length of outer closed contour (closing edge included)
(shape-perimeters shape)             ; [outer hole1 hole2 ...] per-contour lengths
(path-length path)                   ; Length of open path (3D, no closing edge)

;; Reverse / mirror (3D — full turtle frame carried; common on 2D profiles)
(reverse-path path)                  ; trace path's waypoints in reverse
(mirror-path path)                   ; reflect across plane whose normal = end heading (the true tangent)
(mirror-path path [nx ny])           ; reflect across plane with an explicit normal

;; Insert a mark at a fraction of the path's arc length (returns a NEW path)
(add-mark path :name 0.5)            ; mark at the arc-length midpoint of the spine

;; Extract portion of path by height
(subpath-y path from-h to-h)         ; Clip path vertically, output starts at Y=0

;; Shift path horizontally
(offset-x path dx)                   ; Move profile relative to revolve axis

;; Scale path or shape to fit target dimensions
(fit path :y 180)                    ; Scale Y extent to 180, keep X
(fit shape :x 200 :y 130)           ; Scale both axes independently

;; Type predicate
(path? x)                            ; true if x is a path map
```

**Completing a symmetric curve from one half.** Author half of a symmetric curve (start → midpoint `M`), then mirror it across the symmetry axis and reverse it so the two pieces join head-to-tail:

```clojure
(def half (path (bezier-to [36.06 8.94 0] [18.09 0 0] [29.34 2.21 0])))  ; O → M
(def full (path (follow-path half)
                (follow-path (reverse-path (mirror-path half)))))           ; O → M → E
```

`add-mark` returns a **new** path (the input is untouched) with a mark inserted at `fraction` (0..1) of the path's total spine length — it walks the top-level movement commands (`:f`/`:u`/`:rt`/`:lt`; side-trips and rotations are zero-length) and splits the straddling segment so the mark lands exactly there. Because it's just a path mark, it rides `extrude`/`loft`/`revolve` into the mesh as an `:anchor` — so a ruler to it measures the realized geometry, not a fixed construction point. E.g. to track a bezier's actual bow as you tweak its tension: `(register wall (extrude (stroke-shape (add-mark (path (bezier-to-anchor ps :at :end :tension 0.5)) :apex 0.5) 3) (f 10)))` then `(ruler :wall :at :start :wall :at :apex)`.

`mirror-path` reflects across the plane through the half's end point; its one-argument form uses the **end heading** as the plane normal (so the plane is the turtle's right/up plane there). A path ends facing the true tangent of its last segment — a `bezier-to` records the analytic end tangent, exactly like the turtle-level `bezier-to`, so `(f …)` after it continues tangent — which makes the default accurate without naming an axis. Pass a normal explicitly only to mirror across a different plane. Both work in 3D. See `examples/spigolo-quattro-modi.clj` for the same corner built four ways (`bezier-to` with computed handles, `bezier-to-anchor` with a tension, `edit-bezier`, and half + mirror).

---

## 4. 2D Shapes

### Built-in shapes

Shapes are 2D profiles used for extrusion:

```clojure
(circle radius)                  ; Circle, uses resolution setting
(circle radius segments)         ; Custom resolution

(rect r u)                       ; Rectangle centered at origin (r=right, u=up, in the section plane orthogonal to forward)

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

In normal use you do not set these flags directly: the built-in constructors pick the right default. Three cases where `:preserve-position? true` matters explicitly:

- **Letters from `text-on-path`** carry per-letter offsets that encode their position along the word. Re-anchoring each letter to the turtle would collapse them to a single point.
- **Shapes returned by `slice-mesh`** are in plane-local coordinates (origin = turtle, X = right, Y = up) and the points already encode their absolute position in that frame. Without the flag, the slice would visually drift relative to the source mesh when fed to `stamp`.
- **Image-traced outlines** via `(path-to-shape outline :preserve-position true)` / `(stroke-shape outline w :preserve-position true)` (opt-in, default off). The traced nodes are in board coordinates, and the flag keeps the profile's frame origin `[0 0]` — the turtle point you framed with `image-board` — as the extruded mesh's **creation pose**, instead of re-anchoring on the first traced vertex. The creation pose then lands on your chosen point even though it sits off the contour.

A fourth flag, `:align-to-heading?`, swaps the plane axes so 2D x maps to the turtle's heading direction (used internally by `text-on-path` to make letters progress along the curve). It is not normally set by user code.

### Predicates

```clojure
(shape? x)                          ; true if x is a 2D shape map
```

### Import

Load 2D outlines from external sources. Parsed contours become standard Ridley shapes, ready for `extrude`, `loft`, `revolve`, and shape booleans.

```clojure
;; Extract every geometric element from parsed SVG data as a vector of shapes
(svg-shapes svg-data)                                   ; Defaults
(svg-shapes svg-data :segments 64 :scale 1.0
                     :center true :flip-y true)         ; All options
```

| Option | Default | Description |
|--------|---------|-------------|
| `:segments` | 64 | Curve discretization (per arc / bezier element) |
| `:scale` | 1.0 | Uniform scale applied to imported coordinates |
| `:center` | `true` | Center each shape at its centroid |
| `:flip-y` | `true` | Invert Y to map SVG screen coordinates to Ridley's math frame |

`svg-data` is the parsed SVG map produced by `(svg "<svg>...</svg>")`. `svg-shapes` returns one Ridley shape per geometry element (path, rect, circle, polygon). For a single element by index, use `(svg-shape svg-data :index i ...)`.

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

(set-image shape path width        ; Attach a reference image to the shape
           offset-x offset-y)      ; (desktop only — see below)
```

**`set-image`** attaches a reference image to a shape, for tracing over it with paths/beziers. The image becomes visible **only when the shape is `stamp`ed**, UV-mapped onto the stamped polygon in the shape's own 2D frame — so it is **clipped to the shape's outline**:

```clojure
(stamp (set-image (rect 120 90) "/Users/me/ref/foto.png" 120 0 0))
```

- `path` — absolute file path to an image. **Desktop only**: the bytes are read through the Rust server (`/read-file`); on the web build nothing loads.
- `width` — the image width in the shape's **local 2D units**, so it shares the coordinate frame you trace in. This is how you calibrate scale: if a known feature should be *N* units long, pick `width` so it measures *N* (verify with the `ruler`).
- `offset-x`, `offset-y` — the image's **lower-left corner** in the shape's 2D coordinates. Note these are raw shape coordinates: a centred `(rect 200 100)` spans `[-100,100]×[-50,50]`, so its lower-left is at `(-100,-50)` — pass that to cover the whole rect.
- The image **height is derived from its aspect ratio** (no distortion).

Because the image rides on the **shape attribute**, it survives 2D booleans (`shape-union` / `shape-difference` / `shape-intersection` / `shape-xor`): the result keeps the first operand's `:image`, and since it is clipped to the outline, **only the fragment inside the resulting polygon is drawn**. E.g. intersecting an image-bearing rect with a small window shows just that window's slice of the image. (Note: `shape-offset` produces a new contour and **drops** the image.)

The image also **propagates through `extrude`** onto the resulting mesh's **base cap**, clipped to the profile (holes included) — a decal stored as indices into the mesh vertices, so it tracks any baked-in transform applied afterwards (`translate` / `rotate` / `scale` / `attach` / `on-anchors :align`). It even survives `concat-meshes` (and therefore `on-anchors`'s default `:concat`), which merges many imaged caps — each keeping its own photo — into one mesh. It does **not** survive Manifold CSG (`mesh-union` / `mesh-difference` / `mesh-intersection`), which rewrites all geometry, nor does it cover the side walls or the top cap. The image remains a viewport aid: it is never exported.

**`image-board`** is a convenience wrapper that builds a ready-to-trace board:

```clojure
(image-board path scale-factor [imx imy] [orx ory] [w h])
```

It puts the image on a **`preserve-position?` rectangle**, so the turtle stays at `[0 0]`: stamping the board places the rect relative to the turtle by `[orx ory]` and leaves the turtle on the point that will become the extruded mesh's **creation pose**. `scale-factor` is the image width in units; `[imx imy]` frames the photo **relative to the rect corner** (so moving `[orx ory]` carries the image along); `[orx ory]` is the rect corner relative to the turtle; `[w h]` is the crop rect. Trace it with `edit-path-2d`, then extrude through `(path-to-shape outline :preserve-position true)` (or `stroke-shape … :preserve-position true`) so the creation pose lands on the framed turtle point — generally *off* the contour:

```clojure
(stamp (image-board "/Users/me/ref/part.jpg" 200 [0 0] [-100 -50] [200 100]))
(register part (extrude (path-to-shape (edit-path-2d) :preserve-position true) (f 4)))
```

**`edit-image-board`** is the interactive editor for a board — drag handles to move / crop / pan the photo, calibrate `scale` by dragging a two-point ruler onto a feature of known length and pressing **set scale** (recompute is explicit, never on drag), with a white ✛ marking the creation pose. Open it from the definitions panel; on **OK** it rewrites `(edit-image-board …)` to `(image-board … )` with the calibrated values. Rename `image-board` → `edit-image-board` to re-edit. Desktop only.

`translate`, `scale`, `rotate` are **polymorphic** (mesh / SDF / 2D shape — see [Top-level transforms](#top-level-transforms)). The `*-shape` aliases continue to work; pick whichever form reads better in context.

**`rotate` on a 2D shape only accepts `:z` (or no axis at all).** Calling `(rotate shape :x α)` or `(rotate shape :y α)` throws — a 2D shape has no out-of-plane geometry to rotate. To position a shape obliquely in 3D space, set the turtle's heading before consuming the shape (e.g. `(tv 30) (extrude shape (f 20))`). For a Y-foreshortening effect, write `(scale shape 1 (cos angle))` explicitly — that's what the math reduces to, and it makes the lossy projection visible at the call site.

### Shape functions (shape-fn)

Shape functions are shapes that vary along the extrusion path. Instead of passing a separate transform function to `loft`, shape-fns carry the transformation logic inside the shape itself, enabling composable, reusable profiles.

A shape-fn is a function `(fn [t] -> shape)` with metadata `{:type :shape-fn}`. At each point along a loft path (or revolution step), `t` goes from 0 to 1. Shape-fns work with `loft` and `revolve`.

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
| `(rugged shape :amplitude a :frequency f :octaves n :gain g :seed s)` | Layered sinusoid displacement (fBm, angular ridges, varies along t) |
| `(fluted shape :flutes n :depth d)` | Longitudinal cos grooves |
| `(displaced shape (fn [p t] -> offset))` | Custom per-vertex radial displacement |
| `(morphed shape-a shape-b)` | Interpolate between two shapes (same point count) |
| `(noisy shape :amplitude a :scale s)` | Noise-based displacement (see options below) |
| `(woven shape :warp n :weft m)` | Interlocking over/under woven fabric pattern |
| `(heightmap shape hm :amplitude a)` | Displacement from a rasterized heightmap |
| `(profile shape path)` | Scale cross-section to match a path silhouette |
| `(shell shape :thickness n :style s)` | Hollow extrusion with wall pattern (`:solid` `:voronoi` `:lattice` `:checkerboard` `:pattern`) |
| `(shell shape :thickness n :fn f)` | Hollow extrusion with custom thickness function |
| `(woven-shell shape :thickness n ...)` | Shell with radial offset for true over/under weave |

**Composition** via `->` threading:

```clojure
;; Fluted column that tapers
(-> (circle 15 48) (fluted :flutes 20 :depth 1.5) (tapered :to 0.85))

;; Twisted rectangle that shrinks
(-> (rect 30 10) (twisted :angle 180) (tapered :to 0.3))
```

**Partial form (bare transform).** A profile-safe combinator called **without a leading shape** returns the bare transform `(fn [shape t] -> shape)` instead of a shape-fn — exactly the legacy transform `loft` already accepts. This reads far better than a hand-written lambda, and is the intended way to write a `loft+` step inside `transform->` (where the profile comes from the previous step):

```clojure
;; legacy loft with a partial transform (equivalent to the shape-fn form)
(loft (circle 20) (tapered :to 0.5) (f 30))    ;; == (loft (tapered (circle 20) :to 0.5) (f 30))

;; instead of a raw lambda
(loft+ (fn [s t] (scale-shape s (+ 1 (* t 0.3)))) (f 30))
(loft+ (tapered :to 1.3) (f 30))               ;; the same, readable
```

Available in partial form: `tapered`, `twisted`, `fluted`, `rugged`, `noisy`, `capped` (keeps its positional radius: `(capped 3)`), and `displaced` (its 1-arity `(displaced displace-fn)`). The partial value has no `:shape-fn` metadata (`(shape-fn? (tapered :to 0.5))` is `false`), so it routes through loft's legacy branch with no dispatch change. `capped`'s auto-fraction reads `*path-length*`, which the loft binds on the legacy path too.

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

**`heightmap` options:** `:amplitude` (1.0), `:center` (false; when true, shifts sample range to [-0.5, 0.5]), `:direction` (`:circumference` default — width wraps around — or `:height` — width runs along the path), `:fit` (`:auto` default / `:physical` / `:stretch`), `:scale` (1.0; physical mode), `:surface-width` / `:surface-height` (physical overrides; default = base-shape perimeter / loft length), `:tile-x` (1), `:tile-y` (1) (integer count or `:fill` to pack seamless copies), `:offset-x` (0), `:offset-y` (0). `u` runs around the cross-section, `v` along the path. In `:physical` mode the heightmap lands at its real-world size (e.g. `text-heightmap`); `:stretch` fills the whole surface (classic, for seamless patterns).

**Noise and heightmap functions** (available globally):

| Function | Description |
|----------|-------------|
| `(noise x y)` | 2D deterministic continuous noise, returns ~[-1, 1] |
| `(fbm x y)` | Fractal Brownian Motion (layered noise, 4 octaves default) |
| `(fbm x y octaves)` | fbm with custom octave count |
| `(fbm x y octaves lacunarity gain)` | fbm with full control |
| `(mesh-to-heightmap mesh :resolution n)` | Rasterize mesh z-values into a 2D grid |
| `(weave-heightmap :threads 4 :spacing 5 :radius 2 :resolution 128)` | Analytical weave heightmap generator |
| `(text-heightmap "Ridley" :size 5)` | Heightmap from text, sized in real units (`heightmap :fit :physical` keeps it ~`:size` tall, reads straight) |
| `(sample-heightmap hm u v)` | Sample heightmap with bilinear interpolation (auto-tiles) |
| `(heightmap-to-mesh hm)` | Convert heightmap to a flat XY mesh with Z from values |
| `(mesh-bounds mesh)` | 3D bounding box `{:min [x y z] :max [x y z] :center [cx cy cz] :size [sx sy sz]}` |

**`weave-heightmap` options:** `:threads` (4), `:spacing` (5), `:radius` (2), `:lift` (same as radius), `:resolution` (128), `:profile` (`:round` or `:flat`), `:thickness` (radius * 0.5, for `:flat` profile).

**`mesh-to-heightmap` options:** `:resolution` (128), `:bounds` `[x0 y0 x1 y1]`, `:offset-x`, `:offset-y`, `:length-x`, `:length-y` (custom sampling window), `:supersample` (1; anti-alias edges by rasterizing N× and box-downsampling), `:blur` (0; widen the edge ramp by a box blur of this radius in world units).

**`text-heightmap` options:** `:size` (5; physical relief height), `:resolution` (256, grid), `:supersample` (3; edge anti-aliasing), `:edge-softness` (0.02; edge-ramp width as a fraction of glyph height — **this, not the grid or `:curve-segments`, removes faceting/comb on a loft**: it widens the binary mask's edge to ≈ loft step so it reads as a smooth bevel; set 0 for crisp edges), `:curve-segments` (defaults to `max 16, resolution/8`; glyph-outline fidelity only), `:font` (`:roboto`), `:depth` (1, irrelevant after normalization). Width = text advance **including spaces**, height = glyph height; both real `:size` units, carried as `:phys-width`/`:phys-height` so the `heightmap` shape-fn can place it at true size (`:physical` fit).

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
(shell (circle 20 96) :thickness 3 :style :pattern :pattern (circle 24)   ; Tiled motif holes
       :cells 12 :rows 8)

;; Custom thickness function
(shell (circle 20 64) :thickness 3
  :fn (fn [a t] (max 0 (sin (+ (* a 8) (* t PI 6))))))
```

The thickness function `(fn [angle t] -> 0..1)` maps each point to a wall thickness:
- `1.0` = full wall thickness, `0.0` = no wall (opening).
- `angle` = angular position on profile (radians), `t` = path progress (0..1).
- Values below `:threshold` (default 0.05) snap to 0.

**`shell` options:** `:thickness` (2), `:style` (`:solid`), `:fn` (custom, overrides style), `:threshold` (0.05), `:invert?` (false; swap solid/empty, works with any style/`:fn`), `:cap-top`, `:cap-bottom`.

Wall is symmetric: outer ring displaced outward by `thickness/2`, inner ring displaced inward by `thickness/2`. Where thickness is 0, both rings coincide (opening).

**Style-specific options:**

| Style | Options |
|-------|---------|
| `:solid` | (none) |
| `:lattice` | `:openings` (8), `:rows` (12), `:shift` (0.5), `:softness` (0.6) |
| `:checkerboard` | `:cols` (8), `:rows` (8) |
| `:voronoi` | `:cells` (6), `:rows` (6), `:seed` (42), `:wall-width` (0.3), `:margin` (0.05), `:softness` (0.6) |
| `:pattern` | `:pattern` (motif shape, ≥3 pts), `:cells` (8), `:rows` (6), `:grid` (`:square`/`:hex`), `:inset` (0), `:margin` (0.05), `:softness` (0.6) |

**Tiled motifs (`:pattern`).** The shell counterpart of `embroid`'s `:pattern`: tiles an arbitrary 2D motif shape around the wall **by arc-length**, in *cell units* — `:cells` motifs span the perimeter and `:rows` span the sweep, so any integer `:cells` wraps seamlessly at the seam. The motif is the **opening** by default (`:invert?` makes it the solid); `:grid :hex` offsets alternate rows by half a cell; `:inset` grows (>0) / shrinks (<0) the motif in cell units. Use a high-resolution motif (`(circle 24)`, not `(circle 6)`) for round holes — the motif's own point count sets how smooth the openings read.

**Opening edges (`:softness`).** A `:voronoi` or `:lattice` shell is a binary mask. By **default** (`:softness 0.6`) opening edges are cut with an **isocontour** build: a continuous field feeds a marching-triangles pass that slices each boundary triangle along the wall→opening iso-line at sub-grid positions, so openings read smooth — a low-poly curve *following* the boundary, not a grid staircase — with a graceful tapered lip from the variable wall thickness. `~0.4–0.8` works well; the result stays watertight/manifold. This is the shell analogue of `text-heightmap`'s `:edge-softness`. Pass `:softness 0` for the original hard binary cut (whole grid triangles kept/dropped, staircased edges); optionally follow with `(mesh-smooth m :sharp-angle 90 :refine 2)` for crisp walls with rounded corners. **Exception:** `:lattice` with `:invert?` always uses the hard cut (its band-boundary plateau doesn't close manifold under the isocontour build when inverted); `:voronoi` is fine inverted.

```clojure
;; Default :softness already gives smooth openings
(register lamp
  (loft-n 128 (shell (circle 20 128) :thickness 2 :style :voronoi
                     :cells 8 :rows 6)
    (f 50)))
```

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
| `:grid` | `:spacing` `[sx sy]` ([5 5]), `:hole` (1.5), `:hole-segments` (16), `:inset` (0) |
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

### Perforated wall (embroid)

`embroid` is the complement of `shell`: where `shell` hollows out a *solid* into a thin wall with openings, `embroid` perforates a wall that is *already a single surface* (a stroked path swept into a panel) — the case where `shell` does not apply because there is nothing to hollow out. Think of it as cutting a window pattern into "a portion of a shell".

Unlike the other shape-fns, **`embroid` takes the path that defines the wall's centerline (not a shape) plus the wall thickness**, and rebuilds the two faces of the wall by offsetting `±width/2` *perpendicular to the path at every point*. This is why it takes the path: index-pairing the two edges of a stroked outline breaks at miters/caps, and the perpendicular offset keeps the perforation running through the wall thickness regardless of how the path curves.

```clojure
;; Honeycomb-perforated curved wall
(register panel
  (loft (embroid my-path 3
                 :wall {:style :honeycomb :cells 8 :border 4})
        (f 45)))
```

**Signature:** `(embroid path width & {:keys [offset resolution wall] :as opts})`

**`embroid` options:**
- `:wall` — a map of the pattern options below (or pass them as top-level kwargs).
- `:offset [dx dy]` (`[0 0]`) — shift the wall in the profile plane (replaces a `translate` you would have applied to the stroked shape, e.g. to stack variants).
- `:start-cap` / `:end-cap` (`:flat`) — shape the wall's two free ends (the path endpoints), mirroring `stroke-shape`: `:flat` (square butt), `:round` (a half-cylinder of radius `width/2`), or `:square` (extend by `width/2`, then flat). The cap is kept solid (no perforation lands on it). May be passed top-level or inside `:wall`.
- `:cap-steps n` (`8`) — arc segments per `:round` cap.
- `:resolution n` (≈ `2·path-length`) — samples **along the path** (`u`). Governs how crisp the opening edges look in the path direction; the loft step count only refines the **sweep** (`t`). Raise for smoother openings (mesh grows with `resolution × loft-steps`).

**Wall pattern (`:wall` / `:style`):**

| Style | Options |
|-------|---------|
| `:honeycomb` (default) | `:cells` (8, hexes across the wall), `:wall-width` (0.3, strut width in cell units) |
| `:voronoi` | `:cells` (8), `:rows` (12), `:seed` (42), `:wall-width` (0.3) |
| `:pattern` | `:pattern` (a 2D shape used as the opening motif), `:spacing` (15, number or `[sx sy]`), `:grid` (`:square` / `:hex`), `:inset` (0, shrink the motif to fatten struts), `:invert?` (false, swap motif↔gaps) |

Shared options: `:softness` (0.6; isocontour ramp — smooth openings vs `0` = hard staircased cut), `:margin` (0.05; **fraction** of each side kept solid) or `:border` (world-units frame thickness, **uniform** on all four sides — overrides `:margin`).

```clojure
;; Tile an arbitrary motif (round holes on a hex grid)
(loft (embroid p 3 :wall {:style :pattern :pattern (circle 4)
                          :spacing 12 :grid :hex :inset 0.5})
      (f 45))

;; Motif as the SOLID instead of the hole (tiles/bricks)
(loft (embroid p 3 :wall {:style :pattern :pattern (rect 10 5)
                          :spacing 12 :invert? true})
      (f 45))
```

The result is watertight and manifold (each opening is a through-hole rimmed between the two faces; the `:margin`/`:border` frame keeps the panel closed and attached to its neighbours), with faces oriented outward.

**Does not compose in thread with other shape-fns.** `embroid` takes a path (not a shape-fn) and, in the loft, stamps its own stored faces — so transforms applied after it (`(-> (embroid …) (tapered …))`) are silently ignored. Apply positioning with `:offset`, or `translate`/`turtle` on the resulting mesh.

**Curved sweep rails (shell & embroid).** Both `shell` and `embroid` ride the loft's dual-ring machinery, which is intended for straight or *smoothly* curved sweeps. A curved **profile** is fine (the `embroid` path may bend freely, and stays watertight). A curved **rail**, however — `arc-h`/`arc-v` — is recorded as a chain of *hard corners*: the loft splits it into many segments, so the openings staircase/facet and pick up faint radial seams along the curve (the mesh is still watertight/manifold — the seams are cosmetic). For a patterned wall that curves *as it is swept*, use a **bezier rail** (`bezier-to`, recorded smooth so the sweep is not split) instead of `arc-h`/`arc-v`, or raise the motif/`loft` resolution.

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

**Bridge between shapes (`shape-bridge`).** Connect N shapes with smooth bridges via offset → union → unoffset. Each shape is expanded outward by `:radius`, the expansions are unioned, and the result is contracted by the same radius — producing a single outline where nearby shapes are joined by rounded fillets.

```clojure
(shape-bridge a b :radius 5)                       ; Two shapes, default round joins
(shape-bridge a b c :radius 3 :join-type :round)   ; Variadic, explicit join type
```

| Option | Default | Description |
|--------|---------|-------------|
| `:radius` | 1 | Outward offset distance (controls bridge width and corner radius) |
| `:join-type` | `:round` | `:round`, `:square`, or `:miter` (matches `shape-offset` semantics) |

Useful for fairing disconnected blobs into one organic outline, or for blending feature shapes into a base contour before extrusion.

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
- Vectors of shapes are accepted by `shape-offset`, `extrude`, `loft`, `revolve`, and `stamp`.
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
(box r u f)                      ; Rectangular box: r=right, u=up, f=forward (heading)

(sphere radius)                  ; Sphere, uses resolution setting
(sphere radius segments rings)   ; Custom resolution

(cyl radius height)              ; Cylinder, height along forward (heading)
(cyl radius height segments)     ; Custom segments

(cone r1 r2 height)              ; Frustum, axis along forward (heading); r1 = near/start radius, r2 = far radius — matches loft: (cone r1 r2 h) ~= (loft (circle r1) (circle r2) (f h))
(cone r1 r2 height segments)     ; Use r2=0 for proper cone (apex at the far end)
```

**Orientation:** all primitives with an extension axis (`box`, `cyl`, `cone`) extend along the turtle's forward axis (heading). For `box`, the section is the rectangle in the right–up plane; for `cyl` and `cone`, the section is a circle in the same plane. This matches `extrude` (which extends a 2D section along a forward path), with `box` anchored at center and `extrude` anchored at the base of the path.

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

The same convention applies to `extrude-closed`, `loft`, and `revolve`.

**Joint modes.** Control corner geometry during extrusion:

```clojure
(joint-mode :tapered)   ; Default (beveled corners)
(joint-mode :flat)      ; Sharp corners
(joint-mode :round)     ; Smooth rounded corners
```

**Axis-aligned convenience.** When extruding a 2D path (a list of `[x y]` pairs) along a world axis — bypassing the turtle's heading entirely — two helpers cover the common cases:

```clojure
(extrude-z path distance)            ; Extrude a 2D path along world Z
(extrude-y path distance)            ; Extrude a 2D path along world Y
```

Both are thin wrappers over the lower-level `(extrude path axis distance)` form with a fixed axis vector (`[0 0 1]` / `[0 1 0]`). For sweeping a 2D shape along a turtle path, keep using the main `extrude` form above.

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

Default: step count follows `resolution` (64 at default). Returns mesh without side effects.

**Tight curves and self-intersection.** When the path's radius of curvature is comparable to the shape's radius (e.g. a `(th 120)` spike or a high-tension `bezier-as`), `loft` rings on the inner side of the bend overlap. The result may be a topologically valid mesh that still self-intersects in 3D, which `manifold?` will reject. The remedy is on the path side: smooth spikes with `bezier-as` or `arc-h`, choose a smaller shape, or split the loft into straight segments joined with `mesh-union`.

**Known limit: holed profiles along curves.** Shapes with holes (`shape-difference`) sweep two rings at different radii; on any non-trivial curve the inner ring self-intersects before the outer one and the loft produces a 3D self-intersecting mesh that `manifold?` rejects. Tweaking tension or shrinking the hole does not reliably fix this. For an annular tube along a curve, build the tube as `mesh-difference` of two solid lofts (outer shape minus inner shape) along the same path.

### Revolve

```clojure
(revolve shape)                  ; Full 360 deg revolution around turtle up axis
(revolve shape angle)            ; Partial revolution (degrees)
```

The axis of revolution is the turtle's **up** vector (the revolution axis passes through the turtle's current position). Use `(tv …)` to tilt the turtle's pose before revolving when a different axis is needed.

The profile shape is interpreted as:
- 2D X = radial distance from axis (perpendicular to up).
- 2D Y = position along axis (in the up direction).

At the starting angle (`θ = 0`), the profile is stamped so that shape-X maps to the turtle's right direction (`heading × up`) and shape-Y maps to up — identical to the `stamp` / `extrude` convention.

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
(revolve (morphed (rect 20 20) (circle 15 4)) 180) ; Morph during half-revolution
```

### Chaining (extrude+, revolve+, loft+, transform->)

Variants of `extrude`, `revolve` and `loft` that return `{:mesh :end-face}` (and `:start-face`) for chaining multi-segment geometry. The `:end-face` contains the shape and pose of the final face, which can be used as input for the next operation.

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

**loft+**

Chainable `loft`: same dispatch as `loft` (shape-fn, two-shape, or transform-fn),
returning `{:mesh :start-face :end-face}`. The `:end-face` `:shape` is the 2D
cross-section actually stamped on the loft's **last ring** — never a re-evaluation
of the shape-fn at nominal `t=1` — so a chained op continues from the real end
face with no crack at the seam (on corner + short-segment paths the last ring's
`t` can clamp below 1).

```clojure
;; shape-fn / two-shape / transform-fn — like loft, but returns a chaining map
(def taper (loft+ (tapered (circle 20) :to 0.5) (f 30)))
(:mesh taper)                      ; the mesh
(:end-face taper)                  ; {:shape <end section> :pose {:pos :heading :up}}

;; two-shape: the end section carries the resampled point count
(loft+ (rect 20 20) (circle 10) (f 40))
```

Not supported in chaining: `shell` / `embroid` profiles (the swept wall has no
single end cross-section) — `loft+` rejects them with an explanatory error.

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

`loft+` is available as a step too. Inside `transform->` the incoming shape is
injected as the loft's profile, so the step is written with just the transform
(a transform-fn or a target shape) and the movements:

```clojure
(register spout
  (transform-> (circle 20)
    (loft+ (tapered :to 0.6) (f 30))   ; tapered run (partial form — reads clean)
    (revolve+ 45 :pivot :left)         ; corner bend
    (extrude+ (f 20))))                ; straight tail
```

The loft+ transform can be a partial combinator like `(tapered :to 0.6)`, a `(fn [s t] …)`, or a target shape — see the partial-form note under Shape functions.

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
- Inside `transform->`, operations do NOT take a shape argument: it is passed automatically. For `loft+` the first argument is the transform-fn or target shape, not the profile.
- `:pivot` on `revolve+` determines which edge of the shape sits on the revolution axis.
- The standard `extrude`/`revolve`/`loft` remain unchanged (return just the mesh).

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

Also accepts an SDF node (auto-materialized via `sdf-ensure-mesh` first).

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

Simply merges vertices and faces. Not manifold-valid on its own, but useful for heightmap sampling, visualization, and as a fast way to combine the result of a `(for ...)` of `attach` calls into a single argument for a boolean operation. Manifold accepts the concatenated geometry as a tool, so you skip the cost of pairwise unions when the pieces are going to be subtracted (or unioned) wholesale anyway. Any operand may be an SDF node — it is auto-materialized before merging.

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

Direction selectors: `:top` `:bottom` `:up` `:down` `:left` `:right` `:all`. Note that `:top`/`:bottom` are oriented along the mesh's **heading** axis (not world up); use `:up`/`:down` for the up axis.

The `:angle` threshold is **inclusive**: `:angle 90` matches a box's 90° edges. Concave edges (interior creases, e.g. where two unioned solids fold inward) and vertices touched by more than 3 sharp edges are skipped automatically — both would produce self-intersecting cutters.

**Limitation on dense CSG junctions.** `chamfer` and `fillet` operate by subtracting per-edge prism cutters from the mesh. On meshes built by `mesh-union` (or other CSG composition) of primitives that meet at tight intersection contours, the cutters near the contour can clip surfaces of the other primitive, producing visible spurs or bites. The operations are reliable on:
- standalone primitives (boxes, cylinders, spheres);
- compositions where the chamfer `distance` is small relative to the local feature size at every junction.

For dense junctions, prefer chamfering the inputs **before** composing them, or reduce `distance`. The contour ring edges produced by CSG are technically convex sharp edges and will be selected by `:all`; filter them out via `:where` or `:angle` if needed.

**Lower-level chamfer primitives.** `chamfer-edges` is the value-level entry point used internally by `(chamfer ...)`: it returns a new mesh with sharp edges chamfered by CSG subtraction.

```clojure
(chamfer-edges mesh distance)                       ; Defaults: :angle 80
(chamfer-edges mesh distance :angle 60)             ; Lower dihedral threshold
(chamfer-edges mesh distance :where #(pos? (first %))) ; Only edges whose endpoints satisfy predicate
```

| Option | Default | Description |
|--------|---------|-------------|
| `:angle` | 80 | Minimum dihedral angle (degrees) for an edge to count as sharp |
| `:where` | `nil` | Predicate `(fn [[x y z]] -> bool)` applied to BOTH edge endpoints |

`chamfer-prisms` returns the intermediate vector of triangular prism meshes (one per sharp edge) without applying the boolean cut, so you can preview, filter, or transform them before subtracting:

```clojure
(chamfer-prisms mesh distance)                      ; => [prism1 prism2 ...] or nil
(chamfer-prisms mesh distance :angle 30 :where pred)
```

Useful for debugging which edges a chamfer would cut, or for asymmetric chamfers built by hand from a subset of the prisms.

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

**Explicit plane.** `slice-at-plane` is the lower-level form that takes a plane defined by an arbitrary point and normal — bypassing the turtle entirely. Useful when the slicing plane comes from a computation rather than the current turtle pose.

```clojure
(slice-at-plane mesh normal point)              ; Auto-computed right/up basis
(slice-at-plane mesh normal point right up)     ; Explicit local basis

;; Horizontal slice at Z=90
(slice-at-plane :cup [0 0 1] [0 0 90])

;; Vertical slice at X=0 with explicit basis (X = right, Y = up)
(slice-at-plane :cup [1 0 0] [0 0 0] [0 1 0] [0 0 1])
```

Returns the same vector of `:preserve-position? true` shapes as `slice-mesh`. Accepts both mesh values and registered names (keywords).

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

Any operand may be an SDF node — it is auto-materialized before hulling.

### Mesh split (plane cut)

Split a mesh with the plane defined by the turtle's current pose — point =
position, normal = heading — into two halves:

```clojure
(register block (extrude (rect 20 20) (f 20)))
(f 10)
(def halves (mesh-split (get-mesh :block)))   ; {:behind <mesh> :ahead <mesh>}
```

`mesh-split` returns `{:behind <mesh> :ahead <mesh>}`. `:behind` is the half
*behind* the heading — the side the turtle came from — and `:ahead` is the
opposite half. This is the same convention `sdf-half-space` uses (see
above): after `extrude` the turtle ends on the far face of the new solid
with the material behind it, so `mesh-split` at that pose puts the material
in `:behind`.

Either half may come back as an empty mesh (`:vertices []`, `:faces []`)
when the cut plane misses the mesh entirely, or only grazes it — that is a
normal result, not an error. Both halves inherit the source mesh's
creation-pose, material and anchors, the same single-source policy
`mesh-hull`/`solidify` already use.

**Composite form — cutting at every mark of a path.** `mesh-split` also
accepts a path, guillotine-style: one cut per `(mark …)`, right-nesting each
result's `:ahead` into the next cut:

```clojure
(register block (extrude (rect 20 20) (f 30)))
(def result
  (mesh-split (get-mesh :block)
              (path (f 10) (mark :cut-1) (f 10) (mark :cut-2))))
;; => {:behind piece-1 :ahead {:behind piece-2 :ahead remaining}}
```

`(mesh-split m path)` cuts at every mark in the path, in the order they
appear; `(mesh-split m path marks-vector)` cuts only at the listed marks, in
that vector's order. With a single mark the return is literally identical
to the primitive's own `{:behind :ahead}` — the composite is the same
function, just with more marks to walk. The path is resolved from the
turtle's *current* pose, the same resolver every other path consumer uses.
A mark whose plane misses the remaining produces an empty `:behind` at its
place in the chain, without error — the chain continues from `:ahead`
unchanged.

`(split-parts result)` flattens a composite result into its leaves, in
order — `[piece-1 piece-2 … remaining]` for the chain above. A bare mesh
(no cuts) returns `[mesh]`.

### Connected components

`(mesh-components mesh)` decomposes a mesh into its connected components —
the disjoint solids it is made of — via Manifold's topological
`Decompose()`. No boolean, no plane: exact and cheap (sub-millisecond even
on thousands of triangles). It returns a **vector of meshes in a
deterministic order**: decreasing volume, tie-broken by the lexicographic
(x,y,z) vertex-mean centroid. That order is a contract — you can
destructure it positionally (`(let [[a b] (mesh-components m)] …)`) and
trust `a`/`b` to name the same pieces every run, whatever the construction
order (both sort keys are order-independent). A single-component mesh
returns `[mesh]`; an empty mesh returns `[]`. Each component inherits the
source's creation-pose/material/anchors.

```clojure
;; two disjoint prongs merged into one mesh -> two components, no plane needed
(count (mesh-components two-prongs))   ; => 2
```

### Convexity test

`(convex? mesh)` tests whether a mesh is convex via the hull-ratio test:
`volume(mesh) / volume(mesh-hull(mesh)) >= 1 - epsilon`. A convex mesh
coincides with its own hull, so the ratio sits at ~1; any concavity pulls it
down without shrinking the hull.

```clojure
(convex? (box 10))              ; true  — epsilon defaults to 0.01
(convex? mesh 0.001)            ; stricter tolerance
```

The default epsilon (`0.01`) was calibrated by measuring real ratios: box,
tessellated sphere, hex prism and cylinder (fine or coarse tessellation)
all land at 0.99999999+ regardless of mesh resolution — a tessellated
convex shape's vertices lie on its own hull by construction — while a
box-with-cavity frame, an L-shaped prism and a torus land at 0.875 or
below. `0.01` sits with a wide margin on both sides.

An empty mesh (no faces) is convex by definition and returns `true`
immediately, without invoking Manifold.

`(finished? mesh)` is the per-component finiteness criterion used by
`edit-mesh-split`'s semaphore: `true` iff **every** connected component is
convex (`(every? convex? (mesh-components mesh))`). It is the right "done"
test for a convex decomposition — a piece whose parts are all convex is
finished even when the piece as a whole reads concave (a U with two convex
prongs: hull-ratio ~0.5, yet nothing needs cutting, only separating). The
convexity epsilon is unchanged (`finished?` is about *how many* concavities,
not *how strict* the threshold). An empty mesh and any single convex mesh are
finished.

### Mirror & symmetry

The plane is the turtle's pose, as everywhere in this family: point = position,
normal = heading.

`(mesh-mirror mesh)` reflects a mesh through that plane — Manifold's native
`.mirror`, winding-correct (a genuine reflection, positive volume), composing
`translate(−p) ∘ mirror ∘ translate(+p)` for a plane off the origin. Keep one
half of a symmetric object and rebuild the whole with
`(mesh-union half (mesh-mirror half))`.

`(mirror? mesh)` / `(mirror? mesh epsilon)` tests whether a mesh is
mirror-symmetric about that plane, via a two-step cascade: a free volumetric gate
(the two halves of the split have equal volume within tolerance) then a
symmetric-difference confirmation (reflect the near half through the plane;
`vol(union) − vol(intersection)` over a half's volume ≈ 0 for a true mirror). It
is deliberately volumetric, not a tessellation comparison — the two halves may
triangulate differently even on a perfectly symmetric object. An empty mesh is
symmetric (`true`). Cost is 77–148 ms — on-demand, never per-keystroke.

```clojure
(mirror? (box 10 20 30))     ; true about the centre plane
```

`(symmetry-planes mesh)` proposes *verified* symmetry planes as a vector of poses
`{:position :heading :up}` (heading = plane normal, directly usable with
`goto`/`mark`), ordered by quality (symmetric-difference ratio ascending). It is
a pure function of the mesh: **area-weighted** PCA on the face centroids (weighting
by triangle area is mandatory — raw-vertex PCA is defeated by uneven tessellation)
gives up to three candidate planes through the centroid, a degenerate-eigenvalue
case (a square/N-fold object) adds the bounding-box axes, and each candidate is
confirmed by the `mirror?` cascade — only the promoted are returned. An
asymmetric mesh returns `[]`. Cost ~250–450 ms — on-demand.

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

### Edge analysis

Inspect a mesh's sharp edges — useful for previewing where `chamfer` / `fillet` would act, or for building custom edge-driven operations.

```clojure
(find-sharp-edges mesh)                             ; Defaults: :angle 30
(find-sharp-edges mesh :angle 80)
(find-sharp-edges mesh :angle 80 :where #(> (first %) 0))
```

Returns a vector of edge maps: `{:edge [v0 v1] :positions [p0 p1] :angle <degrees> :midpoint [x y z] :normals [n1 n2]}`. Each entry describes one interior edge whose adjacent triangles meet at a dihedral angle steeper than `:angle`.

| Option | Default | Description |
|--------|---------|-------------|
| `:angle` | 30 | Minimum dihedral angle (degrees) to count as sharp |
| `:where` | `nil` | Predicate `(fn [[x y z]] -> bool)` applied to BOTH edge endpoints |

The output feeds directly into `chamfer-prisms` / `chamfer-edges` (see [3D Chamfer & Fillet](#3d-chamfer--fillet)).

### Orientation utilities

```clojure
(lay-flat mesh)                                     ; Lay mesh on the XY plane via its largest bottom face
(lay-flat mesh :top)                                ; Lay on a direction-aligned face (:top :bottom :left :right :up :down)
(lay-flat mesh :base-mark)                          ; Lay on a named anchor's plane
```

Rotates a mesh so a chosen face — its largest direction-aligned face, or the plane recorded at a named anchor — ends up flush with the world XY plane (Z down), then re-centers it at the origin. Useful before export to give a slicer a printable orientation.

When `target` is a direction keyword (`:top`, `:bottom`, `:up`, `:down`, `:left`, `:right`), `lay-flat` selects the largest face in that direction and lays it down. When it is any other keyword, it is resolved as a named anchor (mark) on the mesh and that anchor's plane is laid flat instead. Without an argument, `:bottom` is used.

### Import

```clojure
;; Reconstruct a mesh from base64-encoded vertex / face arrays
(decode-mesh vertices-b64 faces-b64)
```

`decode-mesh` decodes a packed Float32 vertex array and Uint32 face index array (both base64-encoded) into a Ridley mesh. This is the binding behind library-imported `.stl` parts: the generated code stores the geometry as two base64 strings and rebuilds the mesh at eval time. The creation-pose position is anchored to the bounding-box center so subsequent `mesh-translate` calls behave sensibly.

You normally do not call `decode-mesh` directly — it appears in the autogenerated code produced by the library importer — but it is bound in SCI so user code can construct mesh literals from packed binary payloads.

```clojure
;; Read an STL from disk by path (desktop only)
(import-stl "/path/to/model.stl")
(import-stl "/path/to/model.stl" :recenter true)
```

`import-stl` reads a binary or ASCII STL from a filesystem path and returns a mesh, welding duplicate vertices on load. It is **desktop only** (the read goes through the desktop file server; the web build throws). By default the mesh keeps the STL's own coordinates, with the creation-pose anchored at the bounding-box center; pass `:recenter true` to translate the geometry so its bounding-box center sits at the origin.

Unlike `decode-mesh`, `import-stl` does not embed the geometry in the script — it only references the path. This keeps a `.clj` model shareable even when the STL itself may not be redistributed (the recipient re-downloads it from the original source). For a fully self-contained model, import through the library panel, which emits a base64-inlined `decode-mesh` form instead.

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

;; Named anchor / profile mark (world-space, as placed by extrude/loft/revolve)
(distance :wall :at :center :wall :at :D)

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
(ruler [0 45] (mid ps 1))         ; 2D points accepted; mid of a path segment
(ruler :wall :at :start :wall :at :D) ; named anchors / profile marks (world-space)

(clear-rulers)                     ; remove all rulers
```

**Anchors as ruler endpoints.** `<mesh> :at <name>` resolves a named anchor on a
registered mesh in **world space** — including profile marks that `extrude`/`loft`/
`revolve` stamped from the source path. This is the placement-correct way to measure
on swept geometry: a bare path or 2D shape has no 3D placement, so resolve the anchor
from the *mesh* (where the section→world transform has already been applied), not from
the path. `<path> :at <name>` is also accepted but resolves marks in the path's own
frame (origin), not where any extrusion placed them.

Point specs accept 2D vectors too (`[x y]`, padded to `z=0`). Two helpers produce points to measure to:

```clojure
(mid [0 0] [10 4])                 ; midpoint of two points → [5 2 0]
(mid my-path 1)                    ; midpoint of segment 1 (the 2nd edge) of a path
(seg-mid my-path 1)                ; same, explicit
```

Handy with the control-polygon `bezier-as :control`, whose curve passes through the segment midpoints: e.g. tune a control polygon by iterating until `(distance [0 a] (mid poly 1))` reaches a target.

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

**Pivot conventions.** For mesh and SDF, both `scale` and `rotate` use **world axes** with the **creation-pose position** as pivot. The two types are intentionally symmetric, so swapping a mesh for an SDF (or vice versa) in a pipeline never changes how downstream transforms behave.

| Type | translate | scale around | rotate around |
|------|-----------|--------------|----------------|
| Mesh | world axes | creation-pose, world axes | creation-pose, world axes |
| SDF | world axes | creation-pose, world axes | creation-pose, world axes |
| 2D shape | shape's local frame | shape centroid, local axes | shape origin (0, 0), Z |

Every mesh and SDF carries a `:creation-pose` — defaulted to the world origin at construction, advanced by `translate` and `attach`, and shifted by `cp-*`. So `(rotate thing :y 30)` on a mesh or SDF at the origin rotates it in place; on an off-origin one, it pivots around the creation-pose, keeping the visual relationship with that anchor intact.

**Local-axis scaling.** Because `scale` follows world axes, scaling a rotated mesh along its *local* heading needs a dedicated tool. Inside an `attach` body, use `stretch-f` / `stretch-rt` / `stretch-u` to scale along the turtle's current heading / right / up direction — see [attach](#attach). At top level there is no turtle frame, so `scale` is world-only by design.

**`reset-creation-pose`.** After a boolean (`mesh-union`, `sdf-intersection`, etc.) the result inherits the first operand's creation-pose, which may sit far from the resulting geometry's visual center. To make a subsequent in-place `rotate` or `scale` pivot at the visual center instead, re-anchor the pose:

```clojure
(reset-creation-pose thing)             ; pose.position → centroid (mesh) /
                                        ; bbox center (SDF). heading/up untouched.
(reset-creation-pose thing [x y z])     ; pose.position → explicit world point
```

Anchors store absolute world positions and are unaffected by this operation. `reset-creation-pose` is the only escape hatch for "I want to rotate this thing around its visual middle, not where it was constructed."

**Arbitrary-axis rotation on SDF** is implemented internally as a ZYX Tait-Bryan decomposition into three cardinal-axis rotations. This is invisible to the caller (you just get the rotation you asked for), but worth knowing if you hit numerical edge cases near gimbal lock (pitch ≈ ±90° with non-zero yaw or roll).

**Type-specific aliases** are still bound (`mesh-translate`, `mesh-scale`, `translate-shape`, `scale-shape`, `rotate-shape`) for backward compatibility and for code that wants to declare type intent at the call site. They route to the same implementations as the polymorphic forms.

**Negative scale factors (mesh reflection).** `scale` accepts negative factors, which produce a reflection of the mesh along the corresponding axes. When the product of factors is negative (an odd number of negative factors — e.g. `(scale m -1 1 1)`, `(scale m 1 -1 1)`, `(scale m -1 -1 -1)`, or uniform `(scale m -1)`), face winding is automatically reversed so the mesh stays manifold and usable in subsequent boolean operations (`mesh-union`, `mesh-difference`, `mesh-intersection`). Scales whose product is positive (e.g. `(scale m -1 -1 1)` — a 180° rotation about Z) need no winding fix and continue to work as before. The same applies to `stretch-f` / `stretch-rt` / `stretch-u` inside attach when given a negative factor.

**`scale` inside attach is not available.** Writing `(scale …)` in the body of `attach` or `attach!` throws an error directing you to `stretch-f` / `stretch-rt` / `stretch-u`. The earlier "scale the currently-attached mesh" form has been removed; the body of attach is for turtle movements (which advance the geometry) and for `stretch-*` (which scales along the current turtle frame).

**Path-driven transform (`transform`).** Apply a recorded path's turtle commands to a mesh (or vector of meshes) as a rigid-body transformation, without going through `attach`:

```clojure
(transform mesh path)                ; Single mesh: walk path on a virtual turtle attached to it
(transform [m1 m2 m3] path)          ; Vector of meshes: group rigid transform (translate + rotate)
```

The path is replayed on a virtual turtle starting at the mesh's `:creation-pose`; the resulting position/heading/up are baked into the mesh's vertices. With a sequence of meshes, the same rigid transform is applied to all of them so their relative arrangement is preserved (useful for transporting a sub-assembly around a scene).

`transform` also accepts an SDF node (or a vector containing one) — it is auto-materialized to a mesh first. For an SDF you want to keep as SDF while moving it, use `attach` instead (below), which transforms the SDF tree directly and never meshes it.

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

SDF attach is **incremental**: the path is walked one command at a time, and each command transforms the SDF tree directly. Movement (`f`, `rt`, `u`, `lt`), rotation (`th`, `tv`, `tr`, `set-heading`), creation-pose shifts (`cp-f`, `cp-rt`, `cp-u`, `cp-th`, `cp-tv`, `cp-tr`), `mark`, and `move-to` (with or without `:align`) all work on SDFs the same way they do on meshes.

| Command | Effect on SDF |
|---------|---------------|
| `f`, `rt`, `u`, `lt` | Translate the SDF; advance the turtle |
| `th`, `tv`, `tr` | Rotate the SDF around the current turtle position by the corresponding axis (up / right / heading) |
| `stretch-f`, `stretch-rt`, `stretch-u` | Scale the SDF along the current turtle's heading / right / up axis, pivoted at the turtle position |
| `set-heading` | Replace turtle heading/up; SDF unchanged |
| `cp-f`, `cp-rt`, `cp-u` | Translate the SDF in the *opposite* direction (anchor stays, geometry slides) |
| `cp-th`, `cp-tv`, `cp-tr` | Rotate the SDF around the anchor by the *opposite* angle (anchor orientation stays, geometry rotates under it) |
| `mark :name` | Record the current turtle pose as a named anchor on the SDF |
| `move-to … [:align]` | Snap turtle to target creation-pose / centroid / anchor; with `:align` (opt-in, valid with creation-pose and `:at :anchor` forms), also rotate the SDF to match the target's frame. The mesh-only `:from`/`:mate` mating options are rejected with an explanatory error (a separate follow-up). |

The anchors recorded by `mark` survive through subsequent transforms and through SDF booleans (the second argument's anchors are merged in, first-wins on name collision). They also cross the SDF→mesh boundary: when an SDF is materialized, its anchors carry over to the resulting mesh.

Every SDF constructor stamps a default `:creation-pose` at the world origin (heading `+X`, up `+Z`). The pose translates with `f`/`rt`/`u` and rotates with `th`/`tv`/`tr` along with the geometry; only the `cp-*` family shifts the geometry independently (`cp-f`/`cp-rt`/`cp-u` slide it, `cp-th`/`cp-tv`/`cp-tr` rotate it around the anchor). Booleans (`sdf-union`, `sdf-intersection`, `sdf-difference`) keep the **first argument's** creation-pose on the result — pick the operand whose pose you want a subsequent in-place rotate/scale to pivot on.

One command is rejected with an explanatory error:

| Command | Reason |
|---------|--------|
| `inset` | Mesh-face-specific, no SDF analogue |

`(scale …)` is rejected inside *any* attach (mesh or SDF) — use `stretch-f` / `stretch-rt` / `stretch-u` instead, or apply `scale` to the result outside the attach.

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
| `(stretch-f factor)` / `(stretch-rt factor)` / `(stretch-u factor)` | Scale the attached mesh / SDF along the current turtle's heading / right / up direction, pivoted at the turtle position |

**Creation-pose shift (move the geometry under a stationary anchor so a chosen feature coincides with it):**

| Command | Description |
|---------|-------------|
| `(cp-f dist)` / `(cp-rt dist)` / `(cp-u dist)` | Re-anchor at the point `+dist` along heading / right / up: geometry slides by `-dist` along that axis, anchor position unchanged |
| `(cp-th α)` / `(cp-tv α)` / `(cp-tr α)` | Re-anchor with frame rotated `+α` around up / right / heading: geometry rotates by `-α` around the anchor, anchor orientation unchanged |

The `cp-*` commands re-pick which part of the mesh — or which orientation of it — coincides with its creation-pose. The position-shift variants (`cp-f`/`cp-rt`/`cp-u`) translate the geometry under a stationary anchor point so a chosen feature lines up with the anchor; the rotation variants (`cp-th`/`cp-tv`/`cp-tr`) rotate the geometry around the anchor while keeping the anchor's heading/up fixed in world.

The rotation variants matter when the anchor's orientation will be read by downstream operations — most notably `move-to` (which adopts the anchor's heading/up). Rotating the geometry with `cp-th` leaves the anchor pointing the way it did before, so a later `move-to` of *this* mesh into another assembly behaves as if no rotation had happened.

All `cp-*` commands chain in the original creation-pose frame: a `cp-tv` after a `cp-th` rotates around the *original* right axis, not the post-`cp-th` one. (This is the same convention as `cp-f`/`cp-rt`/`cp-u`, which always slide along the original heading/right/up.)

### move-to

Move to another object's position and adopt its orientation (inside `attach`/`attach!`):

```clojure
(move-to :name)              ; move to creation-pose; turtle adopts heading/up (mesh not rotated)
(move-to :name :align)       ; move to creation-pose AND rotate mesh to match its frame
(move-to :name :center)      ; move to centroid only, keep current heading
(move-to :name :at :anchor)  ; move to a named anchor (from attach-path); turtle adopts heading/up
(move-to :name :at :anchor :align)  ; move to anchor AND rotate mesh to match its frame
(move-to :name :at :socket :from :plug)        ; mate the mobile mesh's :plug anchor onto :socket (translate only)
(move-to :name :at :socket :from :plug :align) ; …and rotate :plug's frame onto :socket's frame
(move-to :name :at :socket :from :plug :mate)  ; …rotate onto :socket composed with th 180 (faces opposed, up kept)
```

`:name` is resolved in this order: (1) named anchor on the current turtle (set by `with-path` or top-level `mark`); (2) registered mesh in the registry. Anchor targets only support the default form and `:align` — `(move-to :anchor :center)` and `(move-to :anchor :at :sub)` throw, because a single anchor has no centroid and no sub-anchors.

After `(move-to :A)`, the turtle is at A's position with A's orientation — but only the turtle's frame is updated, not the mesh's. "Forward" means A's forward for subsequent commands, "up" means A's up. This makes relative positioning work correctly even if A has been rotated. To also rotate the attached mesh's vertices to match A's frame, opt in with `:align` (see below).

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

By default `move-to` only translates the mesh; the turtle's heading/up adopt the target's pose for subsequent ops, but the mesh's vertices keep their construction orientation. Add `:align` to also rotate the mesh so its current frame snaps onto the target frame. `:align` is opt-in and works with both the default (creation-pose) form and the `:at :anchor` form:

```clojure
(attach! :child (move-to :parent))                   ; translate to creation-pose only
(attach! :child (move-to :parent :align))            ; translate + rotate to creation-pose frame
(attach! :child (move-to :parent :at :slot))         ; translate to anchor only
(attach! :child (move-to :parent :at :slot :align))  ; translate + rotate to anchor frame
```

`:align` is **not** supported with `:center` — a centroid has no associated frame, so there is nothing to align to. `(move-to target :center :align)` throws an explicit error.

The rotation is computed in two steps:
1. rotate the mesh so its current heading aligns with the target's heading,
2. then rotate around the new heading so the mesh's up aligns with the target's up.

This is the natural primitive when the target's orientation is meaningful — e.g. a path mark whose heading was set by an `(th 180)` to flag a flipped slot, or a skeleton-driven assembly where each mark records "which way this part should face". The cerniera2_C example uses `:align` to snap symmetric brackets onto skeleton marks whose heading encodes the outward-facing side.

#### `:from` and `:mate` — mating a named anchor of the mobile mesh

By default the aligned frame on the **mobile** side is the turtle's current pose (which at the start of `attach` coincides with the mesh's creation-pose). `:from <anchor>` elects a *named anchor of the mobile mesh* as the mating frame instead — so both sides of the join are nameable, not just the target:

```clojure
(attach! :plug-part (move-to :socket-part :at :socket :from :plug :mate))
```

`:from` accepts either an anchor **keyword** (looked up in the mobile mesh's world-space anchors — kept in sync with prior attach-body commands) or an explicit **pose map** `{:position … :heading … :up …}` (`:pos` is also accepted for the position; this is the hook for future viewport code-generation that synthesizes a frame from a face centroid + normal without a mark).

- **`:from` alone** (no `:align`/`:mate`) is a **pure translation**: the mobile anchor's *position* moves onto the destination position, the mesh is not rotated, and the turtle ends on the destination with the destination frame.
- **`:from :align`** additionally rotates the mesh so the mobile anchor's *frame* snaps onto the destination frame (heading→heading, up→up).
- **`:from :mate`** rotates the mobile anchor's frame onto the destination frame **composed with `th 180`** — heading and right negated, **up conserved**. Physically this is a proper rotation about the vertical (up) axis that turns the part face-to-face: two `north` marks drawn on the mating faces stay concordant. `:mate` implies alignment (writing `:mate :align` is redundant but harmless). The alternative `tv 180` convention (up→−up, "the closing book") is deliberately **not** offered — it is the rarer mechanical mating and is anyway obtainable by rotating the mark inside the path.

After the operation the turtle adopts the **mobile anchor's** post-op world pose (position on the destination, the mated frame — decision consistent with "the turtle adopts the pose it moved onto"). This makes the chained refinement commands read from the mobile part's own frame:

```clojure
(attach! :plug (move-to :socket-part :at :socket :from :plug :mate) (f -0.15))  ; back off 0.15 along the plug's own normal → FDM clearance
(attach! :plug (move-to :socket-part :at :socket :from :plug :mate) (tr 30))    ; residual spin about the shared mating normal
```

There is no `:spin` option: residual rotation about the mating normal is expressed with a chained `(tr α)` (per-assembly) or with a `(tr)` baked into the mark definition (a property of the anchor) — the post-op pose sits on the mating axis, so `tr` rotates about exactly that normal.

`:from`/`:mate` compose with the `:at`, `:on`, and `:face` destination forms, and with the default (creation-pose) form. They are rejected — with an explanatory error — for: `:center` (a centroid has no frame); a pure anchor target (a single destination pose, not a mesh); SDF attach (mesh attach only, for now); and group attach `(attach [m1 m2 …])` (the mobile anchor is ambiguous across a group).

#### Path targets

`move-to` accepts a path object as `target`, but only with `:at :anchor`: a path has marks that resolve to anchors, but it has no creation-pose or centroid of its own. The non-`:at` forms — `(move-to path)`, `(move-to path :align)`, `(move-to path :center)` — throw an explicit error. Resolve the path's marks via `:at :mark-name`, or convert the path to a carrier mesh first.

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

**Pattern-dispatch over multiple anchors with `on-anchors`.** When a skeleton carries many marks with role-based names (`:end-post-0`, `:end-post-1`, `:mid-post-0`, …), the explicit `(for [m (filter … (keys (anchors skel)))] (attach … (move-to skel :at m :align)))` form becomes repetitive — one role per `for`/`filter` block, with the role's name written twice. `on-anchors` collapses the idiom into a single pass with role patterns:

```clojure
(on-anchors target
  pattern-1 [:align] body-1
  pattern-2 [:align] body-2
  …)
```

Each clause pairs a **pattern** with a **body**. For every anchor on `target`, clauses are tested in order and the first match wins (no fallthrough). The body runs in an implicit `(turtle :pose <pose> body)` scope, so it sees the turtle positioned at the anchor; subsequent turtle primitives (`f`, `th`, `attach`, `cyl`, …) operate in that scope.

Patterns:

| Pattern | Match                                         |
|---------|-----------------------------------------------|
| string  | prefix match on `(name anchor-name)`          |
| regex   | `re-find` on `(name anchor-name)`             |
| keyword | equality with the anchor's name               |
| set     | `contains?` of the anchor's name in the set   |

`:align` *(optional, per clause)* opts the body into full pose alignment (position + heading + up). Without it, only the position is set and the body inherits the parent turtle's heading/up — same default as `move-to`. `target` is either a path (its `(mark …)` recordings are walked from the world origin) or a mesh value with an `:anchors` map (set by `attach-path` or by registering inside `with-path`).

The result is `(concat-meshes …)` of all body values, with nested sequences flattened via `flatten-meshes`; non-mesh values are silently dropped. A console warning is emitted for any pattern that matched zero anchors, listing the available names. Anchors not matched by any pattern are silently skipped (filtering only a subset is normal).

**Match bindings.** Inside each (flat) clause body, three symbols are bound to the anchor that matched, so one parameterized regex clause can replace several near-identical ones: `anchor` (the matched anchor name, a keyword), `$` (the full match string — `(name anchor)` for non-regex patterns, the `re-find` match for a regex), and `$1`..`$9` (regex capture groups as strings, or `nil` when absent). Since the SCI context has no `js/parseInt`/`parse-long`, index a string-keyed map with `$1` rather than parsing it to a number. Example — four parts, one per radial arm, dispatched by the captured digit:

```clojure
(def arm-tags {"0" :red-small "1" :red-big "2" :green "3" :purple})
(on-anchors arms
  #"(\d)\|here" :align
  (attach (mkmesh (arm-tags $1)) (f 10)))
```

These bindings are not available in grid-mode clauses (which have no single matched anchor).

**Path marks resolve at the current turtle pose**, like `with-path` does — *not* at the world origin (unlike `(anchors path)`, which always inspects from origin, and `(move-to path :at name)`, which uses absolute marks). This makes `on-anchors` composable: a function that builds its geometry through `on-anchors path` lands its pieces wherever the caller has positioned the turtle, so the same component can be re-distributed by an outer `on-anchors` over another skeleton. Mesh targets are unaffected — a mesh's `:anchors` are stored in world coordinates and used as-is.

For ad-hoc per-anchor logic that does not fit the `on-anchors` dispatch shape, `(pin-path path)` exposes the same resolver as a plain function — it returns the `{anchor-name → pose}` map at the current turtle pose, with no scope and no side effects. Use it in a custom `for` over anchors when the per-anchor work is more dynamic than a fixed set of clauses (e.g. parameters keyed by anchor name, or post-processing applied after the loop).

```clojure
;; Two roles dispatched by prefix on the same skeleton.
(def row-skel
  (path (mark :end-post-0) (f 20)
        (mark :mid-post-0) (f 20)
        (mark :end-post-1)))

(register fence
  (on-anchors row-skel
    "end-post-" :align (attach end-post)
    "mid-post-" :align (attach mid-post)))

;; Set pattern + no-align: vertical legs on a horizontal skeleton.
(register stand
  (on-anchors plate-skel
    #{:foot-1 :foot-3} (attach long-foot)
    #{:foot-2 :foot-4} (attach short-foot)))
```

`on-anchors` is the structured form of the `for`/`filter` idiom; the explicit form remains valid when the loop logic itself is dynamic (e.g. the patterns are computed at runtime).

### stretch-f / stretch-rt / stretch-u (attach context)

Inside an `attach` / `attach!` body — and only there — these commands scale the attached mesh or SDF along the current turtle's local frame:

| Command | Axis | Pivot |
|---------|------|-------|
| `(stretch-f factor)` | turtle heading (forward) | turtle position |
| `(stretch-rt factor)` | turtle right (`heading × up`) | turtle position |
| `(stretch-u factor)` | turtle up | turtle position |

The pivot is the turtle's position at the moment of the call, which defaults to the creation-pose but advances through `f` / `rt` / `u` and rotates through `th` / `tv` / `tr` like any other turtle command. Negative factors are allowed and reverse winding (mesh) or reflect (SDF) along that axis.

```clojure
(register b (box 20))

;; Double the size along the box's local heading (forward axis), pivoted at the
;; creation-pose.
(attach! :b (stretch-f 2))

;; Rotate the turtle frame first, then stretch along the rotated heading.
;; Result: box stretched along the original Y direction.
(attach! :b (th 90) (stretch-f 2))

;; Move the turtle, then stretch: the pivot is the new position, so only
;; vertices on one side of it scale outward.
(attach! :b (f 10) (stretch-rt 2))
```

`stretch-*` is the local-axis counterpart to top-level `scale`. Outside `attach` / `attach!` they are not bound. At top level use `scale` (world axes, creation-pose pivot); for local-axis scaling, enter an attach body. Writing `(scale …)` inside attach throws an error pointing at `stretch-*`.

The `scale-shape` alias still works for 2D shape scaling outside attach.

### Lateral movement

Pure translation along the turtle's local axes. No heading/up change, no ring generation.

| Command | Description |
|---------|-------------|
| `(u dist)` | Move along up axis |
| `(d dist)` | Move opposite to up axis |
| `(down dist)` | Alias of `(d dist)` |
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

Both `mesh` and `volume` may be SDF nodes — each is auto-materialized before deforming. A materialized volume has no `:primitive`, so its deformation zone falls back to the AABB box case.

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
(sdf-sphere r)                  ; Sphere of radius r
(sdf-box size)                  ; Cube of given side
(sdf-box sx sy sz)              ; Box with sx along right, sy along up, sz along heading
(sdf-cyl r h)                   ; Cylinder of radius r, height h along the turtle's heading
(sdf-cone r1 r2 h)              ; Cone or frustum, r1 = near (-heading) radius, r2 = far (+heading) radius
(sdf-rounded-box sx sy sz r)    ; Box with rounded corners (true SDF)
(sdf-torus R r)                 ; Torus axis along the turtle's up. R = major, r = minor
```

Like mesh primitives, SDF primitives spawn at the current turtle pose: position, heading, and up are all baked into the resulting SDF. TPMS and periodic patterns (`sdf-gyroid`, `sdf-slats`, `sdf-bars`, `sdf-bar-cage`, `sdf-grid`, …) are the exception — they fill space and stay world-aligned.

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

Cardinal-axis rotations dispatch directly to libfive's `rotate_x/y/z`. Arbitrary-axis rotations decompose into a ZYX Tait-Bryan triple; the decomposition can lose one degree of freedom near gimbal lock (pitch ≈ ±90°), but the visible rotation remains consistent. SDFs and meshes rotate and scale around their `:creation-pose` (the local frame established at construction and advanced by `translate`, `attach`, and `cp-*` commands), so an off-origin SDF or mesh rotates in place — no manual sandwiching needed. Use `reset-creation-pose` to re-anchor at the visual center when the inherited pose drifts (typically after a boolean).

### Materialization

```clojure
(sdf->mesh node)                            ; Auto bounds + budgeted resolution (same path as automatic materialization)
(sdf->mesh node bounds resolution)          ; Explicit bounds and resolution
;; bounds: [[xmin xmax] [ymin ymax] [zmin zmax]]
;; resolution: voxels per unit
```

Materialization is normally automatic. Call `sdf->mesh` only when you need explicit control over bounds or resolution.

**Resolution**: a global meshing resolution governs auto-meshing of SDF nodes (default 15, "turtle-style" — same scale as `(resolution :n N)` for curves). Bump it with `(sdf-resolution! 60)` before `register` to get a finer mesh. Higher = finer but slower; total voxel count is also capped to keep meshes printable. When the tree contains thin features (`sdf-shell`, small `sdf-offset`), resolution is automatically boosted to guarantee at least 3 voxels across the thinnest part.

For full control, call `sdf->mesh` with explicit `bounds` and `resolution` (voxels per unit) — this bypasses the auto-resolution voxel budget entirely, so an unbounded SDF (`sdf-gyroid`, `sdf-half-space`) or an overly fine resolution can generate a mesh large enough to stall the geometry server. The 1-arg form has no such risk: it goes through the same budgeted auto-bounds/auto-resolution path as automatic materialization.

**Conditional materialization (`sdf-ensure-mesh`).** Coerce a value to a mesh: if it is already a mesh, return it unchanged; if it is an SDF node, materialize it. Useful inside polymorphic code that may receive either, and as the controlled-resolution form of auto-meshing:

```clojure
(sdf-ensure-mesh x)                       ; Auto bounds + auto resolution
(sdf-ensure-mesh sdf ref-mesh)            ; Bounds extended to cover ref-mesh, auto resolution
(sdf-ensure-mesh sdf 30)                  ; Auto bounds, resolution override (turtle-style units)
(sdf-ensure-mesh sdf ref-mesh 30)         ; Both: extend bounds AND override resolution
```

The 2-arg form dispatches on the second argument's type (number → resolution override; anything else → reference mesh). To pass an explicit resolution without a reference mesh, use the 3-arg form with `nil` (`(sdf-ensure-mesh sdf nil 30)`).

### Predicates

```clojure
(sdf-node? x)                             ; true if x is an SDF tree node (vs. a mesh)
```

Distinguishes lazy SDF descriptions from materialized meshes. Useful in polymorphic helpers that branch on representation.

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

;; Center the text on the turtle pose (default false = baseline at the pose).
;; :center true centers the ink bounding box on both axes, so the text acts
;; like a centered primitive (align to another piece, rotate about its center).
(text-shape "Hello" :size 30 :center true)

;; Individual character shapes (one shape per character, no composite handling)
(text-shapes "ABC" :size 20)            ; Returns vector of shapes

;; Single character shape
(char-shape "A" :roboto 20)             ; Returns shape for one character

;; Use a custom font registered in Settings → Fonts
(text-shape "Hello" :font :inter-bold)  ; Synchronous lookup by id
```

Fonts are looked up by keyword id from a registry populated at startup
(`:roboto`, `:roboto-mono`) and from the Settings → Fonts panel (custom
fonts persist across sessions on desktop). Passing an unregistered id
raises a deterministic error pointing at the panel — there is no async
"loading, please retry" path.

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

**Shortcut: extrude in one call.** `extrude-text` combines `text-shape` and `extrude` into a single call that emits the text mesh at the current turtle pose, flowing along the heading and extruding along up:

```clojure
(extrude-text "RIDLEY")                          ; Defaults: :size 10 :depth 5
(extrude-text "RIDLEY" :size 40 :depth 3)        ; Bigger glyphs, thinner extrusion
(extrude-text "RIDLEY" :font :roboto-mono)       ; Different registered font
```

| Option | Default | Description |
|--------|---------|-------------|
| `:size` | 10 | Font size in units |
| `:depth` | 5 | Extrusion depth along the turtle's up axis |
| `:font` | `:roboto` | Keyword id of a registered font (built-ins or custom from Settings → Fonts) |

Returns one mesh per character. Use `concat-meshes` or pass directly to a downstream boolean operation if you need a single combined mesh.

**Measuring text.** `text-width` returns the horizontal extent (in the same units as `:size`) a given string would occupy when rendered:

```clojure
(text-width "Hello" :roboto 20)                  ; => width in units at size 20
```

Useful for layout — centering text along a path, sizing a backing plate, computing tracking. `font` is a registered font id keyword (or `nil` for the default `:roboto`).

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

**Type predicate:**

```clojure
(panel? x)                       ; true if x is a panel map
```

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

**Modal sessions.** `tweak`, `edit-bezier`, and `pilot` open a *modal session*: only one runs at a time, and while one is open the **editor is read-only** to the user. This is deliberate — these tools rewrite their own source form on confirm (splicing literals / a `bezier-to-anchor` / an `attach` back over the marker), and a hand-edit meanwhile would invalidate that text replacement. Programmatic edits (the confirm-time rewrite) still go through. **Switching workspace closes the active session** (without re-evaluating) before swapping the buffer, so a session never outlives the document it was editing.

### Tweaking

The `tweak` macro provides interactive parameter exploration with real-time preview. It evaluates an expression, displays the result in the viewport, and creates sliders for numeric literals.

```clojure
;; Default (no filter): a slider for EVERY literal — same as :all
(tweak (extrude (circle 15) (f 30)))                       ; edits 15 and 30

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

### Edit Bezier

`edit-bezier` authors a cubic Bezier curve interactively in 3D, from the keyboard, instead of solving the cubic by hand for the control points. It is a stand-in for a `(bezier-to … :local)` call and is used **wherever `bezier-to` is** — top-level, or inside `(path …)` / `(attach …)` — from the **definitions panel** (Cmd+Enter):

```clojure
(edit-bezier)                                  ; opens the editor with a default curve
(edit-bezier :shape)                           ; planar editing for a 2D shape seed (alias :as-shape-seed)
(edit-bezier :wireframe)                        ; defer downstream re-eval to Insert
(edit-bezier [40 0 0] [13 10 0] [27 10 0])     ; re-open an existing curve
(follow-path (path (edit-bezier)))             ; as a path
(stroke-shape (path (edit-bezier :shape)) 3)   ; as a 2D profile seed
(edit-bezier ps :at :end)                      ; anchor form — edit tensions (2 DOF)
(edit-bezier ps :at :end :symmetric)           ; anchor form — single shared tension
```

**Anchor / tension form.** `(edit-bezier path :at :mark)` edits a curve whose endpoints and tangent directions are fixed by the path's marks (start = current pose, end = the named mark); only the control-point distances (tensions) are editable, with the handle directions locked to the headings. It is the visual way to author a `bezier-to-anchor`. The panel shows a **tension slider** (one when `:symmetric`, two — start / end — otherwise); drag it, or use the arrow keys (`↑↓`, `Shift` for a fine step), and the live extruded result reshapes (no ephemeral control polygon is drawn). Slider and keys stay in sync. `:symmetric` ties the two tensions into one shared value (the natural choice for symmetric corners; `Tab` switches handles only in the asymmetric case). On confirm the marker is rewritten to `(bezier-to-anchor path :at :mark :tension t)` (plus `:tension-end` when asymmetric), keeping `path` as the original expression.

While editing, `(edit-bezier …)` draws a valid default curve so downstream operations run; on confirm it is rewritten to the edited `(bezier-to … :local)`. The marker opens a modal session. The start point P0 is the turtle pose at the call site — it is never written to source, and is recomputed on each eval. Three movable points (the end point and the two control points) are shown in the viewport along with the control polygon and a live preview curve; the turtle indicator marks P0.

**Flags** (keyword args, any order):
- `:as-shape-seed` — author the curve as a 2D profile for `stroke-shape`. The emitted `bezier-to` lies in the `heading`/`right` frame (the path's own 2D trace plane, which `stroke-shape` / `path-to-2d-waypoints` read), but the on-screen overlay is drawn **where the `stroke-shape` of the path will end up** — the extruded cross-section (length along world Y, bow along world Z) — so the handles line up with the resulting wall. Without the flag the editor is fully 3D and the overlay shows the path itself.
- `:wireframe` — nudges update only the ephemeral path/handles; the downstream geometry (e.g. `stroke-shape`/`extrude`) is re-evaluated only on demand (`Insert`). Without it, the downstream re-evaluates live (debounced) on every nudge.

**Keys:**
- `Tab` — cycle the three movable points (end → ctrl1 → ctrl2).
- Arrows — move the selected point. 3D mode: `←`/`→` = *heading* (the curve's length), `↑`/`↓` = *left*, `Shift`+`↑`/`↓` = *up* (depth). `:as-shape-seed` mode (planar): `←`/`→` = *length*, `↑`/`↓` = *bow* (visual up/down of the cross-section); `Shift` is disabled.
- Type digits — set the step size (mm); `Backspace` edits the buffer.
- `Insert` — force a downstream re-evaluation (useful in `:wireframe` mode).
- `Enter` — confirm; `Esc` — cancel.

**On confirm**, the whole `(edit-bezier …)` marker is replaced by a complete call:

```clojure
(bezier-to [ex ey ez] [c1x c1y c1z] [c2x c2y c2z] :local)
```

The vectors are expressed in P0's local `[right up heading]` frame (see the `:local` flag under [Bezier](#bezier)), so the call is pose-independent and re-opening it via `(edit-bezier ex… c1… c2…)` reproduces the same curve. **Cancel** leaves the source unchanged (the marker stays, drawing its default curve).

### Edit Path

There are **two** path editors, dispatched by path species: `edit-path-2d` edits a planar **2D profile**, and `edit-path` edits a **3D rail** (see [Edit Path — 3D rail](#edit-path--3d-rail) below).

`edit-path-2d` is a **pen tool** for tracing a planar polyline over a reference image (see [`set-image`](#set-image)) and clipping the piece you need. It wraps a [`path-2d`](#planar-paths-path-2d) body and opens an interactive session from the **definitions panel** (Cmd+Enter). Its result is a `:2d` path, so it feeds `path-to-shape` / `stroke-shape` directly.

```clojure
;; Trace a region over a stamped board image, then clip it out:
(register cut
  (extrude (shape-intersection board
                               (path-to-shape (edit-path-2d)))   ; ← trace, then clip
           (f 4)))
```

Unlike `edit-bezier`, `edit-path-2d` is **not** a persistent primitive. On confirm it rewrites its `(edit-path-2d …)` marker to a `(path-2d …)`, so re-running the script does **not** re-enter editing. To edit an existing path again, **rename `path-2d` → `edit-path-2d`** — the editor normalizes the seed via `ensure-path-2d` and reads its nodes back (a leading `move-to` is honored, and baked `arc-v` / `bezier-to` curves are recovered as curve nodes, see below).

**Workflow.** Open `(edit-path-2d)` (empty → a small starting triangle), then:
- **Click a segment** to insert a point there (split it); **click elsewhere** to append a point at the end; **drag a node** to move it. Orbiting still works — only grabbing a node, handle or segment suppresses it for that drag. New nodes default to a **smooth bezier** whose handles start collinear with the chord — the segment looks straight and bakes as a clean line until you shape it, but is curvable without first pressing `c`.
- Press **`c`** to toggle the selected node's **incoming segment** between a straight line and a **cubic bezier**; it bakes to a compact `(bezier-to …)` (a bezier left collinear with the chord bakes back as a clean line). The handles are **directional**: the start handle stays tangent to how the path arrives at the start node (length only — it slides along that line, so curves join smoothly), and the end handle is free, setting the entry direction into the next node. The handles are **colour-coded**: the free end handle is **bright cyan**, the length-only start handle **muted teal**, a freed cusp handle **magenta**. Press **`x`** to toggle a node **smooth ↔ cusp** (a cusp frees its outgoing handle for a sharp corner; the first node of an open path is implicitly a cusp), and **`a`** to make the incoming segment a tangent **circular arc** (a rounded corner, baked as `arc-v`). **`Shift+A`** makes every segment a smooth bezier and clears all cusps (tangent-continuous everywhere); **`Shift+X`** is the inverse, turning every segment back into a straight line. These work the same way in the 3D `edit-path` rail editor.
- `Tab` cycles the selected node; **arrows** nudge it; type digits to set the step (mm).
- `Delete` removes the selected node.
- `Enter` confirms; `Esc` cancels.

`edit-path` does **not** need a reference image — clicks land on the turtle's working plane, so it works as a standalone polygon/region drawing tool. When a `set-image` board is present it makes a convenient tracing backdrop; while editing the image is **dimmed** and the overlay (red polyline, filled node dots) is drawn on top so it reads even over a light image. Measure/pick clicks are suppressed during the session.

**Marks/side-trips/orientation preserved.** Node positions and heading come from `f` / `th`(→`tv`) / `set-heading` and a leading `move-to`:
- Strafes and turns bake as `(tv …)(f …)`: in the `(right,up)` plane the turtle's `right` is the plane normal, so native `rt`/`lt` would leave the plane — a perpendicular strafe bakes as `(tv ±90)(f)(tv ∓90)` instead, keeping the trace planar.
- Per-node **orientation is kept** where it matters: at the last (exit) node and at marks the heading is preserved (so a trailing turn / anchor orientation survives); plain corners follow the geometry. Moving a plain corner re-derives its heading; moving a mark or the exit node keeps it.
- `mark` and `side-trip` **attach to their node**: those nodes render **green**, are **protected from deletion** (marks become mesh anchors — never lost), and are re-emitted on confirm.
- A non-leading `move-to` is **rejected with an error**.
- Re-opening a baked path recovers arcs (`arc-v`) and beziers (`bezier-to`) as curve nodes; out-of-plane `:3d` moves (`u`/`tr`) on a legacy path degrade to straight segments (a warning lists what was dropped).

Marks are editable directly (the panel's **mark** field, or `m` to quick-add and `Shift+m` to toggle the labels); a marked node stays green and delete-protected.

**On confirm**, the marker is rewritten to a `path-2d` anchored at the first node:

```clojure
(path-2d (move-to [a0 b0]) (tv a1) (f d1) (tv a2) (f d2) …)
```

The leading `(move-to [a0 b0])` makes [`path-to-shape`](#path-to-shape) (via `ensure-path-2d`) seed the trace from the **absolute** start point (no spurious `[0 0]` vertex), so the traced shape lands in the same 2D frame as the board it was drawn over and the `shape-intersection` clip aligns. It is emitted **only when the start isn't at the origin** — a path starting at the origin bakes as `(path-2d (f …) …)`. The start node is drawn as an orange **ring**, the exit node as a solid orange dot. **Cancel** leaves the source unchanged.

Nodes are edited in the **turtle's stamp plane** at the call site (x-axis = `right` = heading × up, y-axis = `up`) — the same 2D frame the board uses. With the default pose that is the **YZ world plane**, so horizontal arrows move along world Y, vertical arrows along Z, and world X is never touched (the path stays on the image plane). Clicks raycast onto scene meshes and are projected onto that plane.

Segments can be **straight**, **cubic bezier** (`c`), or tangent **circular arc** (`a`); both round-trip on re-edit. Like `tweak` / `edit-bezier` / `pilot` it is a modal session (one at a time, editor read-only while open).

#### Edit Path — 3D rail

`edit-path` (no `-2d`) edits a **3D rail** — a `path` consumed in its own frame by `extrude`-along-path and `loft`, *not* a flat profile. It wraps a plain `(path …)` body (species `:3d`); a `(path-2d …)` body opens `edit-path-2d` instead. Node 0 (the anchor) is pinned at the origin, and nodes are placed in a **selectable working plane** of the turtle frame, named by its normal — `f` (⊥heading = `(right,up)`, the default), `r` (⊥right = `(heading,up)`), `u` (⊥up = `(heading,right)`) — chosen with the panel radios or the `f`/`r`/`u` keys. Drag a node (Shift = axis-lock), nudge with arrows or the per-plane **len/angle** fields, curve a segment with `c` (free bezier) or `t` (both-ends-tangent raccordo), split with `Ins`/`i`, mark with `m`.

On confirm it bakes a `(path …)` of **relative** `set-heading`/`f` segments (rotation-minimizing, so the swept section stays twist-free) plus `(bezier-to … :local)` curves. Rename `path` → `edit-path` to re-edit. Because `extrude` of a **non-planar** rail can still pick up a section roll (holonomy), wrap a twisted result's rail in [`ensure-untwisted`](#orientation-utilities). See the `edit-path` reference card for the full key map.

### Edit Mesh Split

`edit-mesh-split` is an interactive **tree session** for decomposing a mesh into pieces. Both halves of every cut become pieces of a growing tree — you can go back to any piece and keep cutting it, or separate a piece into its connected components with no plane at all. The goal is a decomposition where *every* piece is finished (each connected component convex). **The turtle IS the cut plane** — `position` + `heading` define it; arrows move/rotate the live pose (the shared `edit-attach` gizmo drags it too), and a semi-transparent quad with a cone along `+heading` renders the plane.

```clojure
(register block (extrude (rect 20 20) (f 30)))
(edit-mesh-split (get-mesh :block))
```

**The current piece** is the one being cut — full, with its live `:behind`/`:ahead` split and the semaphore. Other open pieces recede to faint context (no convexity tint — the semaphore is the current piece's alone); finished pieces are grey wireframes. **The mouse only moves the plane**; navigate open pieces with **n**/**p** (or the panel ◀/▶ buttons), deterministic in tree order over the open pieces; **r** reveals all to re-orient. A panel line anchors position by the emission name: `piece <name> (2/5) · open · 1 component`.

**Live semaphore (per-component).** A piece is **finished** (green) iff every connected component is convex — so both a single convex solid and several convex solids in one mesh (a U cut at its base → two convex prongs) are finished. A multi-component finished piece needs *separating, not cutting*: a `N pieces` badge flags it. A piece with a genuinely concave component is **red**. The current cut's two live halves are tinted the same way (`:behind` solid, `:ahead` washed) and the status line quantifies both by volume percentage, e.g. `behind 42% (convex) — ahead 58% (2 pieces)`.

**Gestures.** `Enter` cuts the current piece (both halves join the tree); when *every* piece is finished it commits instead; when the plane can't cut here and work remains, it moves to the next open piece. `s` separates the current piece into its connected components (`mesh-components`, no plane). `n`/`p` (or panel ◀/▶) navigate the open pieces deterministically; `r` toggles reveal-all. `Backspace` undoes the last structural gesture (cut *or* separation) — chronological, any branch, freeing that piece's kept-alive Manifold. `Ctrl`/`Cmd`+`Enter` commits now (even with concave pieces open). `Esc` cancels, emitting nothing. Each open piece keeps its Manifold alive, so a keystroke re-split pays the split alone, not a fresh mesh→manifold conversion.

**Emission.** On close the marker is rewritten to a `let`-chain of self-contained linear `mesh-split` composites (one per branch, each with its own path and its own path-scoped marks) plus a `mesh-components` destructure per separation. The tree shape lives in which binding feeds which call; each cut's delta is the minimal canonical `(th …)(tv …)(tr …)(f …)(rt …)(u …)` from the session's entry pose:

```clojure
(let [{piece-1 :behind piece-2 :ahead}
      (mesh-split (get-mesh :block) (path (f 10) (mark :cut-1)) [:cut-1])
      [piece-3 piece-4] (mesh-components piece-2)]
  [piece-1 piece-3 piece-4])
```

Numbers are never snapped to a grid. `(edit-mesh-split m)` opens a fresh session; `(edit-mesh-split m path marks)` (or renaming one emitted `mesh-split` → `edit-mesh-split`) re-enters that single call — since every emitted call is a self-contained linear composite, re-entry rebuilds the tree from the evaluated composite with no special machinery, and the rest of the `let` is untouched.

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
(export my-sdf)                  ; Bare SDF node — auto-materialized first
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

**Direct download helpers (`save-mesh`, `save-stl`, `save-3mf`).** Lower-level entry points that take an already-resolved mesh value (not a registry name) and trigger the native save-file picker. `save-mesh` is the primary form; `save-stl` and `save-3mf` are convenience wrappers that pin the format.

```clojure
(save-mesh mesh)                          ; Picker, default "model.stl" suggestion
(save-mesh mesh "part.stl")               ; Custom suggested filename
(save-mesh mesh "part.3mf" :3mf)          ; Explicit format

(save-stl mesh)                           ; STL, default suggested name "model.stl"
(save-stl mesh "part.stl")                ; STL, custom suggested name

(save-3mf mesh)                           ; 3MF, default suggested name "model.3mf"
(save-3mf mesh "part.3mf")                ; 3MF, custom suggested name
```

`mesh` can be a single mesh or a vector of meshes (merged into one file for STL, kept as distinct objects for 3MF — see multi-material 3MF above). The format the picker actually writes follows the extension typed by the user: passing `.3mf` to `save-stl` still produces a 3MF file. Use `export` (the keyword-driven entry point above) when working from registered names; reach for `save-mesh` / `save-stl` / `save-3mf` when the mesh value is already in hand.

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
| `:anim`      | auto-pick                | Primary animation name; required only when more than one procedural animation is registered. Determines capture length. |
| `:overwrite` | false                    | Replace an existing file at the target path                      |

Capture is off-realtime: the live render loop is suspended and frames are
generated as fast as the system can compute the procedural mesh. The file is
written to `~/Documents/Ridley/exports/<filename>` (parent dirs created
automatically). A progress overlay covers the viewport during capture and
encoding.

**Multi-anim capture.** Every procedural animation in `:playing` state is
driven in lockstep at the same fractional t each frame, not just the named
one. This lets a single GIF show multiple independently-coloured meshes
animating together — e.g. a static support, a rotating ring, and a sliding
part each as its own registered target with its own colour and its own
`anim-proc!`. The named (or auto-picked) anim's duration governs total
length; others run on the same fractional timeline, so animations with
matching durations progress in lockstep and ones with different durations
finish proportionally.

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

### Vector math (3D)

Vector operators on `[x y z]` triples. All inputs are 3-element vectors; outputs are `[x y z]` for vector-returning operations, scalars for `vec3-dot`. Useful when computing positions, headings, or alignments outside the turtle (e.g. inside a `displaced` shape-fn or a custom warp).

| Function | Description |
|----------|-------------|
| `(vec3+ a b)` | Component-wise addition |
| `(vec3- a b)` | Component-wise subtraction |
| `(vec3* v s)` | Multiply vector by scalar |
| `(vec3-dot a b)` | Dot product (scalar) |
| `(vec3-cross a b)` | Cross product (vector perpendicular to `a` and `b`) |
| `(vec3-normalize v)` | Unit vector along `v`; zero vector returned unchanged |

```clojure
;; Midpoint of two points
(vec3* (vec3+ p1 p2) 0.5)

;; Direction from a to b
(vec3-normalize (vec3- b a))

;; Right-hand perpendicular in the XY plane (heading rotated 90deg)
(vec3-cross heading [0 0 1])
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

## 18. Internals

> **For users extending Ridley with their own code.** This section documents
> the low-level API surface that SCI exposes for those writing reusable part
> libraries, procedural code that drives the scene, or custom integrations.
> It is not part of ordinary Ridley scripting: if you are writing a Ridley
> program and ended up here by accident, the user-facing equivalent is
> almost certainly in one of the earlier sections.

### 18.1 Registry pattern

The registry is the scene's named-object store. Six entry points add or replace entries; they all dispatch on the kind of object being registered, and they all take a name keyword (`:my-thing`) as first argument. User code normally goes through the `register` macro instead; the bare functions are exposed for macro authors and for tooling that builds the scene programmatically.

| Function | Description |
|----------|-------------|
| `(register-mesh! name mesh)` | Add or replace a named mesh in the registry |
| `(register-path! name path)` | Register a named path (abstract object, no visibility) |
| `(register-shape! name shape)` | Register a named 2D shape (abstract) |
| `(register-value! name value)` | Register a plain value (panel-like, used by `register` for non-renderable maps) |
| `(register-panel! name panel)` | Register a 3D panel |
| `(add-mesh! mesh)` | Add an anonymous mesh to the scene (no name, no later lookup) — counterpart of `register-mesh!` for one-off geometry |

### 18.2 Registry introspection

Read-only counterpart of the registry mutators. Use these to query what is currently registered, by name or by visibility, without building a mesh.

| Function | Description |
|----------|-------------|
| `(get-mesh name)` | Look up a registered mesh by keyword |
| `(get-path name)` | Look up a registered path |
| `(get-shape name)` | Look up a registered 2D shape |
| `(get-panel name)` | Look up a registered panel |
| `(registered-names)` | Vector of all registered mesh names |
| `(path-names)` | Vector of registered path names |
| `(shape-names)` | Vector of registered shape names |
| `(visible-names)` | Vector of names of currently-visible meshes |
| `(visible-meshes)` | Vector of mesh maps that are currently visible |
| `(all-meshes-info)` | Diagnostic dump: per-mesh `{:name :visible :vertices :faces ...}` for everything in the registry |

### 18.3 Scene visibility

Programmatic control of which registered objects appear in the viewport, plus the turtle indicator and an explicit refresh hook. User-facing code uses `(show ...)`/`(hide ...)` from §13; the bang-suffixed forms here are the lower-level entry points that those wrap.

| Function | Description |
|----------|-------------|
| `(show-mesh! name)` | Make a registered mesh visible by name |
| `(hide-mesh! name)` | Hide a registered mesh by name |
| `(show-mesh-ref! mesh)` | Make a mesh visible by reference (must already be registered) |
| `(hide-mesh-ref! mesh)` | Hide a mesh by reference |
| `(show-all!)` | Show every registered mesh |
| `(hide-all!)` | Hide every registered mesh |
| `(show-only-registered!)` | Hide anonymous meshes; only named ones remain visible |
| `(show-panel! name)` | Show a registered panel |
| `(hide-panel! name)` | Hide a registered panel |
| `(show-turtle)` / `(show-turtle mesh-or-kw)` | Show the turtle indicator (optionally anchored to a mesh) |
| `(hide-turtle)` | Hide the turtle indicator |
| `(refresh-viewport!)` | Force a viewport rebuild — needed when script code mutates the registry outside the normal eval flow |

### 18.4 Picking & selection

The picking API exposes what is currently selected in the viewport (clicked, hovered, or set via `selected!`). Reads only — picking itself is driven by the GUI.

| Function | Description |
|----------|-------------|
| `(selected)` | Current selection record `{:mesh :face :name :origin :last-op ...}`, or nil |
| `(selected-face)` | Just the face descriptor of the current selection |
| `(selected-mesh)` | The mesh value of the current selection |
| `(selected-name)` | Registry name of the selection, or nil if anonymous |
| `(origin-of selection)` | World-space origin of the picked feature |
| `(last-op selection)` | Last operation recorded on the picked mesh (peek of its source history) |

For the full source history of the picked mesh, see `source-of` in [18.8 Source tracking & metaprogramming](#188-source-tracking--metaprogramming).

### 18.5 Interactive testing

Hooks behind the `tweak` macro and a couple of turtle-state introspection accessors used by tooling.

| Function | Description |
|----------|-------------|
| `(tweak-start! expr opts)` | Open the tweak panel for an arbitrary expression (lower-level than the `tweak` macro) |
| `(tweak-start-registered! name expr opts)` | Tweak entry point for a registered mesh (hides original, restores on cancel) |
| `(get-turtle-resolution)` | Current global resolution setting, as a map (matches `(resolution :n N)` semantics) |
| `(get-turtle-joint-mode)` | Current joint mode keyword (`:flat`, `:round`, `:tapered`) |

### 18.6 Animation API

Lower-level construction primitives behind the `anim!` / `anim-proc!` macros, plus runtime accessors for the camera state used by animation targets. Most user code stays at the macro level; these bindings exist for code that builds animations programmatically (e.g. parameterized sweep generators).

| Function | Description |
|----------|-------------|
| `(anim-make-cmd kind args)` | Build a single animation command map (the value the macros emit per turtle call) |
| `(anim-make-span weight easing cmds & opts)` | Build a span value (weight, easing, command list, callbacks) |
| `(anim-register! name spec)` | Register a timeline animation (entry point of `anim!`) |
| `(anim-proc-register! name spec)` | Register a procedural animation (entry point of `anim-proc!`) |
| `(anim-preprocess spec)` | Run the per-frame preprocessing pipeline on an animation spec (turtle commands → per-frame poses) |
| `(anim-clear-all!)` | Remove all registered animations |
| `(get-camera-pose)` | Snapshot of the viewport camera `{:position :target :up}` |
| `(get-orbit-target)` | Current OrbitControls pivot point (`[x y z]`) |

### 18.7 Collisions & pilot

Collision callbacks fire during animation playback when two registered meshes intersect; pilot mode is the interactive positioning loop documented under §13. Like the other modal sessions (§14), pilot makes the editor read-only while open and is closed when you switch workspace.

| Function | Description |
|----------|-------------|
| `(on-collide a b callback)` | Register a callback `(fn [evt])` for collisions between two named targets |
| `(off-collide a b)` | Remove the collision handler for a specific pair |
| `(reset-collide a b)` | Reset the collision state of a pair (clears "currently overlapping" flag) |
| `(list-collisions)` | Vector of `{:a :b :callback ...}` for currently registered handlers |
| `(clear-collisions)` | Remove every collision handler |
| `(pilot-request! mesh opts)` | Enter interactive pilot mode for a mesh (keyboard-driven positioning) |

### 18.8 Source tracking & metaprogramming

Every mesh that goes through `register` carries two metadata fields used by tooling: `:source-history` (a chronological log of the operations that produced it) and `:source-form` (the quoted form passed to `register`). These bindings read and write that metadata.

| Function | Description |
|----------|-------------|
| `(add-source mesh entry)` | Append an entry to a mesh's `:source-history` |
| `(source-of selection)` | Full source-history of the currently picked mesh (used by inspector tooling) |
| `(source-ref selection)` | Compact reference into the source-history (form + index) |
| `(get-source-form name)` | Quoted form that produced the registered mesh — what `tweak :name` re-evaluates |
| `(set-source-form! name form)` | Replace the stored quoted form (used by `register` to bind both mesh and form atomically) |

`source-of` is bound under picking (it takes a selection record); it overlaps in spirit with the picking accessors above and is the natural counterpart of `selected` for code that needs to know not just *what* is picked but *how it was built*.

### 18.9 Runtime settings

Accessibility settings, runtime environment introspection, and a hook for the "Run definitions" toolbar button. These are the bindings that scripts use to branch on platform (desktop vs. web) or to drive the audio-feedback flag from REPL code.

| Function | Description |
|----------|-------------|
| `(desktop?)` | `true` when running in the Tauri desktop build, `false` in the browser |
| `(env)` | Runtime environment keyword: `:desktop` or `:webapp` |
| `(audio-feedback?)` | Current state of the audio-feedback accessibility flag |
| `(set-audio-feedback! bool)` | Enable / disable audio feedback |
| `(run-definitions!)` | Trigger the toolbar's "Run definitions" action programmatically |

---

## 19. Not Yet Implemented

- Fillet/chamfer vertex blending on 3D mesh edges (edge fillet/chamfer works, vertex blending is experimental, see [FilletChamfer3D.md](FilletChamfer3D.md)).
- OBJ export (STL and 3MF export are available via `export`).
- Backward movement command `(b dist)`. Use `(f -dist)` instead.
