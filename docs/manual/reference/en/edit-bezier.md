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
`(edit-bezier path :at :mark)`
`(edit-bezier path :at :mark :symmetric)`

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

### Anchor / tension form

`(edit-bezier path :at :mark)` edits a curve whose **endpoints and tangent
directions are fixed by the path's marks** (start = the current pose, end =
the named mark). The only editable degrees of freedom are the two
control-point distances (the *tensions*); the handle directions stay locked
to the headings. This is the visual way to author a `bezier-to-anchor`
without guessing tensions — drag until the curve looks right. `:symmetric`
ties the two tensions into one (a single shared value), the natural choice
for symmetric corners.

The preview is the **live downstream geometry**: the marker draws a real
`bezier-to-anchor` call, so the extruded result reshapes as you change the
tension (no ephemeral control polygon is drawn). On confirm the marker is
rewritten to `(bezier-to-anchor path :at :mark :tension t)` (plus
`:tension-end` when asymmetric), with `path` kept as the original
expression.

## Parameters

- `:shape` (alias `:as-shape-seed`) — author the curve as a 2D profile
  for `stroke-shape`. The overlay is drawn where the extruded
  cross-section will end up, so the handles line up with the result.
- `:wireframe` — nudges update only the ephemeral path; the downstream
  geometry is re-evaluated only on demand (Insert). Without it, the
  downstream re-evaluates live (debounced) on each nudge.
- `end`, `ctrl-1`, `ctrl-2` — optional initial points (in P0's local
  frame, as emitted on confirm) to re-open and edit an existing curve.
- `path :at :mark` — anchor / tension form (see above): a path and one of
  its marks fix the endpoints and tangents; the session edits the tensions.
- `:symmetric` — in the anchor form, tie both tensions to one shared value.

## Keys

### Free 3-point form

- `Tab` — cycle the three movable points (end → ctrl1 → ctrl2).
- Arrows — move the selected point. 3D: `←`/`→` heading, `↑`/`↓` left,
  `Shift`+`↑`/`↓` up. `:shape` (planar): `←`/`→` length, `↑`/`↓` bow;
  `Shift` disabled.
- Digits — set the step size (mm); `Backspace` edits the buffer.
- `Insert` — force a downstream re-evaluation (useful with `:wireframe`).
- `Enter` — confirm; `Esc` — cancel.

### Anchor / tension form

- Arrows — `↑`/`→` raise the tension, `↓`/`←` lower it; `Shift` for a
  finer step.
- `Tab` — switch which handle the arrows drive (asymmetric only).
- `Insert` — force a downstream re-evaluation.
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

<!-- example-source: edit-bezier-anchor -->
```clojure
(def ps (path (mark :start) (f 45) (th 90) (f 45) (mark :end)))
(register supporto
  (extrude (stroke-shape (path (edit-bezier ps :at :end :symmetric)) 3) (f 10)))
```
<!-- /example-source -->

The anchor form rounds the corner of a path: the marks fix where the curve
starts and ends and the directions it leaves and arrives, and you drag a
single tension (`:symmetric`) until it bows the way you want — no cubic to
solve.

## Notes

- Reached once per eval (a limit inherited from the modal-evaluator
  pattern); not for use inside a loop or a multiply-called function.
- The mutex blocks `edit-bezier` from opening while `tweak` or `pilot`
  is active, and vice versa.

## See also

- **Related:** `bezier-to`, `bezier-as`, `tweak`, `pilot-request!`,
  `stroke-shape`
