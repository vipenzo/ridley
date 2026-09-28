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
`(mesh-board t {:solid true :opacity 0.35 :explode 30 :views [view …] :section at :anchors L :label "…"})`
`(mesh-board reference candidate)`
`(mesh-board reference candidate {:views [view …] :ghost false :label "…"})`

## Description

Display a set of named meshes and views on them — never part of the scene
registry, never named, never pickable, never included in export.
`mesh-board` is a display DIRECTIVE, not a transformation: it always
returns its **first argument unchanged**, so it composes cleanly in a
threading pipeline (`(-> t (attach (f 10)) (mesh-board))`) without
altering what the rest of the program computes with.

One model: a **set of named meshes** plus **views**. The two-solid
comparison is the pair case.

- **`t`** — a map (name → mesh — a `let`-chain emission's body, or
  `(split-tree …)` over a bare `mesh-split` call), a vector (an older
  emission, pre-map — shown, but without names), or a single mesh.
  `{:only [...]}` restricts a map input to the named subset. A **raw
  `mesh-split` composite** is refused with an error naming `split-tree`:
  its `:behind`/`:ahead` are not piece names.
- **Show** — with no view options every leaf is drawn in place as a
  ghost-wireframe scaffold.
- **Assembly views** — for seeing a joint that is hidden inside the material:
  - `:solid true` draws the leaves as translucent solids (`:opacity`,
    default `0.35`), one color per leaf in map order, so the pins show
    through the halves. A leaf whose name is a registered mesh holding the
    same geometry (`{:A A …}` after `(register A …)`) has its registered
    original hidden for this evaluation — otherwise the opaque solid in
    place would cover the view.
  - `:explode d` moves each leaf `d` units *away* from its cut faces (the
    cut anchors `mesh-split` leaves on it); a leaf with no cut anchor (the
    pins) stays, and so does a middle piece whose cut faces are opposite.
    View only: the returned value is untouched.
- **Views** — `:views` is a vector; each view opens one picture-in-picture
  window over the viewport (drag its header to move it, scroll to zoom;
  view state, not saved in the source):
  - `[:intersection a b]`, `[:missing a b]`, `[:excess a b]` — a solid
    render of a directional boolean between the elements named `a` and
    `b`: their common volume, `a − b` (what `b` does not cover), `b − a`
    (where `b` overshoots). The header shows the view, the names and the
    **volume**, or `vuoto` when the result is empty — never stale content
    from a previous evaluation. On an assembly `[:intersection :B :pins]`
    is the pin/piece interference.
  - `[:section at]`, `[:section at :offset d]` — the whole set cut by the
    plane of `at`: heading = normal, the same convention as `slice-mesh`
    and `mesh-split`, so `[:section :cut]` is the cut face itself with
    every pin's cross-section on it. `at` is an anchor name looked up in
    `:anchors` (e.g. the map `layout-anchors` returns) and then on the
    elements in map order, or a pose map (for a lengthwise cut through a
    pin, a pose whose heading is that normal). `:offset` slides the plane
    along its normal — wrap the call in `tweak` and the offset becomes a
    live slider, the section sweeping through the joint. Each element's far
    half is solid in its own color, its cut face turned toward the viewer,
    so pins, holes and clearance appear in one figure; the window turns with
    the main viewport, so rotate the scene to look at the cut face head-on
    (from the opposite side you see the outer faces). The shown half is the
    one *ahead* of the found anchor's outward normal: with `{:A A :B B …}`
    and `[:section :cut]` you see `B`'s cut face, looking from `A`'s side.
    `:section at` is the short form of one section view.
- **Compare** — `(mesh-board reference candidate opts)` is the pair
  `{:reference reference :candidate candidate}`: without `:views` it opens
  the three boolean windows between them (`:intersection`, `:missing`,
  `:excess` — bare keywords name the default pair), frames each on
  `reference` with its ghost wireframe for spatial anchoring, and prints
  the **fidelity** (a symmetric-difference-based percentage — 100% for
  identical solids), e.g. `mesh-board: reference vs candidate — fidelity
  97.3%`, in the app's output panel. `:ghost true` also overlays both in
  place as ghost-wireframe scaffolds (grey/blue). `[:section pose]` works
  here too: both solids cut, two colors.

Like `stamp`, in-place scaffolds live in a per-evaluation accumulator: a
full evaluation replaces the whole set, an incremental REPL evaluation
appends to it. Windows follow the same rule at the call level: a full
evaluation drops a removed call's windows; re-evaluating with fewer
`:views` unmounts the ones no longer requested. An optional `:label`
disambiguates the windows (and the fidelity message) of several calls
coexisting in one program.

## Parameters

- `t` — a map of meshes (keys = element names), a vector of meshes, or a
  single mesh.
- `:only` — (map input only) a vector of keys to restrict which elements
  are shown.
- `:solid`, `:opacity` — translucent solids instead of ghost wireframes.
- `:explode` — distance each element moves away from its cut faces.
- `:views` — a vector of views: `[:intersection a b]`, `[:missing a b]`,
  `[:excess a b]`, `[:section at & {:keys [offset]}]`; on the pair form a
  bare `:intersection`/`:missing`/`:excess` names the default pair.
- `:section` — short form: one anchor name / pose, or a vector of them.
- `:anchors` — an extra anchor map (`layout-anchors`' result) to look
  `:section` names up in.
- `reference`, `candidate` — meshes (or SDF nodes) to compare.
- `:ghost` — (pair form) overlay both solids in place as ghost wireframes.
- `:label` — names this call's windows and its fidelity message.

## Example

Seeing a joint. Two halves pinned together, then the three views:

```clojure
(def halves (mesh-split (box 40 60 80)))
(def L (layout-anchors (:behind halves) :cut :inset 5 :spacing 10))
(register pins (on-anchors L "pin" :align (cyl 3 10)))
(register B (mesh-difference (:behind halves) pins))
(register A (mesh-difference (:ahead halves) pins))
(mesh-board {:A A :B B :pins pins}
            {:solid true :explode 30
             :views [[:section :cut :offset 8] [:intersection :B :pins]]})
```

Translucent halves pulled 30 apart with the pins left on the cut plane, a
window with the cut face 8 units into `B` and the pins' cross-sections on
it, and a window with the pin/piece interference — zero here, since the
pins were subtracted from `B`; a pin biting into a wall would show its
volume. Wrap the call in `tweak` and the `8` becomes a slider.

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
