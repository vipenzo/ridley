---
name: sdf-diamond
category: sdf-modeling
since: ""
status: stable
---

# sdf-diamond

## Signature

`(sdf-diamond period thickness)`

## Description

Construct an SDF for the diamond (Schwarz-D) triply-periodic minimal
surface — an infinite lattice of tetrahedral cells. Returns a new SDF
tree.

Diamond is the densest of the three built-in TPMS variants: it packs
more wall area per unit volume than `sdf-gyroid` or `sdf-schwarz-p`,
giving the highest strength at the same wall thickness. The visual
texture is a 3D pattern of tetrahedra joining at the cell vertices.

> Desktop only: requires the libfive backend.

## Parameters

- `period` — cell size in world units. Larger = coarser lattice.
- `thickness` — wall thickness in world units.

## Example

{{example: sdf-diamond-block}}

<!-- example-source: sdf-diamond-block -->
```clojure
;; A diamond-lattice infill bounded by a rounded box
(register block
  (sdf-intersection (sdf-rounded-box 40 40 40 3)
                    (sdf-diamond 8 1.0)))
```
<!-- /example-source -->

The diamond lattice is bounded inside the rounded box, producing a
solid suitable for high-strength infill.

## Notes

- Diamond's denser topology means voxel costs at the same period are
  slightly higher than gyroid or Schwarz-P at the same resolution.
- For lighter or less orthogonal variants, see `sdf-gyroid` (organic
  diagonal weave) and `sdf-schwarz-p` (cubic channels).

## See also

- **Other TPMS:** `sdf-gyroid`, `sdf-schwarz-p`
- **Bound with:** `sdf-intersection`, `sdf-difference`
- **Related:** `sdf-formula`, `sdf-shell`, `sdf-resolution!`
