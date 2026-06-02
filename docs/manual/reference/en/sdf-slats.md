---
name: sdf-slats
category: sdf-modeling
since: ""
status: stable
---

# sdf-slats

## Signature

`(sdf-slats axis period thickness)`
`(sdf-slats axis period thickness phase)`

## Description

Construct an SDF for an infinite stack of parallel flat walls
perpendicular to `axis`, spaced `period` apart, each of thickness
`thickness`. Returns a new SDF tree.

Use `sdf-slats` to punch ridges, fins, or grooves through another
shape: subtract them with `sdf-difference` for slots, or intersect with
`sdf-intersection` for a sliced solid. With a phase offset of
`period/2` consecutive slat sets stagger — useful for layered grids
that should not align at every join.

> Desktop only: requires the libfive backend.

## Parameters

- `axis` — `:x`, `:y`, or `:z`. The slats are perpendicular to this
  axis (their normal points along it).
- `period` — centre-to-centre distance in world units.
- `thickness` — wall thickness in world units.
- `phase` (optional, default `0`) — offset along the axis. Use
  `period/2` to stagger relative to other slat sets.

## Example

{{example: sdf-slats-punch-through-shell}}

<!-- example-source: sdf-slats-punch-through-shell -->
```clojure
;; Punch parallel slits through a container
(register container (sdf-shell (sdf-rounded-box 60 60 90 4) 1.5))
(register vase
  (sdf-difference container (sdf-slats :x 8 2)))
```
<!-- /example-source -->

Slats perpendicular to X carve a row of vertical slits through the
container shell.

## Variations

{{example: sdf-slats-staggered}}

<!-- example-source: sdf-slats-staggered -->
```clojure
;; Two stacks, one staggered by half a period — alternating openings
(register cage
  (sdf-union (sdf-slats :x 10 2)
             (sdf-slats :y 10 2 5)))
```
<!-- /example-source -->

The Y-axis set is offset by `period/2 = 5`, so the two stacks
interleave instead of crossing on the same grid lines.

## Notes

- The result is **infinite**. Bound it with `sdf-intersection` or use
  it as a subtrahend in `sdf-difference`.
- For cylindrical bars instead of flat walls, see `sdf-bars`. For a
  full 3D grid, see `sdf-grid`.

## See also

- **Periodic patterns:** `sdf-bars`, `sdf-bar-cage`, `sdf-grid`
- **TPMS infills:** `sdf-gyroid`, `sdf-schwarz-p`, `sdf-diamond`
- **Bound with:** `sdf-intersection`, `sdf-difference`
