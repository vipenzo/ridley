---
name: attract
category: spatial-deformation
since: ""
status: stable
---

# attract

## Signature

`(attract strength)`

## Description

Return a deform-fn that pulls vertices toward the centre of the volume
(for sphere and box volumes) or toward the volume's axis (for cylinder
and cone volumes). Strength varies smoothly with distance via
`smooth-falloff`. Use with `warp`.

`attract` produces a localised collapse: a sphere volume with
`(attract 0.5)` creates a dimple that pulls geometry inward toward a
single point; a cylinder volume creates a pinch along its axis. For
pushing geometry outward instead, use `inflate`.

## Parameters

- `strength` — `0` has no effect, `1` pulls vertices fully to the
  centre (sphere/box) or axis (cylinder/cone). Values outside `[0, 1]`
  are accepted but rarely useful — `>1` overshoots, `<0` repels.

## Example

{{example: attract-sphere-dimple}}

<!-- example-source: attract-sphere-dimple -->
```clojure
;; A circular dimple on a flat face
(register b
  (warp (box 40) (attach (sphere 12) (u 20)) (attract 0.6) :subdivide 2))
```
<!-- /example-source -->

Vertices inside the small sphere are pulled 60% of the way toward its
centre. The smooth falloff turns the cliff into a gentle dimple at the
volume boundary.

## Variations

{{example: attract-cyl-pinch}}

<!-- example-source: attract-cyl-pinch -->
```clojure
;; Pinch a cylinder toward its axis
(register c
  (warp (cyl 12 40 32) (cyl 14 20) (attract 0.5)))
```
<!-- /example-source -->

With a cylinder volume the target is the volume's *axis*, not its
centre point: vertices in the middle section are pulled radially inward,
producing a waist.

## Notes

- The volume's `:primitive` keyword selects the target shape (point or
  axis). Custom meshes used as volumes default to the centre-point
  target.
- Combine with `inflate` to model a localized funnel: `attract` pulls
  the rim, `inflate` lifts the centre.

## See also

- **Mother card:** `warp`
- **Related presets:** `inflate`, `dent`, `squash`, `twist`
