---
name: twist
category: spatial-deformation
since: ""
status: stable
---

# twist

## Signature

`(twist angle)`
`(twist angle axis)`

## Description

Return a deform-fn that rotates vertices around an axis, with the
rotation angle varying linearly along the axis and tapering to zero at
the volume boundary via `smooth-falloff`. Use with `warp`.

For cylinder and cone volumes, `twist` auto-detects the rotation axis
from the volume's orientation. For sphere and box volumes, the axis is
ambiguous — you must pass `:x`, `:y`, or `:z` explicitly.

The rotation angle is `±angle` at the two ends of the volume along the
axis and zero at the centre, so a positive `angle` produces a clockwise
twist on one half and counter-clockwise on the other.

## Parameters

- `angle` — maximum rotation in degrees at the volume extremes.
- `axis` (optional) — `:x`, `:y`, or `:z`. Required for sphere/box
  volumes; ignored for cylinder/cone (which always use their own axis).

## Example

{{example: twist-cyl-auto}}

<!-- example-source: twist-cyl-auto -->
```clojure
;; Twisted cylinder — axis auto-detected from volume
(register c
  (warp (cyl 10 40 32) (cyl 12 40) (twist 90)))
```
<!-- /example-source -->

A 90° spiral applied symmetrically: +45° at one end, −45° at the other,
zero in the middle. Works without an explicit axis because the volume
is a cylinder.

## Variations

{{example: twist-box-explicit-axis}}

<!-- example-source: twist-box-explicit-axis -->
```clojure
;; Sphere/box volume — must pass the axis explicitly
(register b
  (warp (box 30) (box 35) (twist 60 :y) :subdivide 2))
```
<!-- /example-source -->

A box of geometry twisted around the world Y axis. The same call without
an explicit axis throws — boxes have no preferred axis the warp engine
can infer.

## Notes

- The angle is linear in position-along-axis and scaled by
  `smooth-falloff` of the radial distance, so vertices on the volume
  boundary (in any direction) rotate by zero regardless of how far they
  are along the axis.
- For cylinder/cone volumes, the auto-detected axis is the primitive's
  heading direction at the time the volume was created (its
  creation-pose up).

## See also

- **Mother card:** `warp`
- **Related presets:** `squash`, `attract`, `inflate`, `roughen`
