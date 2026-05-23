---
name: rotate-shape
category: 2d-shapes
since: ""
status: stable
---

# rotate-shape

## Signature

`(rotate-shape shape angle-deg)`
`(rotate-shape shape :z angle-deg)`

## Description

Rotate a 2D shape around its origin in the plane. The rotation axis is
always Z (out of the plane); the explicit `:z` keyword is accepted but
optional. Type-specific alias retained for back-compatibility — the
polymorphic `rotate` dispatches here for shapes. Does not modify turtle
state.

`:x` or `:y` axes are rejected on shapes — a 2D shape has no out-of-plane
geometry to rotate. To position a shape obliquely in 3D space, set the
turtle's heading before consuming the shape (e.g. `(tv 30) (extrude shape (f 20))`),
or apply a non-uniform `scale-shape` to fake the foreshortening.

## Parameters

- `shape` — a 2D shape.
- `angle-deg` — rotation angle in degrees, positive = CCW.
- `:z` — axis selector. Optional; the only accepted axis for shapes.

## Example

{{example: rotate-shape-basic}}

<!-- example-source: rotate-shape-basic -->
```clojure
(register tilted-bar (extrude (rotate-shape (rect 30 10) 30) (f 5)))
```
<!-- /example-source -->

Rotate a rectangle 30° in its own plane before extruding.

## Notes

- For 3D placement, set the turtle's pose with `th` / `tv` / `tr` before
  the extrude, rather than trying to rotate the shape itself.
- For the polymorphic form that dispatches on type (mesh / SDF /
  shape), see [rotate](rotate.md). Calling `(rotate a-shape …)` routes
  here automatically; the polymorphic card documents the mesh and
  SDF behavior.

## See also

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** `scale-shape`, `translate-shape`, `reverse-shape`,
  [rotate](rotate.md) (polymorphic mesh / SDF / shape)
