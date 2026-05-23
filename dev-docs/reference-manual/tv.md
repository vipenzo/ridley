---
name: tv
category: turtle-movement
since: ""
status: stable
---

# tv

## Signature

`(tv angle)`

## Description

Pitch rotation: turn the turtle around its **right** axis (`heading × up`)
by `angle` degrees. The turtle's position is unchanged; heading and up
rotate together. **Modifies turtle state.**

Inside `path` and extrusion macros, `tv` records a pitch segment that
bends subsequent `f` segments out of the current plane.

## Parameters

- `angle` — pitch in degrees. Positive = pitch up (heading rotates from
  the current heading toward the current up vector).

## Example

{{example: tv-basic}}

<!-- example-source: tv-basic -->
```clojure
(f 20)              ;; walk forward
(tv 90)             ;; pitch up 90° — heading now points along old up
(f 20)              ;; vertical segment
```
<!-- /example-source -->

A 90° pitch up changes a horizontal walk into a vertical one.

## Variations

{{example: tv-in-extrude}}

<!-- example-source: tv-in-extrude -->
```clojure
(register elbow (extrude (circle 5) (f 20) (tv 45) (f 20)))
```
<!-- /example-source -->

Inside `extrude`, `tv` bends the swept path in the vertical plane.

## Notes

- `tv` is an **intrinsic** rotation (around the current right axis).
  Combinations of `th` and `tv` accumulate roll in the local frame; see
  `(turtle :preserve-up …)` to cancel that drift in long sequences.
- For yaw (around up), use `th`. For roll (around heading), use `tr`.

## See also

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `th`, `tr`, `f`, `arc-v`, `turtle` (`:preserve-up`)
