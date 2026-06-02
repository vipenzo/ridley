---
name: stretch-f
category: positioning-assembly
since: ""
status: stable
---

# stretch-f / stretch-rt / stretch-u

## Signature

`(stretch-f factor)`
`(stretch-rt factor)`
`(stretch-u factor)`

Body-only inside `attach` / `attach!`.

## Description

Scale the attached mesh or SDF along the **current turtle's local frame**, pivoted at the turtle's current position. The three commands are symmetric — they differ only in which local axis they scale along — and all three obey the same conventions: the pivot is wherever the turtle is at the moment of the call, and a negative factor reverses winding (mesh) or reflects (SDF) along that axis.

| Command           | Axis            | Pivot            |
|-------------------|-----------------|------------------|
| `(stretch-f n)`   | turtle heading  | turtle position  |
| `(stretch-rt n)`  | turtle right    | turtle position  |
| `(stretch-u n)`   | turtle up       | turtle position  |

`stretch-*` is the **local-axis counterpart** to top-level `scale`. Outside an `attach` / `attach!` body the symbols are unbound, and writing `(scale …)` *inside* an attach throws an instructive error that points to `stretch-*`. The design intent is clean separation: at top level, `scale` works in world axes from the creation-pose; inside attach, `stretch-*` works in the turtle's local axes from the turtle position.

Because the pivot tracks the turtle, the same `stretch-f 2` produces different results depending on what came before:

- Right after entering attach (turtle at creation-pose), it scales the whole mesh from the creation-pose along its construction heading.
- After `(th 90)`, it scales along the rotated heading — useful for "stretch along a local Y" patterns.
- After `(f 10)`, the pivot has advanced 10 units along heading; vertices on one side of the pivot scale outward, the other side scales inward toward it.

## Parameters

- `factor` — a non-zero number. `1.0` is identity, `2.0` doubles along the axis, `0.5` halves, `-1.0` reflects. Mesh face winding is automatically reversed for negative factors so the result stays manifold; SDF mirroring is similarly safe.

## Example

{{example: stretch-basic}}

<!-- example-source: stretch-basic -->
```clojure
(register b (box 20))

;; Double the box along its forward (heading) axis, pivoted at the creation-pose
(attach! :b (stretch-f 2))
```
<!-- /example-source -->

A 20-cube becomes a 40×20×20 brick along its local heading direction. Because the pivot is the box's creation-pose (origin), both ends move symmetrically outward.

## Variations

{{example: stretch-after-rotate}}

<!-- example-source: stretch-after-rotate -->
```clojure
;; Rotate the turtle frame first, then stretch along the rotated heading
(register b2 (box 20))
(attach! :b2 (th 90) (stretch-f 2))
```
<!-- /example-source -->

`(th 90)` rotates the turtle frame so that "forward" now points along the world Y axis. The subsequent `stretch-f 2` doubles the box along that direction — equivalent to stretching the original construction's Y axis.

{{example: stretch-after-move}}

<!-- example-source: stretch-after-move -->
```clojure
;; Move the turtle, then stretch: the pivot is the new position
(register b3 (box 20))
(attach! :b3 (f 10) (stretch-rt 2))
```
<!-- /example-source -->

`(f 10)` moves the pivot 10 units forward. Vertices on the +right side of the pivot stretch further away; vertices on the −right side stretch the other way. The result is no longer centred.

## Notes

- **Body-only.** `stretch-*` are bound only inside `attach` / `attach!` (and inside `path` for recording — replayed by `play-path`). Calling them at top level raises an error.
- **`scale` is rejected inside attach.** The top-level `scale` symbol throws when used inside an attach body and directs you to `stretch-*`. The error is intentional: world-axis scaling combined with mid-body turtle rotations would produce confusing results.
- **Negative factors reflect.** A factor `< 0` mirrors along the axis. For meshes, face winding is reversed so the result stays manifold and usable in subsequent booleans. For SDFs, the reflected solid behaves identically.
- **Pivot follows the turtle, not the geometry.** A `f` / `rt` / `u` between the body entry and the `stretch-*` shifts the pivot; a `th` / `tv` / `tr` rotates the axes that `stretch-*` will use. The three `stretch-*` always read the turtle pose at the moment of the call.
- **`scale-shape` still works.** For 2D-shape scaling outside attach, `scale-shape` remains the right tool. `stretch-*` is exclusively for the 3D mesh / SDF case inside an attach body.

## See also

- **Related:** `scale`, `attach`, `attach!`, `move-to`, `cp-position`,
  `cp-rotation`
