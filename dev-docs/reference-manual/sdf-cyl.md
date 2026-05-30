---
name: sdf-cyl
category: sdf-modeling
since: ""
status: stable
---

# sdf-cyl

## Signature

`(sdf-cyl r h)`

## Description

Construct an SDF node for a cylinder of radius `r` and height `h`,
centered on the current turtle pose with its axis along the turtle's
*heading* — matching mesh `cyl`. Returns a lightweight SDF tree; no
geometry is computed until meshing.

Like mesh primitives, SDF primitives spawn at the current turtle pose.
In the default pose the cylinder lies along world X. After `(tv 90)`
the turtle's heading tilts onto world Z, and the cylinder axis follows.

> Desktop only: requires the libfive backend.

## Parameters

- `r` — radius in world units.
- `h` — total height along the cylinder axis (turtle's heading).

## Example

{{example: sdf-cyl-basic}}

<!-- example-source: sdf-cyl-basic -->
```clojure
(register c (sdf-cyl 8 30))
```
<!-- /example-source -->

A cylinder 16 units across and 30 units long, axis along the turtle's
heading. Ready for booleans or blends.

## Notes

- The cylinder axis tracks the turtle's *heading* at construction time
  (same as mesh `cyl`). To align it along a different direction, use
  `rotate` after construction.
- Mesh smoothness around the side is driven by the turtle resolution
  (same as mesh `cyl`): `(turtle (resolution :n 128) (sdf-cyl 5 20))`
  scopes the higher detail to that one primitive.
- For an elliptical cross-section, scale per-axis: `(scale c 2 1 1)`.

## See also

- **SDF primitives:** `sdf-sphere`, `sdf-box`, `sdf-cone`,
  `sdf-rounded-box`, `sdf-torus`
- **Periodic patterns of cylinders:** `sdf-bars`, `sdf-bar-cage`
- **Booleans / blends:** `sdf-union`, `sdf-blend`, `sdf-difference`
- **Transforms:** `translate`, `rotate`, `scale`, `attach`
