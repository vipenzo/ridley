---
name: slice-at-plane
category: mesh-operations
since: ""
status: stable
---

# slice-at-plane

## Signature

`(slice-at-plane mesh normal point)`
`(slice-at-plane mesh normal point right up)`

## Description

Slice a mesh at an explicitly-defined plane. The plane is given by
its normal and a point on it; the resulting cross-section is returned
as a vector of 2D shapes, identical in format to `slice-mesh`.

`slice-at-plane` is the lower-level counterpart of `slice-mesh`:
where `slice-mesh` reads the plane from the turtle's current pose,
`slice-at-plane` takes it as explicit parameters. Use it when the
slicing plane comes from a computation (e.g. mid-height of a bounding
box, a normal computed from two anchors) rather than from the live
turtle.

With three arguments, the local right/up basis is computed
automatically from the normal. With five arguments, you supply the
basis explicitly — useful when you want a known orientation for the
output shapes' local X/Y axes (e.g. for sheet-cutting templates that
must align with the world axes).

## Parameters

- `mesh` — a mesh value, a registered mesh keyword, or an SDF node.
- `normal` — `[nx ny nz]` defining the plane normal.
- `point` — `[px py pz]` a point lying on the plane.
- `right` (optional) — `[rx ry rz]` local X-axis of the output
  shapes.
- `up` (optional) — `[ux uy uz]` local Y-axis of the output shapes.

## Example

{{example: slice-at-plane-horizontal}}

<!-- example-source: slice-at-plane-horizontal -->
```clojure
(register cup (revolve (shape (f 20) (th -90) (f 30) (th -90) (f 15))))

;; Horizontal slice at Z=20 — normal points along world +Z, point lies on the plane
(let [contour (first (slice-at-plane :cup [0 0 1] [0 0 20]))]
  (register cap (extrude contour (u 2))))
```
<!-- /example-source -->

The cup is sliced horizontally; the resulting contour is then
extruded up to make a flat cap. The plane is defined entirely without
moving the turtle.

## Variations

{{example: slice-at-plane-explicit-basis}}

<!-- example-source: slice-at-plane-explicit-basis -->
```clojure
;; Vertical slice at X=0, with X = world Y, Y = world Z
(let [contour (first (slice-at-plane :cup
                                     [1 0 0]
                                     [0 0 0]
                                     [0 1 0]
                                     [0 0 1]))]
  (stamp contour))
```
<!-- /example-source -->

An explicit local basis lets the output shape's X/Y match a desired
world orientation — useful when the contour is going to be exported
to a 2D cutter or compared to another shape produced in different
local coordinates.

## Notes

- Returns the same `:preserve-position? true` shapes as `slice-mesh`,
  so subsequent `stamp` calls render them at exact plane-local
  coordinates.
- The three-argument form is correct for most cases; the
  five-argument form is reserved for situations where the
  auto-computed basis happens to be ambiguous (e.g. a normal aligned
  with one of the cardinal axes) or where you need explicit
  alignment.
- Accepts mesh values, registered keywords, and SDF nodes (the SDF
  is auto-materialized at the current `*sdf-resolution*`).

## See also

- **Related:** `slice-mesh`, `project-mesh`, `stamp`
