---
name: sdf-gyroid
category: sdf-modeling
since: ""
status: stable
---

# sdf-gyroid

## Signature

`(sdf-gyroid period thickness)`

## Description

Construct an SDF for a gyroid triply-periodic minimal surface (TPMS) —
an infinite, organic-looking lattice that fills 3-space with a wall of
the requested thickness. Returns a new SDF tree.

The gyroid is the most popular TPMS for 3D printing: high strength-to-
weight ratio, self-supporting at any orientation, and visually
appealing. Use it as an infill structure inside a finite shape via
`sdf-intersection`.

> Desktop only: requires the libfive backend.

## Parameters

- `period` — cell size (centre-to-centre between repeating units in
  world units). Larger period = coarser lattice.
- `thickness` — wall thickness in world units.

## Example

{{example: sdf-gyroid-sphere}}

<!-- example-source: sdf-gyroid-sphere -->
```clojure
;; Gyroid-filled sphere
(register infill
  (sdf-intersection (sdf-sphere 20) (sdf-gyroid 8 0.5)))
```
<!-- /example-source -->

The gyroid is infinite; intersecting with the sphere clips it into a
finite, printable infill structure.

## Notes

- The gyroid is itself an `sdf-shell` over a sine-cosine implicit
  surface — so auto-resolution may bump up for thin walls. For very
  thin shells, set `(sdf-resolution! N)` higher before meshing.
- For alternative TPMS structures, see `sdf-schwarz-p` (cubic
  channels) and `sdf-diamond` (tetrahedral cells).

## See also

- **Other TPMS:** `sdf-schwarz-p`, `sdf-diamond`
- **Bound with:** `sdf-intersection`, `sdf-difference`
- **Related:** `sdf-formula`, `sdf-shell`, `sdf-resolution!`
