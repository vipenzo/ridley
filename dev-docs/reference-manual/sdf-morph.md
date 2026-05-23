---
name: sdf-morph
category: sdf-modeling
since: ""
status: stable
---

# sdf-morph

## Signature

`(sdf-morph a b t)`

## Description

Interpolate between two SDFs. `t = 0` returns `a`, `t = 1` returns `b`,
and intermediate values blend the two distance fields linearly. Returns
a new SDF tree; anchors are merged from both operands.

`sdf-morph` is a building block for animations (drive `t` from a tweak
or animation track) and for "shape transitions" (a sphere becoming a
cube). Unlike `sdf-blend`, which produces a smooth union of two shapes
simultaneously visible, `sdf-morph` produces a single intermediate
shape that smoothly transforms between `a` and `b`.

> Desktop only: requires the libfive backend.

## Parameters

- `a`, `b` — two SDF nodes.
- `t` — interpolation parameter. `0` returns `a`, `1` returns `b`,
  values outside `[0, 1]` extrapolate (often producing odd shapes —
  most users stay in range).

## Example

{{example: sdf-morph-half}}

<!-- example-source: sdf-morph-half -->
```clojure
;; Halfway between a sphere and a cube
(register half-cube
  (sdf-morph (sdf-sphere 10) (sdf-box 14 14 14) 0.5))
```
<!-- /example-source -->

The result is a single shape that is neither a sphere nor a cube but
the linear blend of the two distance fields.

## Variations

{{example: sdf-morph-tweak-animated}}

<!-- example-source: sdf-morph-tweak-animated -->
```clojure
;; Drive t from a tweak knob for live morphing
(tweak [t 0 0 1]
  (register m
    (sdf-morph (sdf-sphere 10) (sdf-box 14 14 14) t)))
```
<!-- /example-source -->

`tweak` rebuilds the shape on every slider change; the morph parameter
becomes an interactive control.

## Notes

- The interpolation is on the distance field, not on triangle
  positions, so the topology can change smoothly (a sphere can morph
  into a cube without re-meshing artefacts).
- For *combining* two shapes with a smooth seam rather than replacing
  one with the other, use `sdf-blend`.

## See also

- **Related:** `sdf-blend`, `sdf-blend-difference`, `sdf-displace`
