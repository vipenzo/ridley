---
name: sdf-box
category: sdf-modeling
since: ""
status: stable
---

# sdf-box

## Signature

`(sdf-box sx sy sz)`

## Description

Construct an SDF node for an axis-aligned box with dimensions
`sx × sy × sz`, centered at the origin. Returns a lightweight SDF tree
map; no geometry is computed until meshing.

For rounded corners with a properly behaved distance field (the kind
that combines cleanly with other SDFs), use `sdf-rounded-box` rather
than `(sdf-offset (sdf-box …) r)` — `sdf-offset` shifts the distance
field uniformly, which is not a true SDF away from the surface.

> Desktop only: requires the libfive backend.

## Parameters

- `sx`, `sy`, `sz` — full dimensions along each axis (in world units),
  i.e. the side lengths, not half-extents.

## Example

{{example: sdf-box-basic}}

<!-- example-source: sdf-box-basic -->
```clojure
(register b (sdf-box 18 18 18))
```
<!-- /example-source -->

A cubic SDF box ready for booleans, blends, or morphs.

## Notes

- The box is centered on the current turtle creation-pose at
  construction time. Position it with `translate`, `attach`, or by
  wrapping in `turtle (…)`.
- The argument order maps to the libfive backend's internal axes; in
  the turtle's default pose (heading along world X, up along world Z)
  the box dimensions follow the world axes in `sx sy sz` order.
- For a smooth blend with another SDF use `sdf-blend`; for a hollow
  shell use `sdf-shell`.

## See also

- **SDF primitives:** `sdf-sphere`, `sdf-cyl`, `sdf-rounded-box`,
  `sdf-torus`
- **Booleans / blends:** `sdf-union`, `sdf-blend`, `sdf-difference`
- **Surface offset:** `sdf-rounded-box`, `sdf-offset`, `sdf-shell`
- **Transforms:** `translate`, `rotate`, `scale`, `attach`
