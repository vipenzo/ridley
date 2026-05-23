---
name: translate-shape
category: 2d-shapes
since: ""
status: stable
---

# translate-shape

## Signature

`(translate-shape shape dx dy)`

## Description

Shift a 2D shape by `[dx dy]`. Type-specific alias retained for
back-compatibility — the polymorphic `translate` dispatches here for
shapes. Holes are translated with the outer contour. Also accepts a
vector of shapes (each shape moves by the same offset). Does not modify
turtle state.

A common use is positioning shapes before `revolve` (which uses X as
radial distance from the rotation axis), or offsetting an input before a
boolean combination.

## Parameters

- `shape` — a 2D shape, or a vector of shapes.
- `dx`, `dy` — translation along X and Y.

## Example

{{example: translate-shape-basic}}

<!-- example-source: translate-shape-basic -->
```clojure
(def offset-circle (translate-shape (circle 10) 25 0))
(register peanut (extrude (shape-union (circle 10) offset-circle) (f 4)))
```
<!-- /example-source -->

Move a copy of a circle along X, then union with the original to make a
peanut outline.

## Notes

- For shapes that have `:centered? true`, translating means the centroid
  moves; the centered flag stays unchanged.
- For the polymorphic form that dispatches on type (mesh / SDF /
  shape), see [translate](translate.md). Calling
  `(translate a-shape …)` routes here automatically; the polymorphic
  card documents the mesh and SDF behavior.

## See also

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** `scale-shape`, `rotate-shape`,
  [translate](translate.md) (polymorphic mesh / SDF / shape)
