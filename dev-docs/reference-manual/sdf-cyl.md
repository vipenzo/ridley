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
centered at the origin with its axis along Z. Returns a lightweight
SDF tree; no geometry is computed until meshing.

> Desktop only: requires the libfive backend.

## Parameters

- `r` — radius in world units.
- `h` — total height along the cylinder axis (Z).

## Example

{{example: sdf-cyl-basic}}

<!-- example-source: sdf-cyl-basic -->
```clojure
(register c (sdf-cyl 8 30))
```
<!-- /example-source -->

A cylinder 16 units across and 30 units tall, ready for booleans or
blends.

## Notes

- The cylinder axis is the SDF Z axis at construction time. To align it
  along a different direction, use `rotate` (e.g. `(rotate c :y 90)`
  swings the axis onto the world X direction).
- For an elliptical cross-section, scale per-axis: `(scale c 2 1 1)`.

## See also

- **SDF primitives:** `sdf-sphere`, `sdf-box`, `sdf-rounded-box`,
  `sdf-torus`
- **Periodic patterns of cylinders:** `sdf-bars`, `sdf-bar-cage`
- **Booleans / blends:** `sdf-union`, `sdf-blend`, `sdf-difference`
- **Transforms:** `translate`, `rotate`, `scale`, `attach`
