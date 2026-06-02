---
name: sdf-blend
category: sdf-modeling
since: ""
status: stable
---

# sdf-blend

## Signature

`(sdf-blend a b k)`

## Description

Smooth union of two SDF nodes. `k` controls the blend radius: higher
values produce a wider, smoother transition where the two shapes meet,
behaving like a fillet welded between them. Returns a new SDF tree.

`sdf-blend` is the SDF analogue of `sdf-union` with a fillet built in.
Use it whenever you want organic transitions rather than sharp seams.
For the inverse operation (smooth subtraction), use
`sdf-blend-difference`.

> Desktop only: requires the libfive backend.

## Parameters

- `a`, `b` — two SDF nodes.
- `k` — blend radius in world units. `0` reduces to a sharp union;
  larger values produce a wider, softer junction. As a rule of thumb,
  `k` is similar in magnitude to the smallest feature involved in the
  blend.

## Example

{{example: sdf-blend-basic}}

<!-- example-source: sdf-blend-basic -->
```clojure
;; Sphere and box welded with a 3-unit fillet
(register blob (sdf-blend (sdf-sphere 10) (sdf-box 14 14 14) 3))
```
<!-- /example-source -->

The two primitives merge with a smooth transition; no manual fillet
geometry needed.

## Variations

{{example: sdf-blend-cluster}}

<!-- example-source: sdf-blend-cluster -->
```clojure
;; Chain blends to combine more than two operands smoothly
(register cluster
  (sdf-blend
    (sdf-blend (sdf-sphere 10)
               (translate (sdf-sphere 8) 10 0 0) 3)
    (translate (sdf-cyl 4 14) 0 0 10) 2))
```
<!-- /example-source -->

Each `sdf-blend` is binary; nest calls for multi-operand smoothing,
varying `k` per stage to taper the joints.

## Notes

- For sharp unions use `sdf-union`. For chained sharp unions the same
  variadic call structure (one node, many nodes, vector) is supported;
  `sdf-blend` does not.
- Anchors are merged from both operands with a first-wins policy on
  name collisions.

## See also

- **Sharp variant:** `sdf-union`
- **Inverse (smooth subtraction):** `sdf-blend-difference`
- **Related operations:** `sdf-morph`, `sdf-shell`, `sdf-offset`
- **Bound the result with:** `sdf-intersection`
