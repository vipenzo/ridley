---
name: scale-shape
category: 2d-shapes
since: ""
status: stable
---

# scale-shape

## Signature

`(scale-shape shape s)`
`(scale-shape shape sx sy)`

## Description

Scale a 2D shape around its centroid. Type-specific alias retained for
back-compatibility — the polymorphic `scale` dispatches to the same
behaviour when called on a shape. Does not modify turtle state.

With one factor the scale is uniform; with two factors the X and Y axes
are scaled independently. Also accepts a vector of shapes — each shape is
scaled around its own centroid.

## Parameters

- `shape` — a 2D shape, or a vector of shapes.
- `s` — uniform scale factor.
- `sx sy` — independent X / Y scale factors.

## Example

{{example: scale-shape-basic}}

<!-- example-source: scale-shape-basic -->
```clojure
(register stretched (extrude (scale-shape (circle 10) 2 1) (f 5)))
```
<!-- /example-source -->

A circle stretched to twice its width but unchanged in height — an
ellipse profile.

## Notes

- Scaling is around the centroid, not the origin. To scale around the
  origin, translate first or use `make-shape` directly.
- Holes scale with the outer contour.
- For the polymorphic form that dispatches on type (mesh / SDF /
  shape), see [scale](scale.md). Calling `(scale a-shape …)` routes
  here automatically; the polymorphic card documents the mesh and
  SDF behavior.

## See also

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** `rotate-shape`, `translate-shape`, `fit`, `resample-shape`,
  [scale](scale.md) (polymorphic mesh / SDF / shape)
