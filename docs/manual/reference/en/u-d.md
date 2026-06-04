---
name: u
category: turtle-movement
since: ""
status: stable
---

# u / d (down)

## Signature

`(u dist)`
`(d dist)`
`(down dist)`

## Description

Pure translation along the turtle's local **up** axis. No heading
change, no ring generation, no pen line. `(u dist)` moves along `+up`;
`(d dist)` moves in the opposite direction; `down` is an alias of `d`.
**Modifies turtle state** (position changes; heading and up are
unchanged).

Lateral movements like this are blocked inside `path`, `extrude`, and
`loft` — they would produce degenerate rings. They are allowed at the
top level, inside `attach` / `attach!`, and inside animation spans.

## Parameters

- `dist` — distance along the up axis. Positive for `u`, treated as the
  opposite direction for `d` / `down`.

## Example

{{example: u-d-basic}}

<!-- example-source: u-d-basic -->
```clojure
(f 30)                  ;; walk forward
(u 15)                  ;; lift the turtle 15 units along its up axis
(register top (sphere 5))
(d 5)                   ;; come back down 5
(down 5)                ;; equivalent to (d 5)
```
<!-- /example-source -->

Lateral lift without changing where the turtle is pointing — useful for
stacking primitives at offsets.

## Variations

{{example: u-d-in-attach}}

<!-- example-source: u-d-in-attach -->
```clojure
(register gear (sphere 5))
(attach! :gear (u 10))    ;; slide the gear 10 units up
```
<!-- /example-source -->

Inside `attach` / `attach!`, lateral movements translate the attached
mesh without ring side-effects.

## Notes

- `u` / `d` change only the **position**, not the heading or up vector.
  Combine with `th` / `tv` / `tr` for orientation.
- Blocked inside path-building macros — call them only where pure
  translation is valid.

## See also

- **Related:** `f`, `rt`, `lt`, `attach`, `attach!`
