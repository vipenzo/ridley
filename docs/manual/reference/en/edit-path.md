---
name: edit-path
category: live-interactive
since: ""
status: stable
---

# edit-path

## Signature

`(edit-path)`
`(edit-path (f d) (tv α) (f d) …)`

(This edits a **3D rail**. To trace a **planar 2D profile** over a reference image,
use the separate **edit-path-2d** instead.)

## Description

An interactive editor for a **3D rail** — a `path` consumed in its own frame by
`extrude`-along-path and `loft`, not a flat profile. It wraps a plain `(path …)` body
and opens a session from the **definitions panel** (Cmd+Enter), not the REPL. The
rail is a chain of nodes in 3D space; node 0 (the **anchor**) is pinned at the origin,
and the rest are placed/edited in a selectable working plane.

`edit-path` dispatches on the body's **path species**: a plain `(path …)` body
(species `:3d`) opens this 3D rail editor; a `(path-2d …)` body opens the planar
`edit-path-2d`. On confirm the `(edit-path …)` marker is rewritten to a baked
`(path …)` — relative `set-heading`/`f` segments plus `bezier-to` curves — so
re-running the script does **not** re-enter editing. To edit again, **rename
`path` → `edit-path`**.

## Parameters

- `body` — an optional `(path …)` body to seed the rail (straight segments and
  bezier curves). Empty opens a minimal default rail with the anchor at the origin.

## Working planes

A 3D point can't be placed unambiguously with a 2D pointer, so edits happen in a
**selectable working plane** of the turtle frame, named by its normal (radio buttons
in the panel, or the keys `f` / `r` / `u`):

- `f` (default) — ⊥ heading, i.e. the `(right, up)` plane (the same plane
  `edit-path-2d` traces in).
- `r` — ⊥ right, i.e. the `(heading, up)` plane.
- `u` — ⊥ up, i.e. the `(heading, right)` plane.

Switch plane to move a node along the third axis the previous plane could not reach.

## Mouse & keys

- **Click** — add a node on the active plane (through the pose); **click a segment**
  splits it there.
- **Drag a node** — move it in the active plane (screen-space grab; its depth is kept
  ⊥ the active-plane normal). **Shift+drag** locks the move to a single axis.
- `Tab` — cycle the selected node. **Arrows** nudge it in the active plane; the
  panel's numeric **len** / **angle** fields set the selected segment precisely (per
  active plane).
- `c` — toggle the incoming segment between a **straight line** and a **free cubic
  bezier**. The two handles (square markers) are **free** in 3D (no tangent re-snap).
  `Shift`/`Alt`+arrows nudge the handles; **Shift+drag a handle** is length-only (it
  slides along its direction from the node, escaping the plane).
- `t` — **raccordo**: turn the corner into a both-ends-tangent bezier — smooth on the
  incoming heading *and* toward the next node. Re-press to re-fit after moving a
  neighbour.
- `Insert` (or `i`) — split the segment entering the selected node (de Casteljau at
  t=0.5 on a bezier, so the curve's shape is preserved).
- `m` — add / rename a **mark** on the node (it renders **green** and is protected
  from deletion); `Shift+m` toggles the floating mark labels. A mark on the **rail**
  becomes a mesh **anchor** at the centerline, reachable with `(on-anchors tube :name …)`.
- `Delete` — remove the node. `Cmd`/`Ctrl`+`Z` — undo. `Enter` — OK; `Esc` — cancel.

## The bake — a twist-free rail

Straight segments bake as **relative** `set-heading` / `f`, so the rail composes under
any consuming pose and the swept section stays **twist-free** along it (a
rotation-minimizing frame, not a global up). Bezier segments bake as
`(bezier-to [end] [c1] [c2] :local)` and tessellate at eval time with their own
rotation-minimizing frame, so an out-of-plane curve has a continuous section `up`
(no pinch / roll at the seam).

## Example

<!-- example-source: edit-path-rail :no-run -->
```clojure
;; Edit a 3D rail interactively, then sweep a circular profile along it.
(register pipe (extrude (circle 4) (edit-path (f 40) (tv 35) (f 40))))
```
<!-- /example-source -->

Open from the definitions panel, adjust the rail (switch planes with `f`/`r`/`u`,
curve a corner with `c`/`t`), confirm, and the `(edit-path …)` marker becomes a plain
`(path …)` that the `extrude` sweeps the circle along.

## Notes

- **Holonomy twist.** A baked rail is twist-free, but `extrude` of a **non-planar**
  rail can still accumulate a section roll around its curvature (holonomy). If a tube
  comes out twisted, wrap the rail in `ensure-untwisted` —
  `(extrude (circle 4) (ensure-untwisted rail))` — the manual remedy.
- **Re-edit.** Beziers round-trip **exactly** (a `:pure` tag recovers the single
  node); a hand-written `arc-h` opened here is converted to a bezier on input. A 3D
  arc that was baked as a tessellated `set-heading` polyline recovers as straight
  segments, not a single arc node (a known limit).
- **Cusps are 2D-only** — 3D bezier handles are already free, so there is no `x`.
- **Not a persistent primitive.** Confirm bakes `(path …)`; re-running does not
  re-open editing. Rename `path` → `edit-path` to edit again.
- **Modal session.** Like `tweak` / `edit-bezier` / `edit-path-2d`, one runs at a
  time and the editor is read-only while open; switching workspace closes the session.
- Does **not** need a reference image — clicks land on the working plane.

## See also

- **Related:** `edit-path-2d`, `path`, `set-heading`, `ensure-untwisted`, `extrude`,
  `loft`, `bezier-to`, `move-to`
