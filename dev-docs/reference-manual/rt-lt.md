---
name: rt
category: turtle-movement
since: ""
status: stable
---

# rt / lt

## Signature

`(rt dist)`
`(lt dist)`

## Description

Pure translation along the turtle's local **right** axis
(`heading × up`). No heading change, no ring generation, no pen line.
`(rt dist)` moves along `+right`; `(lt dist)` moves in the opposite
direction. **Modifies turtle state** (position changes; heading and up
are unchanged).

Lateral movements are blocked inside `path`, `extrude`, and `loft` —
they would produce degenerate rings. Allowed at top level, inside
`attach` / `attach!`, and in animation spans.

Don't confuse `rt` / `lt` with turn commands: they are translations, not
rotations. For yaw, use `th`.

## Parameters

- `dist` — distance along the right axis. Positive for `rt`, opposite
  direction for `lt`.

## Example

{{example: rt-lt-basic}}

<!-- example-source: rt-lt-basic -->
```clojure
(f 20)               ;; walk forward
(rt 10)              ;; sidestep right 10
(register knob (sphere 5))
(lt 5)               ;; sidestep left 5
```
<!-- /example-source -->

Lateral slide along the right axis — handy for placing parallel
elements at a fixed offset.

## Variations

{{example: rt-lt-in-attach}}

<!-- example-source: rt-lt-in-attach -->
```clojure
(register gear (sphere 5))
(attach! :gear (rt 10))   ;; slide the gear 10 units right
```
<!-- /example-source -->

Inside `attach` / `attach!`, lateral movements translate the attached
mesh without ring side-effects.

## Notes

- `rt` / `lt` change only the **position**, not the heading.
- For turns around up (yaw), use `th`. For pitch around right, use `tv`.

## See also

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `f`, `u`, `d`, `th`, `attach`, `attach!`
