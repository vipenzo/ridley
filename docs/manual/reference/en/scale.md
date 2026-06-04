---
name: scale
category: positioning-assembly
since: ""
status: stable
---

# scale

## Signature

`(scale thing s)`                  ; uniform
`(scale thing sx sy sz)`           ; per-axis (mesh / SDF)
`(scale thing sx sy)`              ; per-axis (2D shape)
`(scale thing [sx sy sz])`         ; vector form

## Description

Scale a value uniformly or per-axis. Returns a new value.

`scale` is polymorphic. It dispatches on the type of the first
argument:

| Type      | Axes        | Pivot                                       |
|-----------|-------------|---------------------------------------------|
| Mesh      | world       | mesh's `:creation-pose` position            |
| SDF       | world       | SDF's `:creation-pose` position             |
| 2D shape  | local       | shape centroid                              |

The mesh/SDF symmetry is intentional: swapping one for the other in
a pipeline does not change pivot behavior.

**Negative factors (reflection).** Mesh `scale` accepts negative
factors, which reflect the mesh along the corresponding axes. When
the product is negative (odd count of negatives, e.g. `(scale m -1
1 1)`), face winding is auto-reversed so the result stays manifold
and remains usable in subsequent CSG. Positive-product scales
(e.g. `(scale m -1 -1 1)`, which is a 180° rotation around Z) need
no fix and work as is.

**`scale` is not allowed inside `attach`.** Use `stretch-f`,
`stretch-rt`, `stretch-u` for local-axis scaling along the current
turtle frame. The `scale` call inside an attach body throws an
explanatory error.

## Parameters

- First argument — a mesh, an SDF node, or a 2D shape.
- `s` (uniform) or `sx sy sz` (per-axis for mesh/SDF) or `sx sy` (for
  shape). The vector form `[sx sy sz]` is accepted for mesh/SDF.

## Example

{{example: scale-uniform}}

<!-- example-source: scale-uniform -->
```clojure
(register orig (box 10))
(register big  (translate (scale (box 10) 2) 25 0 0))
```
<!-- /example-source -->

A uniform scale doubles the box's linear size, octuples the volume.
The pivot is the creation-pose (the origin for a freshly built box).

## Variations

{{example: scale-per-axis}}

<!-- example-source: scale-per-axis -->
```clojure
(register slab (scale (box 10) 3 1 0.5))
```
<!-- /example-source -->

Per-axis factors squash and stretch independently along world X / Y / Z.

{{example: scale-reflect}}

<!-- example-source: scale-reflect -->
```clojure
;; Mirror about the YZ plane
(register left  (translate (cyl 4 12 :feathered) -15 0 0))
(register right (scale left -1 1 1))
```
<!-- /example-source -->

A negative X factor reflects the mesh across the YZ plane. Face
winding is auto-flipped so the reflected mesh stays manifold and can
be unioned or subtracted normally.

{{example: scale-shape-2d}}

<!-- example-source: scale-shape-2d -->
```clojure
;; 2D shape: pivot at the shape's centroid, axes are the shape's local axes
(register tile (extrude (scale (rect 8 8) 1.5) (u 2)))
```
<!-- /example-source -->

For shapes, see [scale-shape](scale-shape.md) for the dedicated card.

## Notes

- For local-axis scaling on a rotated mesh, the polymorphic `scale`
  is world-only by design. Use `stretch-f` / `stretch-rt` /
  `stretch-u` inside an `attach` body when the desired axes must
  follow the mesh's current orientation.
- After a boolean operation, the result inherits the first operand's
  creation-pose. If you want a subsequent `scale` to pivot at the
  visual center instead, call `reset-creation-pose` first.
- Type-specific aliases (`mesh-scale`, `scale-shape`) route to the
  same implementations for backward compatibility.

## See also

- **Related:** `translate`, `rotate`, `attach`, `scale-shape`,
  `reset-creation-pose`, `stretch-f`, `stretch-rt`, `stretch-u`
