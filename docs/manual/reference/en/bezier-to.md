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
`(bezier-to target :preserve-heading)`
`(bezier-to target :preserve-heading :tension 0.5)`

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

With no control points, the curve normally arrives along the
start→end **chord**, so the turtle's heading rotates to face the target.
The `:preserve-heading` flag instead makes the curve arrive **tangent to
the current heading**, leaving the heading unchanged — a following
`(f …)` (or any movement) welds on without a cusp. `:tension` (default
`0.33`) sets how far the control points extend, controlling the curve's
width.

Inside `path` and extrusion macros, `bezier-to` becomes a recorded
smooth segment.

## Parameters

- `target` — `[x y z]` world position (or local, with `:local`).
- `ctrl`, `ctrl-1`, `ctrl-2` — control points (`[x y z]`).
- `:steps` — number of segments along the curve. Defaults to the
  resolution setting.
- `:local` — flag: interpret `target` and control points in the
  turtle's local `[right up heading]` frame instead of world space.
- `:preserve-heading` — flag (no control points only): the curve arrives
  tangent to the current heading, leaving the turtle's heading unchanged.
- `:tension` — with `:preserve-heading`, control point distance factor
  (default `0.33`); larger values give a wider belly.

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

{{example: bezier-to-preserve-heading}}

<!-- example-source: bezier-to-preserve-heading -->
```clojure
(bezier-to [20 30 0] :preserve-heading)   ;; arrives tangent to current heading
(f 10)                                     ;; welds on with no cusp
```
<!-- /example-source -->

`:preserve-heading` keeps the turtle pointing the same way through the
curve, so the next segment continues smoothly — ideal for closing a
profile where one side is curved and the rest are straight.

## Notes

- The turtle's heading at the start of the segment is honoured (the
  auto-generated control point preserves tangency).
- With no control points, the heading at the **end** normally rotates to
  the start→end chord; pass `:preserve-heading` to keep it unchanged.
- For curves that connect to a named anchor with smooth tangency, use
  `bezier-to-anchor`.

## See also

- **Related:** `bezier-to-anchor`, `bezier-as`, `arc-h`, `arc-v`,
  `resolution`, `edit-bezier`
