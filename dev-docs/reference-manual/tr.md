---
name: tr
category: turtle-movement
since: ""
status: stable
---

# tr

## Signature

`(tr angle)`

## Description

Roll rotation: turn the turtle around its **heading** axis by `angle`
degrees. The position and heading are unchanged; the up vector rotates
in the plane perpendicular to the heading. **Modifies turtle state.**

Roll affects how subsequent extrusions or stamps orient their 2D
sections in the plane orthogonal to the heading. Common use: rotate the
"up" reference before `extrude` so the profile lands at the desired
rotation around the path.

## Parameters

- `angle` — roll in degrees. Positive = right-hand rule around heading.

## Example

{{example: tr-basic}}

<!-- example-source: tr-basic -->
```clojure
(register bar (extrude (rect 20 5) (f 20)))
(tr 90)
(register bar-rotated (extrude (rect 20 5) (f 20)))
;; same shape extruded along the same heading, but rolled 90°
```
<!-- /example-source -->

Two bars along the same path; the second is rolled 90° around the
heading so its cross-section is rotated in the section plane.

## Notes

- `tr` works normally even inside `(turtle :preserve-up …)`: it sets a
  deliberate roll relative to the corrected up vector.
- Inside `path` and extrusion macros, `tr` becomes part of the recorded
  trajectory.

## See also

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `th`, `tv`, `f`, `turtle`
