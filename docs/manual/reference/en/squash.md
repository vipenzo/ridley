---
name: squash
category: spatial-deformation
since: ""
status: stable
---

# squash

## Signature

`(squash axis)`
`(squash axis amount)`

## Description

Return a deform-fn that flattens vertices toward a plane through the
volume's centre, perpendicular to `axis`. With falloff via
`smooth-falloff` so the squash tapers to zero at the volume boundary.
Use with `warp`.

A single-argument call flattens fully (vertices on the plane). The
two-argument form lets you keep some of the original offset:
`(squash axis 0)` is fully flat, `(squash axis 1)` is the identity, and
intermediate values blend between the two.

## Parameters

- `axis` — `:x`, `:y`, or `:z`. World axis along which to flatten.
- `amount` (optional, default `0`) — preserved fraction of the original
  offset along the axis. `0` = fully flat, `1` = no effect.

## Example

{{example: squash-sphere-flatten-y}}

<!-- example-source: squash-sphere-flatten-y -->
```clojure
;; Flatten the top of a sphere
(register s
  (warp (sphere 20 32 16)
        (attach (sphere 12) (u 14))
        (squash :y)))
```
<!-- /example-source -->

A small sphere volume placed above the centre defines the flatten zone;
`(squash :y)` collapses the y-coordinates of vertices inside it onto
the volume's centre plane, producing a flattened cap.

## Variations

{{example: squash-partial}}

<!-- example-source: squash-partial -->
```clojure
;; Partial squash — keep 40% of the original offset
(register s
  (warp (sphere 20 32 16)
        (attach (sphere 12) (u 14))
        (squash :y 0.4)))
```
<!-- /example-source -->

With `amount 0.4` the cap is lowered but not fully flat — useful for a
subtle bevel-like effect rather than a sharp tabletop.

## Notes

- The flattening axis is the **world** axis, not the volume's local
  axis. To flatten along an arbitrary direction, rotate the mesh first.
- The smooth-falloff applies to the radial distance from the volume
  centre, so vertices on the boundary along the axis are unaffected
  even before any amount blending.

## See also

- **Mother card:** `warp`
- **Related presets:** `attract`, `twist`, `inflate`, `dent`
