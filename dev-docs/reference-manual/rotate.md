---
name: rotate
category: positioning-assembly
since: ""
status: stable
---

# rotate

## Signature

`(rotate thing :x|:y|:z angle-deg)`       ; cardinal axis (mesh / SDF)
`(rotate thing [ax ay az] angle-deg)`      ; arbitrary axis (mesh / SDF)
`(rotate shape angle-deg)`                 ; 2D shape (implicit Z)

## Description

Rotate a value around a chosen axis. Returns a new value.

`rotate` is polymorphic on the first argument:

| Type      | Axis convention                            | Pivot                       |
|-----------|--------------------------------------------|-----------------------------|
| Mesh      | world axes                                 | mesh's `:creation-pose` pos |
| SDF       | world axes                                 | SDF's `:creation-pose` pos  |
| 2D shape  | implicit Z                                 | shape origin (0, 0)         |

The mesh/SDF symmetry is intentional. For an SDF, the arbitrary-axis
form is implemented as a ZYX Tait-Bryan decomposition into three
cardinal-axis rotations — invisible to the caller, but worth knowing
near gimbal lock (pitch ≈ ±90° combined with non-zero yaw or roll).

## Parameters

- First argument — a mesh, an SDF node, or a 2D shape.
- Axis (mesh / SDF only) — `:x`, `:y`, `:z`, or a vector
  `[ax ay az]`. The vector need not be unit-length; it is normalised
  internally.
- `angle-deg` — rotation amount in degrees.

## Example

{{example: rotate-cardinal}}

<!-- example-source: rotate-cardinal -->
```clojure
;; Tilt a slab 30° around world Y
(register slab (rotate (box 30 8 4) :y 30))
```
<!-- /example-source -->

The cardinal-axis form takes a keyword and an angle. The pivot is
the mesh's creation-pose (the origin for a freshly built box).

## Variations

{{example: rotate-arbitrary-axis}}

<!-- example-source: rotate-arbitrary-axis -->
```clojure
;; Rotate 60° around the diagonal X+Y+Z
(register cube (box 10))
(register tilted (rotate cube [1 1 1] 60))
(hide :cube)
```
<!-- /example-source -->

A vector axis lets you rotate around any direction. The vector is
normalised internally.

{{example: rotate-after-boolean}}

<!-- example-source: rotate-after-boolean -->
```clojure
;; Boolean inherits first operand's creation-pose;
;; reset-creation-pose to pivot at the visual center
(register stamped (mesh-difference (box 30) (cyl 6 40)))
(register rolled (rotate (reset-creation-pose stamped) :y 35))
(hide :stamped)
```
<!-- /example-source -->

After a CSG operation, `reset-creation-pose` makes a subsequent
rotate pivot at the visual center instead of at the first operand's
construction origin.

{{example: rotate-shape-2d}}

<!-- example-source: rotate-shape-2d -->
```clojure
(register hex (extrude (rotate (poly [0 0  20 0  20 20  0 20]) 30) (u 5)))
```
<!-- /example-source -->

For 2D shapes, see [rotate-shape](rotate-shape.md).

## Notes

- World-axis rotation may not be what you want after the mesh has
  itself been rotated; for "rotate along the mesh's local heading",
  use `attach` with `(th …)` / `(tv …)` / `(tr …)` in the body —
  those follow the current turtle frame.
- Combine with `reset-creation-pose` when the construction pose is
  no longer the natural pivot (typically after CSG).
- For arbitrary-axis SDF rotation, the ZYX decomposition is exact
  away from gimbal lock; near singularities (pitch ≈ ±90° with non-
  zero yaw or roll), prefer cardinal-axis rotations chained
  manually.

## See also

- **Guide:** placeholder → cap. 9 (Positioning & assembly)
- **Related:** `translate`, `scale`, `attach`, `rotate-shape`,
  `reset-creation-pose`
