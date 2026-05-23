---
name: pen
category: turtle-movement
since: ""
status: stable
---

# pen / pen-up / pen-down

## Signature

`(pen :off)`
`(pen :on)`
`(pen-up)`
`(pen-down)`

## Description

Toggle the turtle's pen state. With the pen "on" every movement
command (`f`, `arc-h`, `arc-v`, `bezier-to`, …) draws a construction
line between the previous and the new position. With the pen "off", the
turtle moves silently without drawing.

`pen-up` is an alias of `(pen :off)`; `pen-down` is an alias of
`(pen :on)`. **Modifies turtle state.**

The visibility of construction lines is also controlled globally by
`show-lines` / `hide-lines` (a viewport toggle that does not affect the
underlying pen state).

## Parameters

- `:off` / `:on` — for the `pen` form.

## Example

{{example: pen-basic}}

<!-- example-source: pen-basic -->
```clojure
(pen :off)         ;; stop drawing
(f 20)             ;; turtle moves silently
(pen :on)          ;; resume drawing
(f 20)             ;; this segment draws
```
<!-- /example-source -->

Skip the construction line for an intermediate move.

## Variations

{{example: pen-aliases}}

<!-- example-source: pen-aliases -->
```clojure
(pen-up)
(f 20)
(pen-down)
(f 20)
```
<!-- /example-source -->

Same behaviour as `(pen :off)` / `(pen :on)`. Use whichever reads
better.

## Notes

- The pen state is a turtle property; nested `turtle` scopes inherit
  the parent state.
- To toggle the **visibility** of construction lines without changing
  the pen state, use `show-lines` / `hide-lines`.

## See also

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `show-lines`, `hide-lines`, `lines-visible?`, `f`,
  `bezier-to`
