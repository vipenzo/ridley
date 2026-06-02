---
name: sdf-grid
category: sdf-modeling
since: ""
status: stable
---

# sdf-grid

## Signature

`(sdf-grid period thickness)`
`(sdf-grid period thickness blend-k)`

## Description

Construct an SDF for a 3D grid lattice — three orthogonal sets of
slats joined into a single infinite scaffold. Returns a new SDF tree.

The two-arg form produces sharp edges where slats meet (a true SDF
suitable for booleans). The three-arg form blends the joints with
radius `blend-k` for a smoother look — at the cost of producing a
**non-SDF**. The blend version may flip face normals when combined
with `sdf-intersection` or `sdf-difference`; for printable parts
always prefer the sharp-edge version.

> Desktop only: requires the libfive backend.

## Parameters

- `period` — centre-to-centre distance between adjacent slats, in
  world units. Same on all three axes.
- `thickness` — wall thickness in world units.
- `blend-k` (optional) — blend radius for smooth joints. **Caveat:**
  the result is not a valid SDF (see Warning below).

## Example

{{example: sdf-grid-bounded-sphere}}

<!-- example-source: sdf-grid-bounded-sphere -->
```clojure
;; Sharp-edge grid lattice bounded by a sphere
(register ball
  (sdf-intersection (sdf-sphere 20) (sdf-grid 8 1.5)))
```
<!-- /example-source -->

The sharp-edge variant intersects cleanly with the sphere, producing a
printable lattice ball.

## Variations

{{example: sdf-grid-perforated-cube}}

<!-- example-source: sdf-grid-perforated-cube -->
```clojure
;; Perforated cube — carve a grid pattern out of a solid box
(register perforated
  (sdf-intersection (sdf-box 40 40 40) (sdf-grid 10 2)))
```
<!-- /example-source -->

Equivalent to bounding the infinite grid by the box. The same call
with `sdf-difference` would give the inverse — a box with the grid
carved out.

## Notes

- **Blend warning.** The 3-arg blend variant uses libfive's
  exponential blend, which does not produce a valid SDF. The gradient
  can invert at the joint regions, causing flipped face normals when
  combined with `sdf-intersection` or `sdf-difference`. For printable
  parts always prefer the sharp 2-arg version.
- For just one axis of slats, see `sdf-slats`. For cylindrical bars
  instead of flat walls, see `sdf-bars`. For a finite bar cage tied
  to specific box dimensions, see `sdf-bar-cage`.

## See also

- **Periodic patterns:** `sdf-slats`, `sdf-bars`, `sdf-bar-cage`
- **TPMS infills:** `sdf-gyroid`, `sdf-schwarz-p`, `sdf-diamond`
- **Bound with:** `sdf-intersection`, `sdf-difference`
