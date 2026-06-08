---
name: edit-bezier
category: live-interactive
since: ""
status: stable
---

# edit-bezier

## Signature

`(edit-bezier)`
`(edit-bezier :shape)`
`(edit-bezier :wireframe)`
`(edit-bezier end ctrl-1 ctrl-2)`

## Description

Author a cubic Bezier curve interactively, in 3D, from the keyboard —
instead of solving the cubic by hand for its control points. `edit-bezier`
is a stand-in for a `(bezier-to … :local)` call and is used **wherever
`bezier-to` is**: top-level, or inside `(path …)` / `(attach …)`. Run it
from the **definitions panel** (Cmd+Enter), not the REPL.

While editing it draws a valid default curve so downstream operations
(`stroke-shape`, `extrude`, `register`) still run, and opens a modal
session. The start point P0 is the turtle pose at the call site — it is
never written to source and is recomputed on each eval. Three movable
points (the end and the two control points) are shown with the control
polygon and a live preview; the turtle indicator marks P0.

**On confirm**, the whole `(edit-bezier …)` marker is rewritten to the
edited call `(bezier-to [end] [c1] [c2] :local)`, expressed in P0's local
`[right up heading]` frame (pose-independent, round-trip identity).
**Cancel** leaves the source unchanged.

## Parameters

- `:shape` (alias `:as-shape-seed`) — author the curve as a 2D profile
  for `stroke-shape`. The overlay is drawn where the extruded
  cross-section will end up, so the handles line up with the result.
- `:wireframe` — nudges update only the ephemeral path; the downstream
  geometry is re-evaluated only on demand (Insert). Without it, the
  downstream re-evaluates live (debounced) on each nudge.
- `end`, `ctrl-1`, `ctrl-2` — optional initial points (in P0's local
  frame, as emitted on confirm) to re-open and edit an existing curve.

## Keys

- `Tab` — cycle the three movable points (end → ctrl1 → ctrl2).
- Arrows — move the selected point. 3D: `←`/`→` heading, `↑`/`↓` left,
  `Shift`+`↑`/`↓` up. `:shape` (planar): `←`/`→` length, `↑`/`↓` bow;
  `Shift` disabled.
- Digits — set the step size (mm); `Backspace` edits the buffer.
- `Insert` — force a downstream re-evaluation (useful with `:wireframe`).
- `Enter` — confirm; `Esc` — cancel.

## Example

<!-- example-source: edit-bezier-basic -->
```clojure
(extrude (circle 4)
  (edit-bezier))
```
<!-- /example-source -->

A keyboard-driven 3D curve, extruded along a circle.

## Variations

<!-- example-source: edit-bezier-shape -->
```clojure
(register wall
  (extrude (stroke-shape (path (edit-bezier :shape)) 3) (f 20)))
```
<!-- /example-source -->

`:shape` authors a 2D profile for `stroke-shape`, with the overlay
aligned to the extruded wall.

## Notes

- Reached once per eval (a limit inherited from the modal-evaluator
  pattern); not for use inside a loop or a multiply-called function.
- The mutex blocks `edit-bezier` from opening while `tweak` or `pilot`
  is active, and vice versa.

## See also

- **Related:** `bezier-to`, `bezier-as`, `tweak`, `pilot-request!`,
  `stroke-shape`
