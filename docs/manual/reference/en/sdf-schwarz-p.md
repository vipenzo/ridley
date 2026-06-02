---
name: sdf-schwarz-p
category: sdf-modeling
since: ""
status: stable
---

# sdf-schwarz-p

## Signature

`(sdf-schwarz-p period thickness)`

## Description

Construct an SDF for a Schwarz-P triply-periodic minimal surface — an
infinite lattice of cubic channels meeting at orthogonal junctions.
Returns a new SDF tree.

Schwarz-P is the orthogonal cousin of the gyroid: where the gyroid
weaves diagonally, Schwarz-P aligns with the cardinal axes. Use it
when you want a visibly orthogonal infill — for example, a lattice
that reads as a stack of cubic cells rather than an organic web.

> Desktop only: requires the libfive backend.

## Parameters

- `period` — cell size in world units. Larger = coarser lattice.
- `thickness` — wall thickness in world units.

## Example

{{example: sdf-schwarz-p-cube}}

<!-- example-source: sdf-schwarz-p-cube -->
```clojure
;; A Schwarz-P-filled cube
(register lattice
  (sdf-intersection (sdf-box 30 30 30) (sdf-schwarz-p 10 0.8)))
```
<!-- /example-source -->

A 10-unit period gives three full cells across the 30-unit cube.

## Notes

- Like other TPMS, the result is built as a shell over an implicit
  surface; auto-resolution boosts for thin walls.
- For organic/diagonal variants, see `sdf-gyroid` and `sdf-diamond`.

## See also

- **Other TPMS:** `sdf-gyroid`, `sdf-diamond`
- **Bound with:** `sdf-intersection`, `sdf-difference`
- **Related:** `sdf-formula`, `sdf-shell`, `sdf-resolution!`
