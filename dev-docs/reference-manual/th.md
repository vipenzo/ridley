---
name: th
category: turtle-movement
since: ""
status: stable
---

# th

## Signature

`(th angle)`

## Description

Yaw rotation: turn the turtle around its **up** axis by `angle`
degrees. Positive values turn anticlockwise (looking down the up
vector). The turtle's position and up vector are unchanged; the
heading rotates in the plane perpendicular to up. **Modifies turtle
state.**

Inside `path` and extrusion macros, `th` records a yaw segment that
controls how subsequent `f` segments bend the path. With
`(joint-mode :flat)` (default) the corner is sharp; `:round` and
`:tapered` smooth or bevel it.

## Parameters

- `angle` — yaw in degrees. Positive = anticlockwise (CCW around up).

## Example

{{example: th-basic}}

<!-- example-source: th-basic -->
```clojure
(f 20)              ;; walk forward
(th 90)             ;; turn left 90°
(f 20)              ;; new heading
```
<!-- /example-source -->

Classic L-turn. `th` rotates the heading without moving the turtle.

## Variations

{{example: th-in-extrude}}

<!-- example-source: th-in-extrude -->
```clojure
(register bent-tube (extrude (circle 5) (f 20) (th 45) (f 20)))
```
<!-- /example-source -->

Inside `extrude`, `th` defines a corner along the swept path. The
current `joint-mode` controls how the geometry handles the turn.

## Notes

- `th` is an **intrinsic** rotation (around the current up axis).
  Repeated combinations of `th` + `tv` accumulate roll in the local
  frame — see `(turtle :preserve-up …)` if that becomes a problem.
- For pitch (around the right axis), use `tv`. For roll (around the
  heading), use `tr`.

## See also

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `tv`, `tr`, `f`, `arc-h`, `turtle` (`:preserve-up`),
  `joint-mode`
