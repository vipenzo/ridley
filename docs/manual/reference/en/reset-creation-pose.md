---
name: reset-creation-pose
category: positioning-assembly
since: ""
status: stable
---

# reset-creation-pose

## Signature

`(reset-creation-pose thing)`
`(reset-creation-pose thing [x y z])`

## Description

Move a value's `:creation-pose` to a new origin without moving the
geometry. The pose's heading and up are unchanged. Returns a new
value.

Why this matters: every mesh and SDF carries a `:creation-pose` —
defaulted to the world origin at construction, advanced by
`translate` and `attach`, and shifted by `cp-*` inside attach.
Subsequent in-place transforms (`rotate`, `scale`) pivot at that
pose. After a boolean (`mesh-union`, `sdf-intersection`, etc.) the
result inherits the FIRST operand's pose, which may sit far from the
visual center of the new geometry. `reset-creation-pose` is the
escape hatch: re-anchor the pose at the result's centroid (for a
mesh) or bounding-box center (for an SDF), so a later in-place
rotation pivots where you would expect.

Two forms:

- `(reset-creation-pose thing)` — pose position becomes the mesh's
  centroid or the SDF's bounding-box center, whichever applies.
- `(reset-creation-pose thing [x y z])` — pose position becomes the
  explicit world point you supply.

Anchors store absolute world positions and are unaffected.

## Parameters

- `thing` — a mesh or an SDF.
- `[x y z]` (optional) — explicit world point for the pose. Without
  it, the default is the visual center.

## Example

{{example: reset-creation-pose-after-boolean}}

<!-- example-source: reset-creation-pose-after-boolean -->
```clojure
;; CSG result inherits the first operand's pose — re-anchor at centroid
(register cut (mesh-difference (box 40) (cyl 8 50)))
(register tilted
  (rotate (reset-creation-pose cut) :y 30))
(hide :cut)
```
<!-- /example-source -->

Without `reset-creation-pose`, the rotation would pivot at the
original box's creation origin. After re-anchoring, it pivots at the
CSG result's centroid — almost always the visually correct behavior.

## Variations

{{example: reset-creation-pose-explicit}}

<!-- example-source: reset-creation-pose-explicit -->
```clojure
;; Re-anchor at an explicit world point — e.g. for a known socket position
(register part
  (rotate (reset-creation-pose (box 20 8 8) [0 0 4]) :y 30))
```
<!-- /example-source -->

The explicit point form is useful when the pose must coincide with a
known feature (a socket, a mating face, a marked endpoint) rather
than with the visual center.

## Notes

- `reset-creation-pose` only moves the pose. The geometry's
  vertices and the anchors' world coordinates are unchanged.
- For meshes, the default centroid is the unweighted mean of
  vertex positions; for very asymmetric shapes that does not
  necessarily coincide with the bounding-box center. Pass an
  explicit point when you need exact control.
- This is the ONLY operation that decouples pose from geometry.
  Every other transform (`translate`, `rotate`, `scale`, `attach`)
  advances both together.

## See also

- **Guide:** placeholder → cap. 9 (Positioning & assembly)
- **Related:** `translate`, `rotate`, `scale`, `attach`,
  `bounds`, `mesh-union`, `mesh-difference`
