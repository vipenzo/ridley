---
name: sdf-rounded-box
category: sdf-modeling
since: ""
status: stable
---

# sdf-rounded-box

## Signature

`(sdf-rounded-box sx sy sz r)`

## Description

Construct an SDF node for a box with dimensions `sx × sy × sz` and
corners rounded with radius `r`. Centered on the current turtle pose,
same axis convention as `sdf-box`. The result is a **true SDF** — the
field reports the actual signed distance everywhere — so the shape
combines cleanly with other SDFs under `sdf-intersection`,
`sdf-difference`, and `sdf-blend`.

This is the recommended way to build a rounded box. The naïve
alternative `(sdf-offset (sdf-box …) r)` shifts the box field uniformly,
which produces a non-SDF away from the surface and can corrupt
downstream booleans.

> Desktop only: requires the libfive backend.

## Parameters

- `sx`, `sy`, `sz` — full dimensions along each axis (world units).
- `r` — corner radius. Must be less than half of the smallest
  dimension; otherwise the box collapses inside out.

## Example

{{example: sdf-rounded-box-basic}}

<!-- example-source: sdf-rounded-box-basic -->
```clojure
(register block (sdf-rounded-box 30 30 60 4))
```
<!-- /example-source -->

A tall block with 4-unit fillets on every edge and corner. Ready for
SDF booleans.

## Variations

{{example: sdf-rounded-box-cage}}

<!-- example-source: sdf-rounded-box-cage -->
```clojure
;; Combine with a bar cage and hollow the interior — a printable basket
(register basket
  (sdf-difference
    (sdf-intersection
      (sdf-rounded-box 60 60 90 6)
      (sdf-bar-cage 60 60 90 5 1.5))
    (translate (sdf-rounded-box 56 56 100 6) 0 0 4)))
```
<!-- /example-source -->

`sdf-rounded-box` is the canonical container shape when combining with
periodic patterns: its true-SDF field keeps booleans well-behaved.

## Notes

- The corner radius applies uniformly to edges and corners; for
  selective filleting you need explicit constructive geometry (e.g.
  building from primitives + blends).
- For a non-true-SDF surface offset (cheap inflation/contraction
  without rounding semantics), see `sdf-offset`.

## See also

- **SDF primitives:** `sdf-sphere`, `sdf-box`, `sdf-cyl`, `sdf-cone`,
  `sdf-torus`
- **Avoid for rounding:** `sdf-offset`
- **Booleans / blends:** `sdf-union`, `sdf-blend`, `sdf-difference`
- **Transforms:** `translate`, `rotate`, `scale`, `attach`
