---
name: f
category: turtle-movement
since: ""
status: stable
---

# f

## Signature

`(f dist)`

## Description

Move the turtle forward by `dist` along its current heading. Negative
values move backward. The pen state determines whether a construction
line is drawn between the previous and the new position; pose
(heading / up) is unchanged. **Modifies turtle state.**

Inside `path` and the extrusion macros (`extrude`, `loft`,
`revolve+`), `f` adds a segment to the recorded path; the same call
that walks the turtle at top level also drives the geometry generation
inside those contexts.

## Parameters

- `dist` — signed distance along the heading. Positive forward, negative
  backward.

## Example

{{example: f-basic}}

<!-- example-source: f-basic -->
```clojure
(f 20)            ;; walk forward 20 units
(f -10)           ;; step back 10 units
```
<!-- /example-source -->

Two consecutive `f` calls advance and then retreat along the same axis.

## Variations

{{example: f-in-extrude}}

<!-- example-source: f-in-extrude -->
```clojure
(register tube (extrude (circle 8) (f 30)))
```
<!-- /example-source -->

Inside `extrude`, `f` defines the length of the swept path. Used
alongside `th` / `tv` / `tr` it builds composite paths in a single
expression.

## Notes

- The construction line drawn by `f` is part of the pen output. Toggle
  globally with `(pen :off)` / `(pen :on)` (or the `pen-up` / `pen-down`
  aliases).
- For pure translation that does not change heading and does not advance
  along the heading axis, use `u` / `d` / `rt` / `lt`.

## See also

- **Related:** `u`, `d`, `rt`, `lt`, `th`, `tv`, `tr`, `arc-h`,
  `bezier-to`, `pen`
