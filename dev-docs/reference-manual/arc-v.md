---
name: arc-v
category: turtle-movement
since: ""
status: stable
---

# arc-v

## Signature

`(arc-v radius angle)`
`(arc-v radius angle & {:keys [steps]})`

## Description

Move the turtle along a circular arc in the vertical plane: both the
position and the heading change so the turtle traces a smooth curve.
The rotation axis is the **right** vector (`heading × up`). **Modifies
turtle state.**

Step count defaults to the current resolution; pass `:steps` for an
explicit override. Inside `path` and extrusion macros, `arc-v` records
a smooth recorded path.

## Parameters

- `radius` — arc radius. Positive for an "up" curve, negative for a
  "down" curve (or use a negative `angle`).
- `angle` — total turn in degrees. Positive = pitch up (heading rotates
  toward up).
- `:steps` — number of segments along the arc. Defaults to the
  resolution setting.

## Example

{{example: arc-v-basic}}

<!-- example-source: arc-v-basic -->
```clojure
(arc-v 10 90)         ;; quarter arc pitching up
(arc-v 10 -90)        ;; quarter arc pitching down
```
<!-- /example-source -->

Two quarter-circles in the vertical plane; negative angle flips the
direction.

## Variations

{{example: arc-v-extrude}}

<!-- example-source: arc-v-extrude -->
```clojure
(register hook (extrude (circle 4) (f 10) (arc-v 15 180)))
```
<!-- /example-source -->

Inside `extrude`, the vertical arc becomes a hook-like swept tube.

## Notes

- `arc-v` rotates around the **right** axis — same axis as `tv`. To
  curve horizontally, use `arc-h`.

## See also

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `arc-h`, `tv`, `bezier-to`, `resolution`
