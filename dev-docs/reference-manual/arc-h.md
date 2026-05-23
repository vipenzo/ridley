---
name: arc-h
category: turtle-movement
since: ""
status: stable
---

# arc-h

## Signature

`(arc-h radius angle)`
`(arc-h radius angle & {:keys [steps]})`

## Description

Move the turtle along a circular arc in the horizontal plane (the plane
perpendicular to up): both the position and the heading change so the
turtle traces a smooth curve. The rotation axis is the **up** vector.
**Modifies turtle state.**

Step count defaults to the current resolution; pass `:steps` for an
explicit override. Inside `path` and extrusion macros, `arc-h` records
a smooth recorded path that subsequent operations sweep over.

## Parameters

- `radius` — arc radius. Positive for left curve, negative for right
  curve (or use a negative `angle` instead).
- `angle` — total turn in degrees. Positive = anticlockwise around up.
- `:steps` — number of segments along the arc. Defaults to the
  resolution setting (see `resolution`).

## Example

{{example: arc-h-basic}}

<!-- example-source: arc-h-basic -->
```clojure
(arc-h 10 90)         ;; quarter circle left, radius 10
(arc-h 10 -90)        ;; quarter circle right
```
<!-- /example-source -->

Two quarter-circles; negative angle flips the direction.

## Variations

{{example: arc-h-extrude}}

<!-- example-source: arc-h-extrude -->
```clojure
(register banked-tube (extrude (circle 5) (arc-h 20 180)))
```
<!-- /example-source -->

Inside `extrude`, the arc becomes a smoothly curved swept tube.

{{example: arc-h-explicit-steps}}

<!-- example-source: arc-h-explicit-steps -->
```clojure
(arc-h 10 90 :steps 32)    ;; 32 segments instead of the resolution default
```
<!-- /example-source -->

`:steps` overrides the resolution-derived count when smoothness matters
more than the global setting.

## Notes

- The pen draws the construction line along the arc when `:on`.
- `arc-h` rotates around the **up** axis — same axis as `th`. To curve
  in the vertical plane, use `arc-v`.

## See also

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `arc-v`, `th`, `bezier-to`, `resolution`
