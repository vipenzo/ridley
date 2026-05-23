---
name: sdf-intersection
category: sdf-modeling
since: ""
status: stable
---

# sdf-intersection

## Signature

`(sdf-intersection a)`
`(sdf-intersection a b & more)`
`(sdf-intersection [a b c …])`

## Description

Keep only the volume shared by every operand. Returns a new SDF tree.
No geometry is computed until meshing.

Variadic with the same conventions as `sdf-union`: any number of SDF
arguments or a single vector. With one operand returns it unchanged;
with zero returns `nil`. Anchors are merged with a first-wins policy
on name collisions.

`sdf-intersection` is the standard way to **bound an infinite SDF** — a
TPMS, a periodic pattern, or a formula — by another finite shape.

> Desktop only: requires the libfive backend.

## Parameters

- `a`, `b`, … — SDF nodes (any number ≥ 1). Pass separately or as a
  vector.

## Example

{{example: sdf-intersection-bound-tpms}}

<!-- example-source: sdf-intersection-bound-tpms -->
```clojure
;; Bound an infinite gyroid to a sphere
(register infill
  (sdf-intersection (sdf-sphere 20) (sdf-gyroid 8 0.5)))
```
<!-- /example-source -->

The gyroid is infinite; intersecting with the sphere clips it to a
finite, printable region.

## Notes

- `sdf-intersection` against a half-space is so common it has a
  shortcut: see `sdf-clip` for `(sdf-intersection shape (sdf-half-space))`.
- For mesh CSG see `mesh-intersection`. Staying in SDF until materialization
  avoids intermediate meshes.

## See also

- **SDF booleans:** `sdf-union`, `sdf-difference`
- **Half-space shortcut:** `sdf-clip`, `sdf-half-space`
- **Bound infinite SDFs:** `sdf-gyroid`, `sdf-schwarz-p`, `sdf-grid`,
  `sdf-slats`, `sdf-bars`
- **Mesh equivalent:** `mesh-intersection`
