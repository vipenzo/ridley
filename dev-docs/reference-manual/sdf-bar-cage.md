---
name: sdf-bar-cage
category: sdf-modeling
since: ""
status: stable
---

# sdf-bar-cage

## Signature

`(sdf-bar-cage sx sy sz n radius & {:keys [axes blend]})`

## Description

Construct an SDF for a cage of cylindrical bars aligned to a centered
box of size `sx × sy × sz`. The cage has `n × n` bars per direction,
with bars on every edge and corner of the box. Returns a new SDF tree.

By default the cage includes bars on all three axes; restrict with
`:axes [:x :y]`, `:axes [:z]`, etc. for one- or two-axis cages.
Smooth the joints with `:blend k` — at the cost of giving up a true
SDF (see Warning).

> Desktop only: requires the libfive backend.

## Parameters

- `sx`, `sy`, `sz` — box dimensions in world units. The bars line up
  to the box edges.
- `n` — number of bars per side per direction (`n ≥ 2`). Higher values
  give a denser cage.
- `radius` — bar radius in world units.
- `:axes` (option, default `[:x :y :z]`) — vector of axes to include.
  Pick `[:x]`, `[:x :y]`, `[:y :z]`, etc. to drop directions.
- `:blend` (option, default `0`) — blend radius. When positive, joints
  between bars are smoothly merged with `sdf-blend`. **Caveat:** the
  blend version is **not a true SDF** — see warning under `sdf-grid`
  and the Warning note below.

## Example

{{example: sdf-bar-cage-basket}}

<!-- example-source: sdf-bar-cage-basket -->
```clojure
;; A basket: rounded container + 5x5 bar cage, hollowed with an open top
(register basket
  (sdf-difference
    (sdf-intersection
      (sdf-rounded-box 60 60 90 6)
      (sdf-bar-cage 60 60 90 5 1.5))
    (translate (sdf-rounded-box 56 56 100 6) 0 0 4)))
```
<!-- /example-source -->

The cage is intersected with the rounded container to bound it; a
slightly larger inner box is subtracted to hollow it and leave an
open top.

## Variations

{{example: sdf-bar-cage-z-only}}

<!-- example-source: sdf-bar-cage-z-only -->
```clojure
;; Vertical bars only — a fenced enclosure
(register fence
  (sdf-intersection (sdf-rounded-box 50 50 40 2)
                    (sdf-bar-cage 50 50 40 4 1 :axes [:z])))
```
<!-- /example-source -->

`:axes [:z]` keeps only the vertical bars; the cage becomes a fence
rather than a full 3D mesh.

## Notes

- **`:blend` warning.** The blend variant uses libfive's exponential
  blend, which does not produce a valid SDF. Combined with
  `sdf-intersection` / `sdf-difference` it can flip face normals at
  joints. For printable parts prefer the sharp-edge default (no
  `:blend`, or `:blend 0`).
- The cage is finite (clamped to the box dimensions) but not bounded:
  intersect with a finite shape for a printable result.

## See also

- **Periodic patterns:** `sdf-slats`, `sdf-bars`, `sdf-grid`
- **TPMS infills:** `sdf-gyroid`, `sdf-schwarz-p`, `sdf-diamond`
- **Bound with:** `sdf-intersection`, `sdf-difference`
