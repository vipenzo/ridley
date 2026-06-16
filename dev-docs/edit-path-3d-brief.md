# Brief: path-2d / edit-path-2d / edit-path-3d (path editing arc)

Status: **path-2d + edit-path-2d + edit-path-3d (straight segments) BUILT & working
in-app (2026-06-16, branch `fix/arc-cap-flush-extrude`).** Next: **Phase 3 — 3D
curves (arc/bezier)** in edit-path-3d, and optionally an extrude `:minimize-twist`
option. This brief is the resume point for a fresh chat.

Related memory: `project_path_2d_3d.md`, `project_edit_path_3d.md`,
`project_edit_path_reedit.md` (2D curve recovery). Earlier brief: `path-2d-brief.md`
(path-2d design, now built).

---

## 1. What exists now (the path family)

Three path "species", all `{:type :path :commands [...]}`, distinguished by a tag:

- **`:3d` (default)** — a turtle rail in space. Consumed in its own frame by
  `extrude`-along-path / `loft`. `edit-path` edits it.
- **`:2d`** (`:species :2d`) — a planar profile whose trace lies in the `(right,up)`
  plane (the plane a shape stamps into). `path-2d` macro produces it; `edit-path-2d`
  edits it; `path-to-shape`/`stroke-shape` consume it. Acceptance invariant:
  `(follow-path P) ≡ (stamp (path-to-shape P))`.

Normalizers at consumer boundaries (the Ridley pattern):
- **`ensure-path-2d`** (shape.cljs) — used by `path-to-shape`/`stroke-shape`/`bounds-2d`.
  `:2d` → 3D-trace + project onto canonical right=[0 -1 0], up=[0 0 1] (a=-y, b=z),
  honoring a leading `move-to` anchor; `:3d` → legacy `path-to-2d-waypoints` (x,y).
- **`ensure-untwisted`** (shape.cljs, `^:export`, bound) — re-frames a 3D rail with a
  rotation-minimizing (parallel-transport) up so a sweep doesn't twist. Manual remedy
  for hand-written non-planar rails (see §4 limitation).

## 2. Macros & editors

- `path-2d` (macros.cljs): `(assoc (path (th -90) (let [th→tv tr→tv rt→u lt→down arc-h→arc-v] body)) :species :2d)`.
  Seeds heading onto the incoming `right` pose-lessly via `(th -90)`; inside, the
  symbols collapse to the plane's dof (th=tv=tr in-plane turn +=left; rt=u/lt=down
  strafe; arc-h=arc-v).
- `edit-path-2d` (+ deprecated `edit-path-2d`-alias semantics): `(edit-path-request! (path-2d …))`.
- **`edit-path`** = the **3D rail editor**: `(edit-path-request! (path …))`. (No longer
  a 2D alias.) `request!` branches on `(:species seed)`: `:2d` → 2D editor, else → 3D.

`set-heading` is now bound inside `(path …)` (rec-set-heading*), and has a **`:local`**
variant — see §3.

## 3. edit-path-3d — the model & interaction (all in edit_path.cljs, `:mode :3d`)

Shared editor code with 2D, so the UI matches. Differences gated by `(three-d? s)`.

- **Positions-only**: a node carries only `:pos` (world [x y z]); heading derived from
  tangent (like 2D). **Node 0 is the pinned anchor** at the origin — not movable
  (`pinned?` guards move-node!/nudge!); the rail is relative, placed by the consuming
  pose. Opens with just that anchor node; click to add the rail.
- **Working plane** = one of the turtle frame-planes, keys **f / r / u** (⊥forward = the
  2D plane / ⊥right / ⊥up) + 3 radios in the panel. Nodes render as **rings oriented to
  the active plane** (foreshorten = orbit-to-edit cue).
- **Hit-testing in screen space** (`viewport/world->screen` + `nearest-node-screen`,
  pixel threshold) so any visible node is grabbable regardless of plane.
- **Drag** deprojects onto the active plane at the node's depth (`:drag-anchor`).
  **Shift+drag** axis-locks to the dominant in-plane axis (note: `plain-click?` excludes
  modifiers, so on-pointer-down has a `shift-grab?` branch to start the drag; Shift on
  empty space goes to the camera).
- **Arrows** nudge along the plane axes. **Ins/i** splits the incoming segment at its 3D
  midpoint (`split-segment!` has a 3D branch; `midv` = generic midpoint).
- **Plane grid** anchored at the origin in-plane, shifted along the normal to the node
  depth.
- **Precision fields** in the panel: **len** + **ang°** of the selected node's incoming
  segment, measured IN the active plane (so they change with f/r/u; out-of-plane
  component preserved on edit). Editable **Step** (number input).

**Bake (the key bit) — twist-free, composing `set-heading :local` rail.**
`nodes->commands-3d` → `shape/positions->rmf-commands`: per segment
`(set-heading [dir][up] :local)(f dist)`, where `dir` and the **parallel-transport
(RMF) up** are expressed in the PREVIOUS segment's `[right up heading]` frame.

`set-heading :local` (new, §commit f91a502): the two vectors are read in the current
frame's basis `[right up heading]` and mapped to world — so the frame is RELATIVE,
and the rail **composes under the consumption pose** (rotates when placed via
`attach`/`on-anchors`/after a turtle turn). Plain (absolute) `set-heading` does NOT
compose (it ignores pose rotation). `:local` is honored in `core/apply-set-heading`
(shared by run-path & compute-path-waypoints), `shape/path-to-3d-waypoints`, and
`extrusion/apply-rotation-to-state`. The flag is threaded through
`rec-set-heading`/`rec-set-heading*` so a re-evaluated baked source keeps composing
(it was silently dropped before — a real bug).

`seed->nodes-3d` recovers nodes from a `:3d` path via `path-to-3d-waypoints`
(positions only; curves degrade to polyline — 3D curve recovery is Phase 3).

## 4. Known limitations / traps (read before resuming)

- **extrude twist on hand-written non-planar rails.** `extrude`/`loft` orient the
  section by the turtle's evolving up; on a non-planar rail that up accumulates a roll
  (holonomy) → the tube spirals. NOT fixed in the extrude core (too broad). The
  edit-path bake avoids it (carries the RMF up via set-heading :local). Manual remedy
  for hand-written rails: `(extrude prof (ensure-untwisted p))`. **Possible Phase-3+
  work: an extrude `:minimize-twist` option** so hand rails don't need ensure-untwisted.
- **Sharp 3D joints can pinch** (miter limit), separate from twist; ensure-untwisted
  doesn't fix it (doesn't move nodes).
- **`request!` wiring trap (fixed, don't regress):** `seed->nodes-3d` returns a plain
  node vector, not the 2D `{:nodes :dropped}` map — request! must wrap it as
  `{:nodes (seed->nodes-3d …) :dropped []}`, else the 3D editor opens EMPTY.
- **set-heading composition trap (fixed):** plain set-heading is absolute (ignores pose
  rotation). Always use `:local` for rails meant to be placed. Verified: a leading
  `(th 90)` rotates a `:local` path's endpoint but not an absolute one's.
- Tauri loads `public/` (static bundle, no devUrl); `npm run dev`/shadow watch writes
  there. For REPL testing use Chrome at localhost:9000 (the webview has no REPL and
  caches aggressively). The runtime drops on multi-namespace `:reload`s — wait and
  re-eval without `:reload` (the rebuilt bundle is current).

## 5. Phase 3 — 3D curves (next)

Add arc/bezier to edit-path-3d (the 2D editor has them: `c`=bezier, `a`=arc, `x`=cusp,
belly handle, rider-tag recovery via `:arc-cap`/`:pure`). For 3D:
- Decide the control-handle DOF in 3D (bezier handles out of the active plane; arc belly
  is 2 DOF). Likely edit handles in the active plane, like positions.
- Frame handling through curves: the tessellated curve points must feed the same
  RMF / set-heading-:local bake so curved rails stay twist-free & composing.
- Re-edit recovery of 3D curves: extend `seed->nodes-3d` to read the rider tags in 3D
  (the 2D `project-2d-to-xy` projects riders; 3D needs the 3D analog).
- Curve keys are currently **gated to 2D** in the keymap (`c`/`a`/`x`, Shift/Alt-arrows);
  ungate for 3D when implemented.

## 6. Key files / fns

- `src/ridley/turtle/shape.cljs` — `ensure-path-2d`, `path-to-3d-waypoints` (+ `:local`),
  `positions->rmf-commands`, `ensure-untwisted`, `rmf-rot/rmf-safe-up/rmf-transport`.
- `src/ridley/turtle/core.cljs` — `apply-set-heading` (abs/`:local`), `rec-set-heading`,
  `run-path`, `compute-path-waypoints`.
- `src/ridley/turtle/extrusion.cljs` — `apply-rotation-to-state` (+ `:local`),
  `resolve-2d-source-anchors` (:2d mark projection), `extrude-from-path`.
- `src/ridley/editor/edit_path.cljs` — the editor: mode seam (`three-d?`, `active-basis`,
  `node->world`, `world->stored`, `node-plane-pos`), `render-3d!`, `seed->nodes-3d`,
  `nodes->commands-3d`, `nodes->code-3d`, `split-segment!`, `set-plane!`, precision
  (`seg-len`/`seg-angle-deg`/`set-seg-len!`/`set-seg-angle!`, in-plane), `request!`.
- `src/ridley/editor/macros.cljs` — `path-2d`, `edit-path`/`edit-path-2d`,
  `rec-set-heading*`, `set-heading` bound in `path`.
- `src/ridley/editor/bindings.cljs` — `ensure-untwisted`, `ensure-path-2d` bindings.
- Docs: `docs/Spec.md` §3 (Planar paths, Edit Path); manual cards `set-heading.md`,
  `ensure-untwisted.md`, `edit-path.md`.

## 7. Session commits (a902e41..f91a502)

path-2d/ensure-path-2d (759d07c) · :2d marks (57df063) · seam-at-start (562a358) ·
edit-path-2d bake/recover (a929443) · edit-path-3d phase 1 data (d92dd9e) · phase 2
interaction (c716b0e) + open-rail/taper/grab/grid/anchor/pin/precision/empty/single
fixes · th/tv → tr → set-heading :local frame saga (cee6383, a304b13, 296d403, f24add2,
5c46085, f91a502) · 3D split + reference cards (4130856).

All green: `npm test` 298/847, 0 failures.
