---
name: mesh-board
category: mesh-operations
since: ""
status: stable
---

# mesh-board

## Signature

`(mesh-board t)`
`(mesh-board t {:only [:piece-2 :piece-3]})`
`(mesh-board reference candidate)`
`(mesh-board reference candidate {:mode :overlay|:intersection|:diff :label "…"})`

## Description

Display mesh scaffold(s) in the viewport — ghost-wireframe, in place, never
part of the scene registry. `mesh-board` is a display DIRECTIVE, not a
transformation: it always returns its **first argument unchanged**, so it
composes cleanly in a threading pipeline (`(-> t (attach (f 10)) (mesh-board))`)
without altering what the rest of the program computes with. Scaffold
geometry is never named, never pickable, and never included in export — it
exists purely for the viewport.

Two forms:

- **Show** — `(mesh-board t)` renders every leaf of `t` as scaffold. `t` may
  be a map (name → mesh — the body a `mesh-split`-driven decomposition emits),
  a vector (an older emission, pre-map — shown, but without names), or a
  single mesh (shown whole). `{:only [...]}` restricts a map input to the
  named subset; it's an error on a vector or single-mesh input, and an error
  naming any requested name that doesn't exist.
- **Compare** — `(mesh-board reference candidate)` shows the reference as
  scaffold, plus a second scaffold that depends on `:mode`: `:overlay`
  (default) shows the candidate itself, superimposed; `:intersection` shows
  only their common volume; `:diff` shows the symmetric difference — where
  the candidate deviates from the reference. In every mode, evaluation prints
  the **fidelity** (a symmetric-difference-based percentage — 100% for
  identical solids), e.g. `mesh-board: riferimento vs candidato — fedeltà
  97.3%`. An optional `:label` appears in the message, to tell apart several
  comparisons in the same program.

Like `stamp`, scaffolds live in a per-evaluation accumulator: a full
evaluation replaces the whole set, an incremental REPL evaluation appends to
it. Every `mesh-board` call in a program contributes independently — remove
one call and re-evaluate, and only its scaffolds disappear.

## Parameters

- `t` — a map of meshes (keys = piece names), a vector of meshes, or a single
  mesh.
- `:only` — (show form, map input only) a vector of keys to restrict which
  pieces are shown.
- `reference`, `candidate` — meshes (or SDF nodes) to compare.
- `:mode` — `:overlay` (default), `:intersection`, or `:diff`.
- `:label` — a string that appears in the printed fidelity message.

## Example

{{example: mesh-board-show}}

<!-- example-source: mesh-board-show -->
```clojure
(register piece-1 (box 10 10 10))
(register piece-2 (attach (box 6 6 6) (f 20)))
(mesh-board {:piece-1 piece-1 :piece-2 piece-2})
```
<!-- /example-source -->

Both pieces appear as ghost-wireframe scaffolds, in place — neither is named
or pickable; `(register …)` is what makes the real, solid geometry visible
and CSG-able alongside them.

{{example: mesh-board-compare}}

<!-- example-source: mesh-board-compare -->
```clojure
(register original (box 10 10 10))
(register candidate (box 10 10 12))   ; a bit too tall
(mesh-board original candidate {:mode :diff :label "piece-1"})
```
<!-- /example-source -->

Prints the fidelity and renders the symmetric-difference volume as scaffold
— the sliver where `candidate` overshoots `original`.

## Notes

- **Pass-through, always.** `mesh-board` never alters its first argument —
  `(mesh-board t)` and `(mesh-board reference candidate …)` both return
  exactly what they were given (`reference`, for the compare form). This is
  what keeps the language guarantee structural: since a scaffold is never
  returned into the value the rest of the program computes with, it can never
  reach a boolean operation or export through ordinary code.
- Toggle scaffold visibility globally with the "Boards" button in the
  viewport toolbar (view state — it does not touch the program, and there is
  no `:off` form in the language: presence in the source is the only switch
  the language itself offers).
- Scaffolds are measurable (shift+click) like any other viewport geometry,
  but excluded from structural pick / gizmo snap — read-only, by
  construction.
- The pieces of a decomposition tree remain ordinary, CSG-able mesh data —
  `mesh-board` puts nothing between them and `mesh-union` / `mesh-difference`
  / `mesh-intersection`. The citizenship guarantee is the pass-through, not a
  type restriction.
- Fidelity reuses the same symmetric-difference machinery as `mirror?`
  (union/intersection volumes), generalized from a self-mirror comparison to
  two arbitrary meshes.

## See also

- **Related:** `attach` (now accepts a map/vector of meshes as a rigid
  group), `mesh-components`, `mesh-union`, `mesh-difference`,
  `mesh-intersection`, `mirror?`, `stamp`
