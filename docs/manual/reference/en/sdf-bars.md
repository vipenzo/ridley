---
name: sdf-bars
category: sdf-modeling
since: ""
status: stable
---

# sdf-bars

## Signature

`(sdf-bars axis period radius)`
`(sdf-bars axis period radius phase-a phase-b)`

## Description

Construct an SDF for an infinite forest of parallel cylindrical bars
running along `axis`, distributed over a 2D periodic grid in the two
perpendicular axes. Returns a new SDF tree.

`period` can be a single number (same period on both perpendicular
axes) or a `[period-a period-b]` vector (different periods per axis).
Phase offsets shift the grid along each perpendicular axis
independently.

> Desktop only: requires the libfive backend.

## Parameters

- `axis` — `:x`, `:y`, or `:z`. The bars run along this direction.
- `period` — number or `[pa pb]`. Centre-to-centre distance between
  bars on the two perpendicular axes. A single number applies to
  both.
- `radius` — bar radius in world units.
- `phase-a`, `phase-b` (optional, both default `0`) — offsets along
  the two perpendicular axes. The pairing of `a`/`b` with world axes
  depends on `axis` (see Notes).

## Example

{{example: sdf-bars-cage}}

<!-- example-source: sdf-bars-cage -->
```clojure
;; A forest of vertical bars bounded by a sphere
(register cage
  (sdf-intersection (sdf-sphere 20) (sdf-bars :z 8 1)))
```
<!-- /example-source -->

Vertical bars on an 8-unit grid, clipped to a sphere — a recognisable
"cage" infill.

## Variations

{{example: sdf-bars-different-periods}}

<!-- example-source: sdf-bars-different-periods -->
```clojure
;; Rectangular grid: 12-unit spacing on one axis, 6 on the other
(register grid
  (sdf-intersection (sdf-rounded-box 60 60 30 3)
                    (sdf-bars :z [12 6] 1)))
```
<!-- /example-source -->

Pass `period` as a vector to give the two perpendicular axes different
spacings — useful for stripes, tabular layouts, or wall-friendly
patterns.

## Notes

- The result is **infinite**. Bound it with `sdf-intersection` or
  subtract it from a solid with `sdf-difference`.
- The pairing of `phase-a`/`phase-b` follows the axis: for `:z` bars,
  `phase-a` shifts along X and `phase-b` along Y; for `:x`, along Y
  and Z; for `:y`, along X and Z.
- For flat walls instead of cylinders, see `sdf-slats`. For an
  axis-aligned box of bars on all three axes, see `sdf-bar-cage`.

## See also

- **Periodic patterns:** `sdf-slats`, `sdf-bar-cage`, `sdf-grid`
- **TPMS infills:** `sdf-gyroid`, `sdf-schwarz-p`, `sdf-diamond`
- **Bound with:** `sdf-intersection`, `sdf-difference`
