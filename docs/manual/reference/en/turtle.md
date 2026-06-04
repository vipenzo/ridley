---
name: turtle
category: turtle-movement
since: ""
status: stable
---

# turtle

## Signature

`(turtle & body)`
`(turtle :reset & body)`
`(turtle :preserve-up & body)`
`(turtle [x y z] & body)`
`(turtle pose-map & body)`

## Description

Macro. Open an **isolated turtle scope**. The child turtle inherits the
parent's full state (position, heading, up, pen, resolution, joint
mode, material, …) but operates on its own copy. Movements and
rotations inside the scope **do not affect the outer turtle**.

Lines and meshes created inside the scope are visible (the scene
accumulator is shared). The body returns the value of its last form.

Options control what the child turtle starts from and how it manages
orientation drift:

- **No options** — child copies the parent state verbatim.
- **`:reset`** — child starts at the origin with default heading
  (`+X`), default up (`+Z`), and default settings; the parent state is
  ignored.
- **`:preserve-up`** — enable preserve-up mode (see below).
- **`[x y z]`** — set the child's position (positional vector).
- **Map** — `{:pos … :heading … :up …}` overrides any of the three
  vectors.

## Parameters

- `body` — forms evaluated in the isolated scope.
- Optional first argument: `:reset`, `:preserve-up`, `[x y z]`, or a
  pose-override map.

## Example

{{example: turtle-basic}}

<!-- example-source: turtle-basic -->
```clojure
(f 20) (th 45)
(turtle
  (f 10)
  (register branch (box 5)))
;; outer turtle still at (f 20) (th 45) — branch is rendered at (f 20) (th 45) (f 10)
```
<!-- /example-source -->

Build a branching construction without disturbing the parent's pose.

## Variations

{{example: turtle-reset}}

<!-- example-source: turtle-reset -->
```clojure
(turtle :reset
  (register origin-marker (sphere 2)))
;; the sphere is placed at the world origin regardless of where the outer turtle is
```
<!-- /example-source -->

`:reset` ignores the parent: the child starts at world origin with
default settings.

{{example: turtle-pose-override}}

<!-- example-source: turtle-pose-override -->
```clojure
(turtle {:pos [10 0 5] :heading [0 1 0] :up [0 0 1]}
  (register tag (box 5)))
```
<!-- /example-source -->

Override individual components of the pose with a map.

{{example: turtle-position-vector}}

<!-- example-source: turtle-position-vector -->
```clojure
(turtle [0 0 30]
  (register hat (sphere 5)))
;; child starts at [0 0 30] with parent's heading/up
```
<!-- /example-source -->

A positional vector sets only the position; everything else is
inherited.

{{example: turtle-preserve-up}}

<!-- example-source: turtle-preserve-up -->
```clojure
(turtle :preserve-up
  (dotimes [_ 85] (f 3) (th 8.6) (tv 0.5)))
;; up stays close to world up (no implicit roll drift)
```
<!-- /example-source -->

`:preserve-up` cancels the roll drift that accumulates from many
alternating `th` and `tv` calls — useful for text on 3D curves,
helices, climbing handrails.

## Notes

- **Preserve-up.** `th` (yaw) and `tv` (pitch) are intrinsic rotations
  around the *current* up and right axes. Their composition causes the
  up vector to drift in 3D, producing visible implicit roll over many
  steps. `:preserve-up` captures the up at scope entry and reprojects
  up onto the plane perpendicular to the heading after every rotation;
  the heading itself is unchanged, so paths and positions behave
  exactly as before. `tr` (deliberate roll) still works normally inside
  preserve-up scopes.
- Nesting is supported — each `turtle` scope is isolated from its
  parents. `(turtle (turtle :reset …))` opens a fresh sub-scope with no
  context inheritance.
- `turtle` does not clear pen lines or registered meshes; both are
  scene-global concerns.

## See also

- **Related:** `reset`, `f`, `th`, `tv`, `tr`, `with-path`,
  `joint-mode`, `resolution`
