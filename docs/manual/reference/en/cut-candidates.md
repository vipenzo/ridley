---
name: cut-candidates
category: mesh-operations
since: ""
status: stable
---

# cut-candidates

## Signature

`(cut-candidates mesh)`
`(cut-candidates mesh opts)`

## Description

Propose cut-plane poses for a mesh — the candidates a "next event" gesture
(step through, don't hunt by eye) jumps between, and what `edit-mesh-split`'s
section-area strip marks as ticks. A pure function of `(mesh, turtle pose,
opts)` in its underlying form (`ridley.manifold.core/cut-candidates`); this
DSL wrapper fills the pose from the turtle's current position/heading/up, so
only `opts` override anything.

Returns a vector of `{:pose {:position :heading :up} :kind :step|:neck|:reflex
:salience n}`, sorted by `:salience` **descending** — off-axis or curved
meshes can have hundreds of candidates, so ranking is part of the contract,
not an afterthought.

Three candidate generators, selected by `:mode`:

- **`:translation`** (default) — candidates along the turtle's current
  heading. **STEP**s are exact: a face coplanar with the sweep (its normal
  parallel to the heading) marks a jump in the cross-section area, read
  directly from the mesh's faces (`|ΔA|`), never by sampling. **NECK**s are
  local minima of the sampled section-area profile (the "waist" of a
  dumbbell shape).
- **`{:mode :rotation :axis :up|:right}`** — a pencil of planes pivoting
  about the given axis (never `:heading` — that never moves the plane, and
  throws a readable error). STEPs are faces lying in the pencil's own plane
  (rare); NECKs are minima of `A(θ)`.
- **`{:mode :reflex}`** — cuts where the mesh's own concavity lives: the
  clustered face-planes of its reflex (concave, dihedral > 180°) edges,
  ranked by concavity mass (`Σ length × angle-excess`). Reads only the mesh,
  not the turtle pose — a convex mesh returns `[]`.

## Parameters

- `mesh` — the mesh (or keyword name, or SDF node) to propose cuts for.
- `opts` (optional map):
  - `:mode` — `:translation` (default) | `:rotation` | `:reflex`.
  - `:axis` — `:rotation` only, `:up` (default) | `:right`.
  - `:tolerance` — dedup tolerance for coplanar step offsets (mm, default
    `0.1`); for `:reflex`, the cluster offset tolerance.
  - `:angle-tol` — how parallel to the sweep a face normal must be to count
    as a step, in degrees (default `1.0`).
  - `:samples` — profile sample count (default `96`).
  - `:min-neck-depth` — valley-depth floor for NECKs (default 1% of the
    profile's peak area).
  - `:bias` — **STEP-only.** The flush-cut pose for a STEP candidate isn't
    placed exactly ON the coplanar face — Manifold's meshes are float32, so
    a "flat" face is rippled by a few nm of tessellation noise, and a plane
    at zero distance from it splits that noise unpredictably, shaving an
    irregular sliver off the wrong side. `:bias` shifts the suggested pose
    by that amount **into the bulk** — the side the cross-section is larger
    on — along the snapped normal, so the plane clears the noise band and
    the whole rippled face lands cleanly on one side. Default: scale-aware,
    `max(1e-3mm, 1e-4 × bbox-diagonal)` — far above fp32 noise (nanometres
    at real-part scale), far below any real feature. `:bias 0` reproduces
    the old exactly-flush behaviour, for comparison/debugging. The sign is
    resolved per-candidate from its cluster's `ΔA` (which side the material
    continues on) — you only configure the magnitude.
  - `:reflex-tol`, `:cluster-angle-tol` — `:reflex` only; see
    `ridley.geometry.cut-candidates/reflex-candidates`.

## Example

{{example: cut-candidates-basic}}

<!-- example-source: cut-candidates-basic -->
```clojure
(register block (box 40 30 10))
(f 20)
(def cands (cut-candidates (get-mesh :block)))
;; two STEP candidates (the ±X caps), salience = the box's 30×10 section
```
<!-- /example-source -->

## Notes

- STEP salience is exact (read from the mesh's faces); NECK/rotation-STEP
  salience comes from the sampled section-area profile — see `section-area`.
- The candidate's `:pose` is directly usable to place the turtle
  (`goto`/`mark`), or as a `mesh-split` cut plane.
- The bias only touches STEP poses — `:neck` and `:reflex` candidates cut
  through the solid by construction, never flush with a coplanar face, so
  there is no equivalent ambiguity to correct.
- Sibling safety net: `mesh-split`'s `:heal-slivers` opt catches the same
  class of sliver **after** a cut, whether the plane came from a candidate
  or was placed by hand — the bias here prevents it at the suggestion stage,
  `:heal-slivers` cleans it up regardless.
- `edit-mesh-split` shows the section-area profile as a strip with these
  candidates as ticks (blue = step, orange = neck), and a "jump to next
  event" gesture that steps between them in position order, not salience
  order (salience is for filtering/ranking, navigation follows the axis).

## See also

- **Related:** `section-area`, `mesh-split`, `symmetry-planes`,
  `mesh-components`
- **Interactive:** `edit-mesh-split` — the section-area strip, jump-to-event
  gestures, and (Part 3 of the sliver-bias brief) the sliver indicator + ±ε
  micro-nudge buttons all build on this.
