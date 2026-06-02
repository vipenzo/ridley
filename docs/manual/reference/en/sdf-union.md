---
name: sdf-union
category: sdf-modeling
since: ""
status: stable
---

# sdf-union

## Signature

`(sdf-union a)`
`(sdf-union a b & more)`
`(sdf-union [a b c …])`

## Description

Combine SDF nodes into their union — a single node whose surface
encloses the union of every operand. Returns a new SDF tree; no
geometry is computed until meshing.

Variadic: accepts any number of SDF arguments, including a single
vector. With zero usable nodes the result is `nil`; with one node it
returns that node unchanged. Anchors are merged with a first-wins
policy on name collisions.

For a **smooth** union with a fillet-like seam, use `sdf-blend`.

> Desktop only: requires the libfive backend.

## Parameters

- `a`, `b`, … — SDF nodes (any number ≥ 1). Pass either separate
  arguments or a single vector of nodes.

## Example

{{example: sdf-union-basic}}

<!-- example-source: sdf-union-basic -->
```clojure
;; Union of three primitives, sharp seams at intersections
(register cluster
  (sdf-union (sdf-sphere 8)
             (translate (sdf-sphere 8) 10 0 0)
             (translate (sdf-sphere 8) 0 10 0)))
```
<!-- /example-source -->

Three overlapping spheres merged into one shape. The seams are sharp;
for rounded transitions use `sdf-blend`.

## Notes

- The union of zero nodes returns `nil`. Code that builds unions from
  filtered sequences should guard against the empty case.
- For mesh CSG (after materialization), the equivalent operator is
  `mesh-union` — but combining at the SDF layer is preferred when the
  operands are already SDFs because no intermediate meshes are
  produced.

## See also

- **SDF booleans:** `sdf-difference`, `sdf-intersection`
- **Smooth variant:** `sdf-blend`, `sdf-blend-difference`
- **Mesh equivalent:** `mesh-union`
