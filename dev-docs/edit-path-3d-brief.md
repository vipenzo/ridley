# Brief: path-2d / edit-path-2d / edit-path-3d (path editing arc)

Status: **path-2d + edit-path-2d + edit-path-3d BUILT & working
in-app (2026-06-16, branch `fix/arc-cap-flush-extrude`).** Curves:
- **2D editor**: tangent "raccordo" **arc** (`a` → `arc-v`) + free **bezier** (`c`).
- **3D editor**: curves are **béziers** (`c` free, `a` arc-shaped) — see Phase 3b. The
  3D arc-as-`arc-h` approach was dropped (it twisted the tube at the seam); the
  *language* keeps `arc-h`/`arc-v` for hand-written clarity, and a hand-written `arc-h`
  opened in the 3D editor is converted to a bezier on input.

Headless round-trips verified, `npm test` 298/847 green, pending in-app eyeball.
Optional later: an extrude `:minimize-twist` option. This brief is the resume point.

### Phase 3b (3D béziers) — the 3D editor's curve primitive

**Why bezier, not arc, in 3D.** A 3D arc baked as `(set-heading [t][normal] :local)
(arc-h …)` keeps the centerline smooth but forces the section `up` = the arc-plane
normal, which JUMPS (up to 180°) vs the straight's RMF up at the seam → the extruded
tube twists/pinches at the arc entry (observed in-app). Tessellating the arc into one
RMF sweep fixes the twist but bloats the source/re-edit into hundreds of nodes. The
**cubic bezier** solves both: `bezier-to` tessellates at eval-time with its OWN
rotation-minimizing frame (`rec-bezier-to*` parallel-transports the up → continuous,
no pinch) yet the **source is one compact `(bezier-to [end][c1][c2] :local)`**, and the
run carries a `:pure` tag so re-edit recovers it as a single node. Verified headless:
out-of-plane bezier rail has continuous up (no seam jump); exact c1/c2 round-trip.

Implementation (all [edit_path.cljs](../src/ridley/editor/edit_path.cljs)):
- node model `:bez {:c1 :c2}` (world 3-vec handles, free — no tangent re-snap in 3D).
- `bezier-frame-3d` (tessellate + projection-RMF → :pts/:exit-h/:exit-u, mirroring
  `rec-bezier-to*` so the walk's post-bezier frame matches the recorder's) ·
  `walk-3d-segments` (:bez/:straight, single source for bake + render) ·
  `nodes->commands-3d` (bezier → one `bezier-to :local`; straight → set-heading/f).
- recover: `group-arc-runs-3d` collapses `:pure` (bezier, with move-count) and
  `:arc-cap` (hand `arc-h`) runs; `seed->nodes-3d` builds a bez node from `:pure`
  (exact) or converts an arc run via `arc-run->bez`/`arc->bez-handles` (L=(4/3)tan(θ/4)r).
- interaction: `toggle-bezier!` (**`c`**, free bezier, c1 along incoming tangent) ·
  `toggle-arc!` (2D **`a`** = toggle arc-v; 3D **`t`** = a BOTH-ends-tangent raccordo:
  c1 along the incoming heading, c2 along the OUTGOING direction toward the next node, so
  the corner is smooth on both sides — the deliberate exception to "each curve sets its
  own arrival heading"; falls back to an arc-shaped bezier on the last node). 3D `t`
  ALWAYS (re)applies the tangent fit (never reverts to a line — that's `c`'s toggle job;
  re-pressing `t` re-fits after moving a neighbour) · `render-3d!` (bezier tessellation + c1/c2 squares + guide lines) ·
  `nearest-bez-handle-screen` + screen-space handle grab/drag **constrained to the
  active plane** (raycast through the handle's depth ⊥ the active-plane normal);
  **Shift+drag a handle = length-only** (slides along its fixed direction from the
  anchor node, escaping the plane — `:dir`/`:anchor` stashed on grab) ·
  `move-node!`/`nudge!`/`nudge-handle!` dimension-agnostic (handle nudge moves along the
  active px/py) · `split-segment!` de Casteljau in 3D · keys `c`/`t` + Shift/Alt-arrow
  handle nudge for 3D (`x` cusp stays 2D-only — 3D handles are already free).
  Distinct key `t` (not `a`) for the 3D raccordo so it doesn't clash with 2D's arc.

### Marks — editable named anchors on nodes (2D + 3D)

A mark is just a named point: `(mark :name)` on a node's `:tail` (record-only, rides
edits, re-emits in the bake). Now editable in both editors:
- **Data/plumbing**: 2D already carried `:tail`; 3D didn't — fixed so
  `nodes->commands-3d` re-emits each node's tail (anchor's first, then per end node via
  the walk's `:i`) and `seed->nodes-3d` captures `:mark`/`:side-trip` into the tail.
- **Editing**: panel **"mark" text field** for the selected node (blank = remove; 2D+3D,
  focus-safe via `update-panel!`); **`m` key** quick-adds a default unique name (`:m1`…)
  and focuses the field to rename. `node-mark-name`/`set-node-mark!`/`add-mark-quick!`.
  Marked nodes stay green + delete-protected (clear the mark to delete the node).
- **Visualization (option B)**: mark names float at their nodes as **camera-facing
  billboard labels** — new `viewport/set-labels!`/`clear-labels!` (reuse
  `create-panel-mesh`; billboarded in the render loop alongside scene panels; a
  same-texts fast path just repositions meshes so dragging a marked node doesn't thrash
  textures). edit-path's `render!`/`render-3d!` push labels; `cleanup!` clears them.
- Verified: mark round-trip 2D (`(path-2d (f 20)(mark :corner)(tv 90)(f 20))`) and 3D
  (`(mark :foo)`/`(mark :bar)` preserved through seed→bake). npm test 298/847 green.
- **Label toggle**: `Shift+m` shows/hides the billboard labels (they occlude the node
  they sit on while editing) — session flag `:labels-hidden?` honored by
  `update-mark-labels!`. Labels also draw on top (depthTest off) so a node on the rail
  centerline isn't hidden inside the extruded tube. `set-labels!` always clears+recreates
  (a fast-path that repositioned meshes broke when the live re-eval emptied world-group).
- **RAIL marks → mesh anchors (the payoff)**: a `(mark :name)` on the *rail* now becomes
  a mesh `:anchor` at the centerline (pose = rail frame), so `(on-anchors tube :name …)`
  attaches there — previously only PROFILE/section marks became anchors. Implemented in
  `operations/merge-rail-anchors` (resolve the path's rail marks via `turtle/resolve-marks`
  from the consumption pose, merge into the result's `:anchors`; profile mark wins a name
  clash). Injected in `pure-extrude-path` so it covers BOTH the normal extrude AND the
  bezier→`pure-loft-path` delegation (a path with `bezier-to` has `:bezier true` and is
  lofted, bypassing `extrude-from-path`); `extrude-from-path` also carries them for the
  implicit/direct callers. Verified: `(extrude (circle 3) rail-with-(mark :joint))` →
  `:anchors {:joint …}` → `(on-anchors tube :joint :align (attach (cone …)))` places the
  cone. Not yet wired for user-facing `loft`/`revolve` spines (follow-up).

### Phase 3a (2D arcs) — TANGENT "raccordo" model

### Phase 3a (arcs) — TANGENT "raccordo" model, 2D + 3D

An arc node carries just **`:arc {}`** (no belly). It is the unique circular arc that
leaves its start node **tangent to the incoming heading** and ends at the next node —
a rounded corner, so there is **no cusp at the start**. Geometry note (the question
that drove this): an arc constrained by (start position, start tangent, end position)
**always exists and is unique** — only the start tangent is pinned, not the end's — so
there is never an "impossible" case needing a bezier promotion; the sole degenerate is
*tangent ∥ chord*, which falls back to a straight. (A tangent pointing away from B is
still valid, just a large sweep.) Chains of arcs are smooth throughout, because each
arc takes the previous arc's exit tangent as its own incoming tangent.

Bake — **2D** (profile, planar, no section twist): a bare **`(arc-v r sweep)`** (no
leading `th`, since it's already tangent); recoverable for free because `rec-arc-v*`
re-tessellates into `:arc-cap`-tagged steps, so the tag machinery finds the run →
single arc node on re-edit.

Bake — **3D** (rail, extruded): tessellate the arc to points and frame the WHOLE rail
(straight endpoints + arc points) with ONE rotation-minimizing sweep via
`positions->rmf-commands`. This is required for a clean tube: an arc baked as
`(set-heading [t][normal] :local)(arc-h …)` keeps the *centerline* continuous but
forces the section `up` = the arc-plane normal, which JUMPS relative to the straight's
RMF up at the seam (up to 180°) → a sudden section roll that pinches/twists the
extrusion at the arc's entry (observed in-app). Feeding tessellated points through RMF
keeps `up` continuous everywhere (verified: no seam jump). **Trade-off:** the 3D baked
source is set-heading/f only (no `arc-h`), so re-editing a 3D arc recovers it as its
tessellated polyline, not a single arc node — restoring single-node 3D re-edit needs
geometric arc-detection in `seed->nodes-3d` (a follow-up). 2D re-edit is unaffected.

Implementation (all in [edit_path.cljs](../src/ridley/editor/edit_path.cljs)):
- geometry: `tangent-arc-geom-3d`/`tangent-arc-tess-3d` (world) and
  `tangent-arc-2d`/`tangent-arc-tess-2d` (plane); `rmf-transport-up`.
- frame walks (single source of truth for render + split, mirroring the bake's heading
  tracking): `walk-3d-segments`, `walk-2d-segments`.
- bake: `nodes->commands-3d` (walk → set-heading :local + arc-h) and the 2D
  `nodes->commands` arc branch (→ bare arc-h, no th).
- recover: `group-arc-runs-3d` + `seed->nodes-3d`, and the 2D `seed->nodes` arc-run
  branch — both rebuild the node as `:arc {}` (the arc is re-derived from the incoming
  heading + node positions, which reproduces the baked tangent arc exactly).
- interaction: `toggle-arc!` (2D + 3D → `:arc {}`, no belly), `render!`/`render-3d!`
  tessellate via the walk, `split-segment!` splits an arc into two tangent arcs at the
  mid-sweep point, `a` key ungated for 3D. **Belly handle / hit-test / drag removed**
  (the arc has no free handle now).

Verified headlessly: 3D (XY, tilted plane, mixed straight/arc/straight/arc — bake emits
`set-heading [0 0 1] …` before arc-h ⇒ heading continuous, endpoints exact, recovery
clean) and 2D (`(path-2d (f 20) (arc-v 10 180) (f 20))` — no th, exact recovery, valid
67-pt rounded profile). `npm test` 298/847, 0 failures.

**Still 2D-only (Phase 3b):** béziers (`c`), cusp (`x`), Shift/Alt-arrow handle nudge.

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
