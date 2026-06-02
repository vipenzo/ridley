---
name: sdf-difference
category: sdf-modeling
since: ""
status: stable
---

# sdf-difference

## Signature

`(sdf-difference a b)`
`(sdf-difference a b c & more)`
`(sdf-difference [a b c …])`

## Description

Subtract one or more SDF nodes from `a`. Returns a new SDF tree
representing `(((a − b) − c) − d) …`. No geometry is computed until
meshing.

Variadic with the same conventions as `sdf-union`. With a single
operand the function returns that node unchanged; with an empty list it
returns `nil`. Anchors are inherited from the **minuend** (`a`) only —
operands that are subtracted do not contribute anchors.

For a **smooth** subtraction with a soft concavity, use
`sdf-blend-difference`.

> Desktop only: requires the libfive backend.

## Parameters

- `a` — the minuend SDF node (the shape being eaten into).
- `b`, … — subtrahend SDF nodes. Pass either separate arguments or a
  single vector of nodes.

## Example

{{example: sdf-difference-basic}}

<!-- example-source: sdf-difference-basic -->
```clojure
;; Sphere with a cylindrical hole through the middle
(register drilled
  (sdf-difference (sdf-sphere 12)
                  (sdf-cyl 4 30)))
```
<!-- /example-source -->

The cylinder is subtracted from the sphere, producing a clean cylindrical
bore.

## Notes

- Anchors come from the minuend only; if you need anchors from a
  subtrahend, mark them before the subtraction (or merge after with
  `mark`).
- For mesh CSG see `mesh-difference`. Staying in SDF until the final
  `register` avoids intermediate meshes.

## See also

- **SDF booleans:** `sdf-union`, `sdf-intersection`
- **Smooth variant:** `sdf-blend-difference`, `sdf-blend`
- **Mesh equivalent:** `mesh-difference`
