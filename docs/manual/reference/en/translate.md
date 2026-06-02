---
name: translate
category: positioning-assembly
since: ""
status: stable
---

# translate

## Signature

`(translate mesh dx dy dz)`
`(translate sdf  dx dy dz)`
`(translate shape dx dy)`

## Description

Move a value by a 3D (mesh / SDF) or 2D (shape) offset, in world
coordinates. Returns a new value; the original is unchanged.

`translate` is polymorphic. It dispatches on the first argument and
behaves the same way for meshes and SDFs (both consume three offsets,
both translate in world axes, both advance the value's
`:creation-pose` by the same amount). For 2D shapes the offset is 2D
and pivots in the shape's local frame.

| Type      | Axis convention                          | Pivot                  |
|-----------|------------------------------------------|------------------------|
| Mesh      | world axes                               | world frame            |
| SDF       | world axes                               | world frame            |
| 2D shape  | shape's local frame                      | shape's local frame    |

For local-axis translation along the heading / right / up of a
rotated mesh, see `attach` with `(f dist)` / `(rt dist)` / `(u dist)`
in the body — those follow the turtle's current frame, not the world
axes.

## Parameters

- First argument — a mesh, an SDF node, or a 2D shape.
- `dx`, `dy`, `dz` — world-axis offsets for mesh / SDF. For shapes,
  only `dx` and `dy`.

## Example

{{example: translate-mesh}}

<!-- example-source: translate-mesh -->
```clojure
(register cube (box 10))
(register moved (translate cube 20 0 5))
(hide :cube)
```
<!-- /example-source -->

A box is shifted 20 units along +X and 5 along +Z. Both the geometry
and the mesh's `:creation-pose` advance by `[20 0 5]`.

## Variations

{{example: translate-sdf}}

<!-- example-source: translate-sdf -->
```clojure
;; Same call shape on an SDF — the result is also an SDF
(register field
  (sdf-ensure-mesh
    (translate (sdf-sphere 8) 15 0 0)))
```
<!-- /example-source -->

`translate` returns an SDF when given an SDF, mesh when given a mesh.
The translation is applied to the SDF tree before materialization.

{{example: translate-shape-2d}}

<!-- example-source: translate-shape-2d -->
```clojure
(register tile
  (extrude (translate (rect 8 8) 4 0)
           (u 2)))
```
<!-- /example-source -->

On a 2D shape the offset is 2D; see [translate-shape](translate-shape.md)
for the dedicated shape-only card.

## Notes

- The polymorphic `translate` is the recommended form: code that
  swaps a mesh for an SDF (or vice versa) keeps working without
  edits. Type-specific aliases (`mesh-translate`, `translate-shape`)
  still exist for backward compatibility.
- `:creation-pose` advances by the same offset. Subsequent in-place
  `rotate` / `scale` on the translated value will pivot at the new
  pose, not at the original construction point.
- For path-driven rigid transport (replay a turtle path as a
  translate + rotate without going through `attach`), see `transform`.

## See also

- **Guide:** placeholder → cap. 9 (Positioning & assembly)
- **Related:** `scale`, `rotate`, `attach`, `translate-shape`,
  `transform`, `reset-creation-pose`
