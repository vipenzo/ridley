# Ridley — Turtle Surface Constraints

## Spec for Claude Code

### Overview

Two complementary systems for constraining turtle movement to surfaces:

1. **Strict surface mode** (`:on-sphere`, `:on-cylinder`, etc.) — the turtle is locked to the surface. Movement follows geodesics. Commands that would leave the surface (`tv`, `tr`, lateral) are reinterpreted as surface-local operations. Precise, mathematically clean. Ideal for engravings, labels, surface patterns.

2. **Field mode** (`:attract`, `:contain`) — the turtle moves freely in 3D but is influenced by a force field. All commands work unmodified. The field bends the trajectory toward (or away from) a surface. Strength is a continuous parameter from 0 (no effect) to 1 (hard constraint). Ideal for organic forms, decorative shapes, exploration.

Both are turtle options, composable with each other and with `:preserve-up`:

```clojure
;; Strict: locked to sphere surface
(turtle :on-sphere 50 ...)

;; Field: attracted toward sphere, strength 0.5
(turtle :attract (sphere 30) 0.5 ...)

;; Field: contained inside a box, strength 0.8
(turtle :contain (box 50) 0.8 ...)

;; Combinations
(turtle :on-sphere 50 :preserve-up ...)
(turtle :attract (sphere 30) 0.5 :preserve-up ...)
```

---

# Part 1: Strict Surface Mode

## `:on-sphere`

### Syntax

```clojure
(turtle :on-sphere 50 & body)                         ;; radius, centered at origin
(turtle :on-sphere {:radius 50 :center [0 0 0]} & body)
(turtle :on-sphere {:radius 50 :lat 45 :lon 90} & body)  ;; starting position
```

### Initial Pose

Default: **+X pole**, heading toward **+Z**:

```clojure
;; position: [50 0 0]  (on surface)
;; heading:  [0 0 1]   (tangent, toward north pole)
;; up:       [1 0 0]   (outward normal)
```

Override with `:lat` / `:lon` (degrees):
- `:lat` — from equator, -90 to 90, positive toward +Z
- `:lon` — from +X axis, 0 to 360, positive toward +Y

### Movement: `(f dist)`

Follows a **great circle** (geodesic). Distance is arc length on the surface.

```
θ = d / R
P' = P·cos(θ) + H·R·sin(θ)
H' = -P·sin(θ)/R + H·cos(θ)
up' = normalize(P' - center)
```

Long movements are subdivided based on resolution setting for smooth pen traces.

### Rotation: `(th angle)`

Rotates heading within the tangent plane. Rotation axis = surface normal (outward):

```clojure
normal = normalize(position - center)
new-heading = rotate-vec(heading, normal, angle)
```

### `tv` and `tr`

**Reinterpreted**, not blocked. In on-sphere mode:

- `(tv angle)` is decomposed into the equivalent surface movement — a component of forward motion and heading change that produces the same "intent" (tilting the trajectory) but stays on-surface. Implementation: `tv` adjusts a pending curvature that modifies subsequent `f` steps, curving the path on the sphere.
- `(tr angle)` has no geometric meaning on a surface (there's only one "up" — the normal). It is stored but only affects extrusion shape orientation (the shape rotates around the heading axis).

### Lateral Movement

`u`, `d` are **reinterpreted** as movement along the surface normal:
- `(u dist)` — offset the extrusion outward from surface (useful for raised engravings)
- `(d dist)` — offset inward

`rt`, `lt` are reinterpreted as `th`-based movement: `(rt dist)` → turn 90°, forward dist, turn -90°. The turtle stays on-surface.

### Pen Traces

Movement generates line segments (chords between positions). Subdivided based on resolution for visual smoothness.

### Extrusion

Extrude works normally. At each ring:
- Position: on the surface
- Heading: tangent to surface (forward direction)
- Up: surface normal (outward)

Positive extrusion depth = away from surface (raised). Negative = into surface (engraved).

### Navigation

| Command | Behavior |
|---------|----------|
| `(mark :name)` | Saves position on surface + tangent heading |
| `(goto :name)` | Teleports to anchor, draws great circle if pen on |
| `(look-at :name)` | Rotates heading toward great circle direction to target |
| `(reset)` | Returns to default position on sphere |
| `(path ...)` | Records surface-constrained commands |
| `(arc-h r angle)` | Small circle on sphere (incremental f + th steps) |
| `(bezier-to ...)` | Approximated on surface via subdivision |

### Which Commands Work

| Command | On-sphere behavior |
|---------|-------------------|
| `(f dist)` | Great circle arc |
| `(th angle)` | Rotate in tangent plane |
| `(tv angle)` | Reinterpreted as surface curvature |
| `(tr angle)` | Stored for shape orientation only |
| `(u dist)` | Normal offset (for extrusion depth) |
| `(d dist)` | Normal offset (inward) |
| `(rt dist)` | Lateral movement on surface |
| `(lt dist)` | Lateral movement on surface |
| `(pen :on/:off)` | Normal |
| `(color ...)` | Normal |
| `(resolution ...)` | Affects arc subdivision |
| `(extrude ...)` | Shape perpendicular to surface |
| `(stamp shape)` | Shape on surface |

### Turtle State

```clojure
{:surface-mode :sphere
 :surface-params {:radius 50 :center [0 0 0]}
 ;; position, heading, up maintained as usual but constrained
 }
```

---

## Future Strict Surfaces

### `:on-cylinder`

```clojure
(turtle :on-cylinder {:radius 20 :height 100} & body)
(turtle :on-cylinder 20 & body)                        ;; infinite cylinder along Z
```

Cylinder is "unrollable" — isometric to a rectangle. Geodesics are helices (straight lines on unrolled surface). Starting position: +X surface point, heading along +Z.

### `:on-cone`

```clojure
(turtle :on-cone {:r1 30 :r2 10 :height 50} & body)
```

Also unrollable into a circular sector. Geodesics are straight lines on the unrolled sector.

### `:on-mesh`

```clojure
(turtle :on-mesh some-mesh & body)
```

Requires face adjacency data. Movement crosses face boundaries by finding the exit edge, locating the adjacent face, and re-projecting position and heading onto the new face plane. Same algorithm as sphere/cylinder conceptually, just with discrete planar faces instead of smooth curvature.

---

# Part 2: Field Mode

## Core Concept

The turtle moves freely in 3D. All commands (`f`, `th`, `tv`, `tr`, `u`, `rt`, etc.) work exactly as they normally do. But after each movement step, a force field nudges the turtle's position and re-orients its heading.

The key insight: the field acts **during path recording** too. So a path recorded under field influence is already deformed. `extrude` and `loft` then work on the deformed path as if it were any normal path — no special handling needed.

## Syntax

```clojure
;; Attract toward a surface
(turtle :attract surface strength & body)

;; Contain inside a volume
(turtle :contain surface strength & body)

;; Surface can be any primitive expression
(turtle :attract (sphere 30) 0.5 ...)
(turtle :attract (cyl 20 80) 0.3 ...)
(turtle :contain (box 50) 0.8 ...)

;; Strength: 0.0 = no effect, 1.0 = hard constraint
;; Intermediate values = proportional correction per step

;; Composable with other options
(turtle :attract (sphere 30) 0.5 :preserve-up ...)
(turtle :attract (sphere 30) (tweak :pull 0.1 0.0 1.0) ...)
```

## `:attract`

Pulls the turtle toward the nearest point on the target surface.

### Algorithm (per movement step)

After a normal movement produces position P':

```
1. Find nearest point on surface: S = nearest(surface, P')
2. Displacement vector: D = S - P'
3. Apply correction: P'' = P' + D * strength
4. Re-project heading onto tangent plane at P'' (remove normal component)
5. Normalize heading
6. Set up = surface normal at P'' (interpolated with world up based on strength)
```

With strength 0: P'' = P', no effect.
With strength 1: P'' = S, fully on surface (equivalent to projection).
With strength 0.5: P'' halfway between free position and surface.

### Heading Re-projection

The heading is only partially re-projected based on strength:

```
normal = surface-normal-at(P'')
heading-normal-component = dot(heading, normal) * normal
corrected-heading = heading - heading-normal-component * strength
new-heading = normalize(corrected-heading)
```

At strength 1, heading is fully tangent to the surface. At strength 0.5, heading is partially tangent — the turtle can "glance off" the surface.

### Up Vector

```
surface-up = surface-normal-at(P'')
new-up = normalize(lerp(current-up, surface-up, strength))
```

At strength 1, up = surface normal. At low strength, up is mostly unchanged.

## `:contain`

Keeps the turtle inside a volume. No effect when inside; pushes back when the turtle tries to leave.

### Algorithm (per movement step)

After a normal movement produces position P':

```
1. Check if P' is inside the volume
2. If inside: no correction (P'' = P')
3. If outside:
   a. Find nearest point on surface: S = nearest(surface, P')
   b. Displacement: D = S - P' (points inward)
   c. Apply correction: P'' = P' + D * strength
   d. Reflect heading component that points outward:
      normal = inward-normal-at-S
      if dot(heading, normal) < 0:
        heading += normal * (-2 * dot(heading, normal)) * strength
```

With strength 1: hard wall, turtle bounces off.
With strength 0.5: soft wall, turtle partially penetrates then curves back.
With strength 0: no containment.

### Containing shapes

The "surface" for containment is the *inside* of a volume:

```clojure
(turtle :contain (box 50) 0.8 ...)        ;; stay inside a 100x100x100 box
(turtle :contain (sphere 40) 0.6 ...)     ;; stay inside sphere radius 40
(turtle :contain (cyl 20 100) 0.9 ...)    ;; stay inside cylinder
```

## Target Surfaces

Both `:attract` and `:contain` accept primitive expressions. These are not meshes — they're mathematical definitions used only for distance/projection calculations:

| Surface | `nearest(surface, P)` | `normal-at(S)` |
|---------|----------------------|----------------|
| `(sphere R)` | `center + normalize(P - center) * R` | `normalize(P - center)` |
| `(cyl R H)` | Project onto cylinder wall (clamp height) | Radial outward (ignore Y) |
| `(cone R1 R2 H)` | Project onto cone surface | Perpendicular to surface |
| `(box W D H)` | Clamp to nearest face | Face normal |

The center of the surface is determined by the turtle's position when entering the scope — the field is placed *at the current turtle position*. This enables:

```clojure
(f 50)  ;; move to [50 0 0]
(turtle :attract (sphere 30) 0.5
  ;; sphere centered at [50 0 0]
  ...)
```

Or explicitly:

```clojure
(turtle :attract (sphere {:radius 30 :center [0 0 0]}) 0.5 ...)
```

## Turtle State

```clojure
{:field-mode :attract              ;; :attract, :contain, nil
 :field-surface {:type :sphere     ;; surface definition
                 :radius 30
                 :center [0 0 0]}
 :field-strength 0.5}              ;; 0.0 to 1.0
```

---

# Examples

## Strict Mode: Engraved Text on Sphere

```clojure
(register ball (sphere 50))

(turtle :on-sphere {:radius 50 :lat 0 :lon -60}
  (th 90)
  (def eq (path (f 200)))
  (register letters
    (text-on-path "RIDLEY" eq :size 12 :depth 2)))

(register engraved (mesh-difference ball letters))
```

## Strict Mode: Grid on Sphere

```clojure
(register globe (sphere 50))

;; Latitude lines
(doseq [lat (range -60 61 30)]
  (turtle :on-sphere {:radius 50 :lat lat}
    (th 90)
    (pen :on)
    (f (* 2 PI 50 (cos (/ (* lat PI) 180))))))

;; Longitude lines
(doseq [lon (range 0 360 30)]
  (turtle :on-sphere {:radius 50 :lon lon}
    (pen :on)
    (f (* PI 50))))                      ;; half circumference pole to pole
```

## Strict Mode: Spiral on Sphere

```clojure
(register planet (sphere 50))

(turtle :on-sphere 50
  (pen :on)
  (dotimes [_ 500]
    (f 1.5) (th 7.2)))                   ;; loxodrome
```

## Field Mode: Organic Spiral

```clojure
;; Same spiral code, different results at different strengths
(turtle :attract (sphere 30) (tweak :pull 0.3 0.0 1.0)
  (def spiral (path (dotimes [_ 200] (f 2) (th 5) (tv 3)))))

(register form (extrude (circle 1.5) spiral))
```

At strength 0.0: free spiral in space.
At strength 0.3: spiral that loosely wraps around the sphere.
At strength 0.7: spiral tightly hugging the surface.
At strength 1.0: spiral flattened onto the sphere.

## Field Mode: Contained Chaos

```clojure
;; Random walk contained in a box
(turtle :contain (box 40) 0.9
  (def walk (path
    (dotimes [_ 300]
      (f 3)
      (th (- (rand 60) 30))
      (tv (- (rand 60) 30))))))

(register sculpture (extrude (circle 1) walk))
```

The random walk bounces off the walls of the box, creating a tangled form that fills the volume.

## Field Mode: Attracted to Cylinder

```clojure
;; Vine wrapping around a column
(turtle :attract (cyl 15 80) 0.6
  (def vine (path
    (dotimes [_ 150]
      (f 2) (th 12) (tv 2)))))

(register column (cyl 14 80))
(register vine-mesh
  (extrude (circle 1.5) vine))
```

## Field Mode: Shell Between Two Spheres

```clojure
;; Contain between two concentric spheres (attract to outer, contain in outer)
;; The path stays in the "shell" zone
(turtle :attract (sphere 40) 0.3 :contain (sphere 50) 0.9
  (def shell-path (path
    (dotimes [_ 400]
      (f 2) (th 8) (tv 5)))))

(register shell (extrude (circle 1) shell-path))
```

## Combined: Surface Pattern + Field Deformation

```clojure
;; Draw a precise pattern on a sphere, then extrude with field influence
(turtle :on-sphere 50
  (def star-path (path
    (dotimes [_ 5]
      (f 30) (th 144)))))

;; Extrude the star path with some field attraction for organic feel
(register star
  (turtle :attract (sphere 50) 0.8
    (extrude (circle 2) star-path)))
```

---

# Implementation Plan

## Phase 1: Strict Sphere (`:on-sphere`)

1. Add `:surface-mode` and `:surface-params` to turtle state
2. Implement `sphere-forward`: great circle formula
3. Implement `sphere-th`: rotation around surface normal
4. Reinterpret `tv` as surface curvature modifier
5. Reinterpret `u`/`d` as normal offset, `rt`/`lt` as surface-lateral
6. Store `tr` for shape orientation
7. Subdivide pen traces based on resolution
8. Integrate with `path` recording
9. Integrate with `extrude` (ring orientation = surface tangent frame)
10. Test: lines on sphere, engraved text, extruded band

## Phase 2: Field Attract (`:attract`)

1. Add `:field-mode`, `:field-surface`, `:field-strength` to turtle state
2. Implement `nearest-point-on-surface` for sphere, cylinder, box, cone
3. Implement `surface-normal-at` for each surface type
4. Hook into movement pipeline: after each `f`, apply field correction
5. Apply field during `path` recording too
6. Heading and up re-projection with strength interpolation
7. Test: spiral attracted to sphere at various strengths, tweak integration

## Phase 3: Field Contain (`:contain`)

1. Implement inside/outside test for each surface type
2. Apply containment correction: only when outside, with heading reflection
3. Test: random walk contained in box, contained in sphere

## Phase 4: Additional Strict Surfaces

1. `:on-cylinder` — unrollable, geodesics = helices
2. `:on-cone` — unrollable into sector
3. `:on-mesh` — requires face adjacency, edge crossing

## Phase 5: Combinations

1. `:attract` + `:contain` in same scope (shell zones)
2. Strict + field (pattern on surface, then field-influenced extrude)
3. Multiple fields (future — attract to two surfaces simultaneously)

---

# Design Decisions

1. **Both systems coexist.** Strict mode for precision (engravings, labels), field mode for exploration (organic forms, decoration). They solve different problems.

2. **Field acts during path recording.** The deformed path is a normal path once recorded. Extrude/loft don't need any special handling. This is the key architectural insight.

3. **Strength is continuous 0-1.** This makes it tweakable. At 0 = normal turtle. At 1 = hard constraint (equivalent to projection for attract, hard wall for contain). Everything in between produces unique forms.

4. **Surface definitions are mathematical, not meshes.** `nearest-point-on-surface` and `surface-normal-at` are closed-form calculations. No mesh traversal, no performance concerns. `:on-mesh` is the exception, deferred to a later phase.

5. **Field center = turtle position at scope entry** (unless explicitly specified). This makes field placement intuitive: move to where you want the field, then open the scope.

6. **All commands work in field mode.** No restrictions, no reinterpretation. The field is a post-processing step on movement, transparent to the command layer.

7. **Strict mode reinterprets commands rather than blocking them.** `tv` on a sphere becomes surface curvature, `u`/`d` become normal offset, `rt`/`lt` become surface-lateral. This means existing code produces *something meaningful* even in strict mode, rather than erroring out.

8. **Distance is always arc length / world units.** `(f 10)` means 10 units of travel along the surface (strict mode) or 10 units of travel with field correction (field mode). Consistent with normal turtle behavior.
