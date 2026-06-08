---
name: bezier-to
category: turtle-movement
since: ""
status: stable
---

# bezier-to

## Signature

`(bezier-to target)`
`(bezier-to target & {:keys [steps]})`
`(bezier-to target ctrl)`
`(bezier-to target ctrl-1 ctrl-2)`
`(bezier-to target ctrl-1 ctrl-2 :local)`

## Description

Move the turtle to `target` along a smooth bezier curve, updating
heading at each step to remain tangent to the curve. **Modifies turtle
state.**

Four forms cover increasing degrees of control:

- **One target** — quadratic bezier with an auto-generated control
  point that starts tangent to the current heading.
- **Target + one control point** — explicit quadratic bezier.
- **Target + two control points** — cubic bezier.

`:steps` overrides the resolution-derived step count.

By default `target` and the control points are **world coordinates**.
The `:local` flag reads them in the turtle's local `[right up heading]`
frame (origin = current turtle position), making the call
pose-independent. This is the form `edit-bezier` emits.

Inside `path` and extrusion macros, `bezier-to` becomes a recorded
smooth segment.

## Parameters

- `target` — `[x y z]` world position (or local, with `:local`).
- `ctrl`, `ctrl-1`, `ctrl-2` — control points (`[x y z]`).
- `:steps` — number of segments along the curve. Defaults to the
  resolution setting.
- `:local` — flag: interpret `target` and control points in the
  turtle's local `[right up heading]` frame instead of world space.

## Example

{{example: bezier-to-basic}}

<!-- example-source: bezier-to-basic -->
```clojure
(bezier-to [20 0 10])      ;; quadratic, control point inferred from heading
```
<!-- /example-source -->

The simplest form — let the turtle figure out the control point from
its current heading.

## Variations

{{example: bezier-to-quadratic}}

<!-- example-source: bezier-to-quadratic -->
```clojure
(bezier-to [20 0 10] [10 0 20])     ;; explicit quadratic
```
<!-- /example-source -->

One control point gives a quadratic curve with explicit shape control.

{{example: bezier-to-cubic}}

<!-- example-source: bezier-to-cubic -->
```clojure
(bezier-to [40 0 0] [10 0 20] [30 0 20])     ;; cubic bezier
```
<!-- /example-source -->

Two control points give a cubic — the full degree of freedom for
shaping a single segment.

{{example: bezier-to-explicit-steps}}

<!-- example-source: bezier-to-explicit-steps -->
```clojure
(bezier-to [40 0 20] :steps 64)
```
<!-- /example-source -->

`:steps` overrides the resolution-derived count when smoothness matters
more than the global setting.

{{example: bezier-to-local}}

<!-- example-source: bezier-to-local -->
```clojure
(bezier-to [0 0 40] [10 0 15] [10 0 25] :local)   ;; local [right up heading] frame
```
<!-- /example-source -->

`:local` makes the curve follow the turtle's pose — useful when the
same curve should work wherever the turtle is placed.

## Notes

- The turtle's heading at the start of the segment is honoured (the
  auto-generated control point preserves tangency).
- For curves that connect to a named anchor with smooth tangency, use
  `bezier-to-anchor`.

## See also

- **Related:** `bezier-to-anchor`, `bezier-as`, `arc-h`, `arc-v`,
  `resolution`, `edit-bezier`
