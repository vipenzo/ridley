# Reference manual — progress

Tracks completed Reference cards per batch, against the brief in
`dev-docs/brief-reference-batch1-2d-shapes.md` and any successors.

## Batch 1 — 2D Shapes (category: `2d-shapes`)

Brief: `dev-docs/brief-reference-batch1-2d-shapes.md`.

### Constructors

- [x] [circle](circle.md)
- [x] [rect](rect.md)
- [x] [polygon](polygon.md)
- [x] [poly](poly.md)
- [x] [star](star.md)
- [x] [shape](shape.md)
- [x] [stroke-shape](stroke-shape.md)
- [x] [path-to-shape](path-to-shape.md)
- [x] [svg-shapes](svg-shapes.md) (includes `svg-shape` variant)
- [x] [make-shape](make-shape.md)

### 2D Booleans

- [x] [shape-union](shape-union.md)
- [x] [shape-difference](shape-difference.md)
- [x] [shape-intersection](shape-intersection.md)
- [x] [shape-xor](shape-xor.md)

### Modifiers

- [x] [shape-offset](shape-offset.md)
- [x] [fillet-shape](fillet-shape.md)
- [x] [chamfer-shape](chamfer-shape.md)
- [x] [shape-hull](shape-hull.md)
- [x] [shape-bridge](shape-bridge.md)
- [x] [set-image](set-image.md)

### Transformations

- [x] [scale-shape](scale-shape.md)
- [x] [rotate-shape](rotate-shape.md)
- [x] [translate-shape](translate-shape.md)
- [x] [reverse-shape](reverse-shape.md)
- [x] [morph-shape](morph-shape.md)
- [x] [resample-shape](resample-shape.md)
- [x] [fit](fit.md)

### Patterns

- [x] [pattern-tile](pattern-tile.md)
- [x] [voronoi-shell](voronoi-shell.md)

### Visualization

- [x] [stamp](stamp.md)
- [x] [stamp-visibility](stamp-visibility.md) (`show-stamps` / `hide-stamps` / `stamps-visible?`)

### Predicate

- [x] [shape?](shape-p.md)

### Notes

- `loft.md` predates this batch and lives in the same folder as the
  reference template. Its category is `generative-operations`, not
  `2d-shapes` (now part of Batch 2).
- The polymorphic `scale`, `rotate`, `translate` cards are deferred to a
  future batch; the `*-shape` aliases in this batch carry a placeholder
  cross-reference (`→ <name> (polymorphic, scheda futura)`).

## Batch 2 — Generative Operations (category: `generative-operations`)

Brief: `dev-docs/brief-reference-batch2-generative-operations.md`.

### Core generative operations

- [x] [extrude](extrude.md)
- [x] [extrude-closed](extrude-closed.md)
- [x] [extrude-axis](extrude-axis.md) (`extrude-z` / `extrude-y`)
- [x] [loft](loft.md) — pre-existing, audited, no diff required
- [x] [loft-n](loft-n.md) (stub)
- [x] [loft-between](loft-between.md) (stub)
- [x] [revolve](revolve.md)

### Chaining

- [x] [extrude+](extrude-plus.md)
- [x] [revolve+](revolve-plus.md)
- [x] [loft+](loft-plus.md)
- [x] [transform->](transform-arrow.md)

### Shape-fn core

- [x] [shape-fn](shape-fn.md)
- [x] [shape-fn?](shape-fn-p.md)

### Built-in shape-fns

- [x] [tapered](tapered.md)
- [x] [twisted](twisted.md)
- [x] [fluted](fluted.md)
- [x] [rugged](rugged.md)
- [x] [noisy](noisy.md)
- [x] [displaced](displaced.md)
- [x] [morphed](morphed.md)
- [x] [profile](profile.md)
- [x] [heightmap](heightmap.md)
- [x] [woven](woven.md)
- [x] [shell](shell.md)
- [x] [woven-shell](woven-shell.md)
- [x] [capped](capped.md)

### Heightmap helpers

- [x] [heightmap-to-mesh](heightmap-to-mesh.md)
- [x] [mesh-to-heightmap](mesh-to-heightmap.md)
- [x] [sample-heightmap](sample-heightmap.md)
- [x] [weave-heightmap](weave-heightmap.md)

### Noise helpers

- [x] [noise](noise.md)
- [x] [fbm](fbm.md)

### Utility

- [x] [joint-mode](joint-mode.md)
- [x] [angle](angle.md)
- [x] [displace-radial](displace-radial.md)

### Notes

- **`loft.md` audit.** Re-read against Spec §6 (`### Loft`,
  `### Loft-between`). Currently aligned: the three modes (shape-fn,
  transform-fn / legacy, two-shape) are documented; default step count
  of 16 matches Spec; `loft-n` is referenced. No diff required for this
  batch.
- **Spec correction applied.** `docs/Spec.md` line 1183 referenced a
  non-existent `(square 20)` constructor; replaced with `(rect 20 20)`.
  Captured in this batch as the canonical example for `morphed`.
- **Filenames.** `?` → `-p` (`shape-fn-p.md`); `+` → `-plus`
  (`extrude-plus.md`, `revolve-plus.md`); `->` → `-arrow`
  (`transform-arrow.md`). The `name:` frontmatter keeps the original
  symbol.
- **Heightmap / noise helpers** were originally documented in Spec §4
  next to the `heightmap` and `noisy` shape-fns; they live in this batch
  alongside the consumers.

## Batch 3 — Core (Registration / Turtle / 3D Primitives)

Brief: `dev-docs/brief-reference-batch3-core.md`.

### Part A — `revolve` axis verification

- **Result.** Confirmed by source-code reading **and** by a REPL
  diagnostic test: the axis of revolution is the turtle's **up**
  vector, not heading. Test: offset square `[5 0] [10 0] [10 5] [5 5]`
  revolved at default pose (`heading +X`, `up +Z`) produces a mesh with
  bbox `X[-10,10] Y[-10,10] Z[0,5]` — the sweep lies in XY (perpendicular
  to Z=up); the original 2D Y extent stays along Z.
- **Spec correction.** `docs/Spec.md` §6 Revolve updated: signature
  comment ("around turtle up axis"), profile interpretation
  ("perpendicular to up", "in the up direction"), and an explicit note
  that `(tv …)` is the way to tilt the axis.
- **Code correction.** `src/ridley/turtle/core.cljs:1709` had an
  inconsistent docstring opening line ("around the turtle's heading
  axis") contradicting the rest of the same docstring. Fixed.
- **Reference card.** `revolve.md` rewritten to reflect the corrected
  semantics: axis = up, X = radial perpendicular to up, Y = along up;
  added explicit Note pointing to `(tv …)` for axis tilting.

### Registration & Visibility (category: `registration-visibility`)

- [x] [register](register.md) (includes `r` alias)
- [x] [show / hide](show-hide.md)
- [x] [show-all / hide-all](show-hide-all.md)
- [x] [show-only-objects](show-only-objects.md)
- [x] [objects / registered / scene](registry-query.md)
- [x] [info](info.md)
- [x] [bounds](bounds.md)
- [x] [dimensions](dimensions.md) (`height`/`width`/`depth`/`top`/`bottom`/`center-*`)
- [x] [mesh](mesh.md)
- [x] [panel](panel.md)
- [x] [out / append / clear](panel-io.md)
- [x] [panel?](panel-p.md)
- [x] [fit-camera](fit-camera.md)
- [x] [show-lines / hide-lines / lines-visible?](line-visibility.md)
- [x] [color](color.md)
- [x] [material](material.md)
- [x] [reset-material](reset-material.md)

### Turtle Movement (category: `turtle-movement`)

- [x] [f](f.md)
- [x] [u / d](u-d.md) (includes `down` alias)
- [x] [rt / lt](rt-lt.md)
- [x] [th](th.md)
- [x] [tv](tv.md)
- [x] [tr](tr.md)
- [x] [arc-h](arc-h.md)
- [x] [arc-v](arc-v.md)
- [x] [bezier-to](bezier-to.md)
- [x] [bezier-to-anchor](bezier-to-anchor.md)
- [x] [bezier-as](bezier-as.md)
- [x] [pen / pen-up / pen-down](pen.md)
- [x] [reset](reset.md)
- [x] [resolution](resolution.md)
- [x] [turtle](turtle.md)

### 3D Primitives (category: `3d-primitives`)

- [x] [box](box.md)
- [x] [cyl](cyl.md)
- [x] [cone](cone.md)
- [x] [sphere](sphere.md)

### Notes — Batch 3

- **`set-heading` skipped.** Item 26 of the brief: not a user-facing
  function — it exists only as an internal recorded command
  (`:set-heading`) emitted by `bezier-to`, `arc-h`, and similar smooth
  curves. Documented in Spec §11 (SDF attach table) as a recorded
  command, not as a callable.
- **Scope extension (option B).** §13 Spec items not in the brief were
  added on user confirmation: registry helpers (`info`, `bounds`,
  `dimensions`, `mesh`) and appearance (`color`, `material`,
  `reset-material`).
- **`down` alias** documented in [u-d.md](u-d.md) alongside `d`.
- **`box` polymorphism.** 1-arity cube and 3-arity rectangular box
  documented in the same card with both signatures.

## Batch 4 — Path + Mesh Operations + Positioning & Assembly + Faces

Brief: `dev-docs/brief-reference-batch4-path-mesh.md`.

### Path (category: `path`)

- [x] [path](path.md)
- [x] [mark](mark.md)
- [x] [follow](follow.md)
- [x] [side-trip](side-trip.md)
- [x] [quick-path / qp](quick-path.md)
- [x] [poly-path](poly-path.md)
- [x] [poly-path-closed](poly-path-closed.md)
- [x] [follow-path](follow-path.md)
- [x] [anchors](anchors.md)
- [x] [path-to](path-to.md)
- [x] [mark-pos / mark-x / mark-y](mark-query.md)
- [x] [bounds-2d](bounds-2d.md)
- [x] [subpath-y](subpath-y.md)
- [x] [offset-x](offset-x.md)
- [x] [path?](path-p.md)
- [x] [with-path](with-path.md)

### Mesh Operations (category: `mesh-operations`)

#### Booleans + aggregate

- [x] [mesh-union](mesh-union.md)
- [x] [mesh-difference](mesh-difference.md)
- [x] [mesh-intersection](mesh-intersection.md)
- [x] [mesh-hull](mesh-hull.md)
- [x] [concat-meshes](concat-meshes.md)

#### Smoothing & diagnostics

- [x] [mesh-smooth](mesh-smooth.md)
- [x] [mesh-refine](mesh-refine.md)
- [x] [find-sharp-edges](find-sharp-edges.md)
- [x] [mesh-diagnose](mesh-diagnose.md)

#### 3D Chamfer & Fillet

- [x] [chamfer](chamfer.md)
- [x] [fillet](fillet.md)
- [x] [chamfer-edges](chamfer-edges.md)
- [x] [chamfer-prisms](chamfer-prisms.md)

#### Slicing / projection / orientation / import

- [x] [slice-mesh](slice-mesh.md)
- [x] [slice-at-plane](slice-at-plane.md)
- [x] [project-mesh](project-mesh.md)
- [x] [lay-flat](lay-flat.md)
- [x] [decode-mesh](decode-mesh.md)
- [x] [mesh?](mesh-p.md)

### Positioning & Assembly (category: `positioning-assembly`)

- [x] [translate](translate.md) (polymorphic mesh / SDF / shape)
- [x] [scale](scale.md) (polymorphic mesh / SDF / shape)
- [x] [rotate](rotate.md) (polymorphic mesh / SDF / shape)
- [x] [reset-creation-pose](reset-creation-pose.md)
- [x] [transform](transform.md)
- [x] [attach](attach.md)
- [x] [attach!](attach-bang.md)
- [x] [move-to](move-to.md)
- [x] [cp-f / cp-rt / cp-u](cp-position.md)
- [x] [cp-th / cp-tv / cp-tr](cp-rotation.md)

### Faces (category: `faces`)

- [x] [attach-face](attach-face.md)
- [x] [clone-face](clone-face.md)

### Notes — Batch 4

- **Items dropped from the brief.** `inset-face` and `scale-face`
  (items 29–30) do not exist in the codebase or the Spec. No cards
  written; total batch size dropped from 49 to 47.
- **Category split.** The brief listed every item under
  `mesh-operations`. On user confirmation, applied per-Spec
  categories: §3 → `path`, §7 → `mesh-operations`, §9 →
  `positioning-assembly`, §8 → `faces`. `with-path` (Spec §2.2) is
  classified as `path` since its semantics are path-driven.
- **`with-path` correction.** Brief item 17 hinted "se esiste come
  alias/wrapper di `path`". It is in fact a separate macro: it pins
  a path at the current turtle pose, resolves marks as anchors, and
  scopes them for the body. Dedicated card written.
- **`path-to` form.** Documented as `(path-to :anchor-name)` — the
  form used inside `with-path` / `attach-path` scopes. The card
  notes the side-effect on turtle heading.
- **Polymorphic transform placeholders updated.** Batch-1 cards
  `scale-shape.md` / `rotate-shape.md` / `translate-shape.md` no
  longer carry the "scheda futura" placeholder; they now cross-link
  to the polymorphic [scale](scale.md) / [rotate](rotate.md) /
  [translate](translate.md) cards.
- **`mark`, `follow`, `side-trip`, `cp-*` are body-only.** They are
  bound only inside `path` / `attach` / `attach!` recording
  contexts. Each card states this in the signature line.
- **`move-to` placement.** Listed under "Path" in the brief but
  semantically a §9 Positioning command. Categorised as
  `positioning-assembly` to match the Spec.
- **`mesh?` predicate.** The brief lists it (item 44) but Spec does
  not formalise it; the binding exists in `macros.cljs`. Documented
  the practical behavior (`:vertices` + `:faces` shape check).
- **Filenames.** `?` → `-p` (`path-p.md`, `mesh-p.md`); `!` →
  `-bang` (`attach-bang.md`); compound-symbol cards keep their
  primary symbol as `name:` (`cp-position.md` carries `name: cp-f`;
  `cp-rotation.md` carries `name: cp-th`; `mark-query.md` carries
  `name: mark-pos`).

## Batch 5 — Faces / SDF / Text / Warp

Brief: `dev-docs/brief-reference-batch5-faces-sdf-text-warp.md`.

### Faces (category: `faces`)

- [x] [list-faces](list-faces.md)
- [x] [face-ids](face-ids.md)
- [x] [get-face](get-face.md)
- [x] [face-info](face-info.md)
- [x] [find-faces](find-faces.md)
- [x] [face-at](face-at.md)
- [x] [face-nearest](face-nearest.md)
- [x] [largest-face](largest-face.md)
- [x] [auto-face-groups](auto-face-groups.md)
- [x] [ensure-face-groups](ensure-face-groups.md)
- [x] [face-shape](face-shape.md)
- [x] [highlight-face](highlight-face.md)
- [x] [flash-face](flash-face.md)
- [x] [clear-highlights](clear-highlights.md)
- [x] [distance](distance.md)
- [x] [area](area.md)
- [x] [ruler](ruler.md)
- [x] [clear-rulers](clear-rulers.md)
- (already in batch 4) [attach-face](attach-face.md), [clone-face](clone-face.md)

### SDF Modeling (category: `sdf-modeling`)

#### Primitives
- [x] [sdf-sphere](sdf-sphere.md)
- [x] [sdf-box](sdf-box.md)
- [x] [sdf-cyl](sdf-cyl.md)
- [x] [sdf-rounded-box](sdf-rounded-box.md)
- [x] [sdf-torus](sdf-torus.md)

#### Booleans
- [x] [sdf-union](sdf-union.md)
- [x] [sdf-difference](sdf-difference.md)
- [x] [sdf-intersection](sdf-intersection.md)

#### SDF-specific operations
- [x] [sdf-blend](sdf-blend.md)
- [x] [sdf-blend-difference](sdf-blend-difference.md)
- [x] [sdf-half-space](sdf-half-space.md)
- [x] [sdf-clip](sdf-clip.md)
- [x] [sdf-shell](sdf-shell.md)
- [x] [sdf-offset](sdf-offset.md)
- [x] [sdf-morph](sdf-morph.md)
- [x] [sdf-displace](sdf-displace.md)

#### Materialization
- [x] [sdf-ensure-mesh](sdf-ensure-mesh.md)
- [x] [sdf-resolution!](sdf-resolution-bang.md)
- [x] [sdf-node?](sdf-node-p.md)

#### Formulas
- [x] [sdf-formula](sdf-formula.md)
- [x] [sdf-revolve](sdf-revolve.md)

#### TPMS
- [x] [sdf-gyroid](sdf-gyroid.md)
- [x] [sdf-schwarz-p](sdf-schwarz-p.md)
- [x] [sdf-diamond](sdf-diamond.md)

#### Periodic patterns
- [x] [sdf-slats](sdf-slats.md)
- [x] [sdf-bars](sdf-bars.md)
- [x] [sdf-bar-cage](sdf-bar-cage.md)
- [x] [sdf-grid](sdf-grid.md)
- [x] [sdf-arrow-mesh](sdf-arrow-mesh.md)

### Text (category: `text`)

- [x] [text-shape](text-shape.md)
- [x] [text-shapes](text-shapes.md)
- [x] [char-shape](char-shape.md)
- [x] [extrude-text](extrude-text.md)
- [x] [text-width](text-width.md)
- [x] [load-font!](load-font!.md)
- [x] [font-loaded?](font-loaded-p.md)
- [x] [text-on-path](text-on-path.md)

### Spatial Deformation / Warp (category: `spatial-deformation`)

- [x] [warp](warp.md)
- [x] [inflate](inflate.md)
- [x] [dent](dent.md)
- [x] [attract](attract.md)
- [x] [twist](twist.md)
- [x] [squash](squash.md)
- [x] [roughen](roughen.md)
- [x] [smooth-falloff](smooth-falloff.md)

### Notes — Batch 5

- **Filenames.** `?` → `-p` (`sdf-node-p.md`, `font-loaded-p.md`);
  `!` → `-bang` (`sdf-resolution-bang.md`). The `name:` frontmatter
  keeps the original symbol.
- **`sdf-arrow-mesh`** was added during the batch as a debugging helper
  visible from the SCI bindings. Card written for parity.

## Batch 6 — Remaining (Turtle/Assembly residues, Mesh residues, Scene, Live & Interactive, AI Describe, Export, Math & Utilities)

Brief: `dev-docs/brief-reference-batch6-all-remaining.md`.

In progress.

### Turtle & Assembly residues

- [x] [goto](goto.md) (`turtle-movement`)
- [x] [look-at](look-at.md) (`turtle-movement`)
- [x] [get-anchor](get-anchor.md) (`turtle-movement`)
- [x] [attach-path](attach-path.md) (`positioning-assembly`)
- [x] [play-path](play-path.md) (`positioning-assembly`)
- [x] [stretch](stretch.md) (`positioning-assembly`) — `stretch-f`/`stretch-rt`/`stretch-u`
- [x] [link!](link-bang.md) (`positioning-assembly`)
- [x] [unlink!](unlink-bang.md) (`positioning-assembly`)

### Mesh utility residues (category: `mesh-operations`)

- [x] [mesh-simplify](mesh-simplify.md)
- [x] [mesh-laplacian](mesh-laplacian.md)
- [x] [merge-vertices](merge-vertices.md)
- [x] [manifold?](manifold-p.md)

### Scene residues (category: `registration-visibility`)

- [x] [turtle-visibility](turtle-visibility.md) — `show-turtle` / `hide-turtle`
- [x] **`register.md` extension** — add "Hierarchical assemblies" section
  (map-literal form inside `with-path`, name-prefixing, link inference,
  prefix-matching of show/hide)

### Live & Interactive (category: `live-interactive`)

- [x] [tweak](tweak.md)
- [x] [anim!](anim-bang.md)
- [x] [anim-proc!](anim-proc-bang.md)
- [x] [span](span.md) — includes `parallel` sub-form
- [x] [play!](play-bang.md)
- [x] [pause!](pause-bang.md)
- [x] [stop!](stop-bang.md)
- [x] [stop-all!](stop-all-bang.md)
- [x] [seek!](seek-bang.md)
- [x] [anim-list](anim-list.md)
- [x] [ease](ease.md)
- [x] [pilot-request!](pilot-request-bang.md)
- [x] [edit-bezier](edit-bezier.md)
- [x] [edit-path](edit-path.md)
- [x] [picking](picking.md) — `selected` / `selected-mesh` / `selected-face` /
  `selected-name` / `source-of` / `origin-of` / `last-op`

### AI Describe (category: `ai-describe`)

- [x] [describe](describe.md)
- [x] [ai-ask](ai-ask.md)
- [x] [end-describe](end-describe.md)
- [x] [cancel-ai](cancel-ai.md)
- [x] [ai-status](ai-status.md)

### Export (category: `export`)

- [x] [export](export.md)
- [x] [save-mesh](save-mesh.md)
- [x] [save-stl](save-stl.md) (stub → `save-mesh`)
- [x] [save-3mf](save-3mf.md) (stub → `save-mesh`)
- [x] [render-view](render-view.md)
- [x] [render-all-views](render-all-views.md)
- [x] [render-slice](render-slice.md)
- [x] [save-views](save-views.md)
- [x] [save-image](save-image.md)
- [x] [anim-export-gif](anim-export-gif.md)
- [x] [export-manual](export-manual.md) — covers `export-manual-en`/`-it`/`export-manual`

### Math & Utilities (category: `math`)

- [x] [math](math.md) — consolidated: trig, exp, rounding, comparison,
  angle conversion, `PI`
- [x] [vec3](vec3.md) — consolidated: `vec3+` / `vec3-` / `vec3*` /
  `vec3-dot` / `vec3-cross` / `vec3-normalize`
- [x] [turtle-state](turtle-state.md) — `get-turtle` /
  `turtle-position` / `turtle-heading` / `turtle-up` / `attached?` /
  `last-mesh`
- [x] [console-print](console-print.md) — `println` / `print` / `prn` /
  `log` (debug) / `T` (tap)
