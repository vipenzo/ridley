---
name: edit-mesh-split
category: live-interactive
since: ""
status: stable
---

# edit-mesh-split

## Signature

`(edit-mesh-split mesh)`
`(edit-mesh-split mesh path)`
`(edit-mesh-split mesh path marks-vector)`

## Description

Decompose a mesh into pieces interactively — a **tree session** for
`mesh-split`. Run it from the **definitions panel** (Cmd+Enter). Unlike
a straight guillotine, both halves of every cut become pieces of a
growing tree: you can go back to any piece and keep cutting it, or
separate a piece into its connected components without any plane at
all. The goal is a decomposition where **every piece is finished** —
each of its connected components convex.

**The turtle is the cut plane** — position and heading define it
directly. Arrows move/rotate the live pose; a semi-transparent quad
renders the plane, with a small cone along `+heading` into `:ahead` —
`Enter` cuts, keeping the *opposite* side `:behind` as the near piece
(same convention as `extrude`, material trails behind). You can also
drag the plane with the same handle gizmo `edit-attach` uses (translate
arrows + rotation rings). The plane tracks the pointer every frame; the
pieces never rigidly move with it.

**The current piece** is the one the plane is cutting — its live
`:behind`/`:ahead` split is shown solid/washed. Every OTHER piece is on
screen too: still-open pieces as solid blue bodies, finished pieces as
grey wireframes. **Click any piece** — or press **n** — to make it
current (the plane jumps to its middle).

**Live semaphore (per-component).** A piece is **finished** (green)
when *every* connected component is convex — so a piece that is one
convex solid is finished, and so is a piece that is several convex
solids stuck together in one mesh (a U cut at its base → two convex
prongs). Such a piece needs **separating, not cutting**: a `N pieces`
badge flags it, and pressing **s** splits it into its components (Part 1's
`mesh-components`, no plane). A piece with a genuinely concave component
is **red** and needs a plane. The current cut's two live halves are
tinted the same way (green finished / red concave), `:behind` solid,
`:ahead` washed; a status line quantifies both by volume percentage,
e.g. `behind 42% (convex) — ahead 58% (2 pieces)`.

**Gestures.**

- **Enter** — cut the current piece (both halves join the tree). When
  *every* piece is finished, `Enter` instead **commits**. When the
  plane can't cut here and work remains elsewhere, it moves on to the
  next open piece.
- **s** — separate the current piece into its connected components.
- **n** / click — select the next / a specific piece as current.
- **Backspace** — undo the last structural gesture (cut *or*
  separation), whatever branch it touched — a single chronological
  history, freeing that piece's live Manifold as it goes.
- **Ctrl/Cmd+Enter** — commit now, even with concave pieces still open.
- **Esc** — cancel the whole session, emit nothing.

Each open piece keeps its Manifold alive, so re-splitting it on every
keystroke costs the split alone, not a fresh mesh→manifold conversion.

**On confirm**, the marker is rewritten to a `let`-chain of
self-contained linear `mesh-split` composites — one per branch, each
with its own path and its own path-scoped marks — plus a
`mesh-components` destructure for each separation. The tree shape lives
in which binding feeds which call:

```clojure
(let [{piece-1 :behind piece-2 :ahead}
      (mesh-split (get-mesh :block)
                  (path (f 10) (mark :cut-1))
                  [:cut-1])
      [piece-3 piece-4] (mesh-components piece-2)]
  [piece-1 piece-3 piece-4])
```

The keystroke transcript is never what's emitted — each branch's path
resolves from the session's entry pose, and for each cut the tool
synthesizes the minimal canonical `(th …)(tv …)(tr …)(f …)(rt …)(u …)`
delta. Numbers are never snapped to a grid.

## Parameters

- `mesh` — the mesh (or keyword name, or SDF node) to decompose.
- `path`, `marks-vector` (optional) — re-entry: reopen an
  already-emitted `mesh-split` call for further editing (rename that one
  `mesh-split` → `edit-mesh-split`; the rest of the `let` is untouched).
  Because every emitted call is a self-contained linear composite,
  re-entry needs no special machinery — the session tree is rebuilt from
  the evaluated composite, and re-entering then immediately closing
  reproduces the same call.

## Example

{{example: edit-mesh-split-basic}}

<!-- example-source: edit-mesh-split-basic -->
```clojure
(register block (extrude (rect 20 20) (f 30)))
(edit-mesh-split (get-mesh :block))
```
<!-- /example-source -->

## Notes

- A tree, not a straight guillotine and not a general BSP: each piece is
  produced by exactly one cut or one separation, and undo is
  chronological over those gestures. To re-cut a piece already emitted as
  a leaf, wrap its binding in a fresh `mesh-split` (re-enter that call).
- `s` (separate) applies to multi-component pieces only; on a single
  component it's a no-op with a note — that piece needs a plane.
- Keymap mirrors `edit-attach`'s step/angle scheme: `Tab` cycles step ↔
  angle, digits set the active value, arrows move (`f`/`rt`) or rotate
  (`tv`/`th`), Shift+arrows move vertically (`u`) or roll (`tr`). The
  grid also governs the gizmo snap; Shift+drag bypasses it.

## See also

- **Related:** `mesh-split`, `mesh-components`, `split-parts`, `convex?`, `edit-attach`
