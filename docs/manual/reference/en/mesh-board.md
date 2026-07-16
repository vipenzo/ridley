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
`(mesh-board reference candidate {:views [:intersection :missing :excess] :ghost false :label "…"})`

## Description

Display or compare mesh scaffold(s) — never part of the scene registry,
never named, never pickable, never included in export. `mesh-board` is a
display DIRECTIVE, not a transformation: it always returns its **first
argument unchanged**, so it composes cleanly in a threading pipeline
(`(-> t (attach (f 10)) (mesh-board))`) without altering what the rest of
the program computes with.

Two forms:

- **Show** — `(mesh-board t)` renders every leaf of `t` as an in-place
  ghost-wireframe scaffold, never part of the scene registry. `t` may be a
  map (name → mesh — a `let`-chain emission's body, or `(split-tree …)` over
  a bare `mesh-split` call), a vector (an older emission, pre-map — shown,
  but without names), or a single mesh (shown whole). `{:only [...]}`
  restricts a map input to the named subset; it's an error on a vector or
  single-mesh input, and an error naming any requested name that doesn't
  exist.

  A **raw `mesh-split` composite** is refused with an error naming
  `split-tree` — its `:behind`/`:ahead` are not piece names, and taking them
  for names would display an honest-looking two-piece board for a one-cut
  composite and break for anything deeper. `(mesh-board (split-tree AA))`.
- **Compare** — `(mesh-board reference candidate opts)` opens one small
  picture-in-picture window per requested view, each showing a **solid**
  render (not wireframe — a solid reads the deviation's shape at a glance)
  of a directional boolean between `reference` and `candidate`:
  - `:intersection` — their common volume.
  - `:missing` — `reference − candidate`: the material the candidate doesn't
    cover yet (what's still missing).
  - `:excess` — `candidate − reference`: where the candidate overshoots the
    reference (what's extra).

  `:missing` and `:excess` are two **directional** diffs, not one merged
  symmetric difference — for iterating on a candidate against a reference
  they're complementary, distinct signals, so both stay visible at once by
  default. Each window shows the view's solid result alongside a
  ghost-wireframe of `reference` (spatial anchoring — which part of the
  object the diff is on) and a header with the view name and its **volume**
  (e.g. `missing: 12.3 mm³`) — more actionable than a bare percentage. When a
  view's result is empty (the pieces don't overlap yet, for `:intersection`),
  the window shows just the reference wireframe with an explicit `vuoto`
  label — never stale content from a previous, non-empty evaluation. Camera
  framing is fit to `reference`'s bounding box, so it stays stable across
  re-evaluations while only `candidate` changes; orientation follows the main
  viewport as you rotate it. Windows are picture-in-picture: they float over
  the viewport, independent of where `reference`/`candidate` sit in the scene
  (unlike the show form's in-place scaffolds). Each window can be
  **repositioned** (drag its header) and **zoomed** (scroll wheel over it) —
  view state, not saved in the source; a reload restarts from the default
  stacked-column layout.

  Every compare call also prints the **fidelity** (a symmetric-difference-
  based percentage — 100% for identical solids), e.g. `mesh-board:
  riferimento vs candidato — fedeltà 97.3%`, in the app's output panel. An
  optional `:label` appears in the message and disambiguates the windows of
  several comparisons coexisting in the same program — without it, two
  concurrent compare calls share one default group of windows.

Like `stamp`, in-place scaffolds (show form, and the compare form's optional
`:ghost`) live in a per-evaluation accumulator: a full evaluation replaces
the whole set, an incremental REPL evaluation appends to it. Every
`mesh-board` call in a program contributes independently — remove one call
and re-evaluate, and only its scaffolds disappear. Comparison windows follow
the same rule at the call level: a full evaluation drops a removed call's
windows; re-evaluating with fewer `:views` unmounts the ones no longer
requested.

## Parameters

- `t` — a map of meshes (keys = piece names), a vector of meshes, or a single
  mesh.
- `:only` — (show form, map input only) a vector of keys to restrict which
  pieces are shown.
- `reference`, `candidate` — meshes (or SDF nodes) to compare.
- `:views` — (compare form) a vector of `:intersection`/`:missing`/`:excess`;
  default all three. Reduce it to skip the cost of boolean ops you don't
  need on dense meshes.
- `:ghost` — (compare form) when `true`, also overlays `reference` and
  `candidate` in place as ghost-wireframe scaffolds (grey/blue, one color per
  role) — useful for coarse initial positioning. Default `false`.
- `:label` — a string that appears in the printed fidelity message and
  disambiguates a comparison's windows from a concurrent one.

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
(mesh-board original candidate {:views [:excess] :label "piece-1"})
```
<!-- /example-source -->

Prints the fidelity and opens one window showing `excess` — the sliver where
`candidate` overshoots `original`, with its volume in the header.

## Notes

- **Pass-through, always.** `mesh-board` never alters its first argument —
  `(mesh-board t)` and `(mesh-board reference candidate …)` both return
  exactly what they were given (`reference`, for the compare form). This is
  what keeps the language guarantee structural: since a scaffold is never
  returned into the value the rest of the program computes with, it can never
  reach a boolean operation or export through ordinary code.
- Toggle in-place scaffold visibility globally with the "Boards" button in
  the viewport toolbar (view state — it does not touch the program, and
  there is no `:off` form in the language: presence in the source is the
  only switch the language itself offers). Each comparison window has its
  own collapse toggle in its header.
- Scaffolds are measurable (shift+click) like any other viewport geometry,
  but excluded from structural pick / gizmo snap — read-only, by
  construction. Comparison windows are picture-in-picture previews, not part
  of the measurable scene — their only interactions are dragging to
  reposition and the scroll wheel to zoom, both scoped to the window itself
  (rotation stays synced to the main viewport, and the drag/zoom never
  reaches the main viewport underneath).
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
