---
name: sdf-box
category: sdf-modeling
since: ""
status: stable
---

# sdf-box

## Signature

```
(sdf-box size)        ; cube with the given side
(sdf-box sx sy sz)    ; rectangular box
```

## Description

Construct an SDF node for a box, centered on the current turtle pose.
Returns a lightweight SDF tree; no geometry is computed until meshing.

The 1-arg form builds a cube of side `size`; the 3-arg form takes the
three side lengths individually — same convention as mesh `box`.

Like mesh primitives, SDF primitives spawn at the current turtle pose.
A bare `(sdf-box 10 10 10)` after `(f 30) (th 45)` lives at `(30 0 0)`
rotated 45° around the world Z axis.

For rounded corners with a properly behaved distance field (the kind
that combines cleanly with other SDFs), use `sdf-rounded-box` rather
than `(sdf-offset (sdf-box …) r)` — `sdf-offset` shifts the distance
field uniformly, which is not a true SDF away from the surface.

> Desktop only: requires the libfive backend.

## Parameters

- `size` — side length of the cube (1-arg form).
- `sx`, `sy`, `sz` — full dimensions along each axis (in world units),
  i.e. the side lengths, not half-extents (3-arg form).

## Example

{{example: sdf-box-basic}}

<!-- example-source: sdf-box-basic -->
```clojure
(register b (sdf-box 18 18 18))
```
<!-- /example-source -->

A cubic SDF box ready for booleans, blends, or morphs.

## Notes

- The box is centered on the current turtle pose at construction time
  — both position and orientation are baked into the resulting SDF.
- The argument order matches mesh `box`: `sx` runs along the turtle's
  *right* axis, `sy` along *up*, `sz` along *heading*. In the default
  pose those map to world Y, Z, X respectively.
- For a smooth blend with another SDF use `sdf-blend`; for a hollow
  shell use `sdf-shell`.

## See also

- **SDF primitives:** `sdf-sphere`, `sdf-cyl`, `sdf-cone`,
  `sdf-rounded-box`, `sdf-torus`
- **Booleans / blends:** `sdf-union`, `sdf-blend`, `sdf-difference`
- **Surface offset:** `sdf-rounded-box`, `sdf-offset`, `sdf-shell`
- **Transforms:** `translate`, `rotate`, `scale`, `attach`
