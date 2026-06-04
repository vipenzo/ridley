---
name: sdf-cone
category: sdf-modeling
since: ""
status: stable
---

# sdf-cone

## Signature

`(sdf-cone r1 r2 h)`

## Description

Construct an SDF node for a cone or truncated cone (frustum), centered
on the current turtle pose, with its axis along the turtle's *heading*
— matching mesh `cone`. Returns a lightweight SDF tree; no geometry is
computed until meshing.

Like mesh primitives, SDF primitives spawn at the current turtle pose.
`r1` is the radius at the near/start end (−heading), `r2` the radius at
the far end (+heading, along the heading) — matching mesh `cone` and the
reading order of `loft`: `(sdf-cone r1 r2 h)` mirrors `(cone r1 r2 h)`.

Internally, `sdf-cone` is built on top of `sdf-formula` as
`max(rho - r(z), |z| - h/2)`, where `r(z)` interpolates linearly from
`r1` to `r2`. The iso-zero contour is the exact frustum surface,
suitable for booleans and meshing, although the field is not a true
Euclidean SDF away from the surface (a max-of-half-spaces formulation
rather than a perpendicular distance metric).

> Desktop only: requires the libfive backend.

## Parameters

- `r1` — radius at the near/start end (−heading).
- `r2` — radius at the far end (+heading, forward). Use `0` for a sharp tip (apex forward).
- `h` — total height (length along the axis).

## Example

{{example: sdf-cone-basic}}

<!-- example-source: sdf-cone-basic -->
```clojure
(register frustum (sdf-cone 8 3 30))
```
<!-- /example-source -->

A truncated cone 30 units long with an 8-unit near/start end and 3-unit far end.

## Variations

{{example: sdf-cone-tip}}

<!-- example-source: sdf-cone-tip -->
```clojure
;; Sharp cone (r2=0) blended onto a sphere
(register dart
  (sdf-blend
    (sdf-sphere 6)
    (attach (sdf-cone 5 0 20) (f 12))
    2))
```
<!-- /example-source -->

## Notes

- The cone axis tracks the turtle's *heading* at construction time
  (same as mesh `cone`). To align it along a different direction, use
  `rotate` after construction.
- Mesh smoothness around the slanted side is driven by the turtle
  resolution at construction time. Scope a higher value to a single
  cone with `(turtle (resolution :n 128) (sdf-cone …))`.
- Because the field is not a true Euclidean SDF, `sdf-offset` and
  `sdf-shell` may misbehave on this shape — prefer composing the cone
  with `sdf-union`, `sdf-intersection`, `sdf-difference`, and
  `sdf-blend`.

## See also

- **SDF primitives:** `sdf-sphere`, `sdf-box`, `sdf-cyl`,
  `sdf-rounded-box`, `sdf-torus`
- **Formula-based primitives:** `sdf-formula`
- **Booleans / blends:** `sdf-union`, `sdf-blend`, `sdf-difference`
- **Transforms:** `translate`, `rotate`, `scale`, `attach`
