---
name: lay-flat
category: mesh-operations
since: ""
status: stable
---

# lay-flat

## Signature

`(lay-flat mesh)`
`(lay-flat mesh target)`
`(lay-flat mesh path)`
`(lay-flat mesh path mark)`

## Description

Rotate a mesh so that one of its faces ends up flush with the world
XY plane, then re-center the result at the origin. Designed for
preparing a model for 3D-print export: a slicer expects the model
sitting on a flat bed, and `lay-flat` is the explicit step to put it
there without re-modelling.

Three selection modes:

- **Direction keyword** — `:top`, `:bottom`, `:up`, `:down`,
  `:left`, `:right`. `lay-flat` finds the LARGEST face whose normal
  points in that direction and lays it down. Default (no argument)
  is `:bottom`.
- **Path** — a path value carrying a `(mark …)`. `lay-flat` resolves
  the mark at the mesh's creation-pose and lays that plane flat (the
  mark's heading is the face normal). This is the self-contained way
  to lay a print face that is not on a cardinal direction — no
  separate `attach-path` step. With several marks, pass the name as a
  third argument: `(lay-flat mesh path :name)`.
- **Anchor keyword** — any other keyword. `lay-flat` resolves it as
  a named anchor already on the mesh (e.g. from `attach-path`, or a
  profile mark) and lays the anchor's plane flat.

Returns a new mesh; the input is unchanged.

## Parameters

- `mesh` — a mesh value or a registered name.
- `target` (optional) — a direction keyword, a path with a `(mark …)`,
  or a named-anchor keyword, as above.
- `mark` (optional, with a path) — the name of the mark to use when the
  path carries more than one.

## Example

{{example: lay-flat-default}}

<!-- example-source: lay-flat-default -->
```clojure
(register bracket
  (-> (box 30 20 8)
      (lay-flat)))     ; bottom face down on Z=0
```
<!-- /example-source -->

No argument is shorthand for `:bottom`. The mesh's largest bottom
face sits on the XY plane.

## Variations

{{example: lay-flat-direction}}

<!-- example-source: lay-flat-direction -->
```clojure
;; Print the part on its left side (largest leftward face touches the bed)
(register sideways
  (-> (box 40 10 20)
      (lay-flat :left)))
```
<!-- /example-source -->

For asymmetric parts the optimal print orientation is often not the
default bottom. The direction keyword picks the largest face on the
specified side and lays it down.

{{example: lay-flat-path}}

<!-- example-source: lay-flat-path -->
```clojure
;; Mark the desired print face with a path and lay it flat in one step
(register part (box 30 20 8))
(register oriented (lay-flat part (path (tv 35) (mark :print-face))))
```
<!-- /example-source -->

When the print face is not on a cardinal direction, point a path's
mark along the face normal and hand the path to `lay-flat`. The mark
is resolved at the part's creation-pose and its plane is laid flat —
no separate `attach-path` step.

## Notes

- `lay-flat` also re-centers the output at the origin. If you need
  to keep a specific corner at `[0 0 0]`, follow up with
  `translate` to position the part.
- For meshes without pre-defined face groups (e.g. CSG results),
  face groups are auto-detected by coplanar adjacency; the largest
  bottom face is selected from that grouping.
- The operation modifies the mesh's vertices, not its
  `:creation-pose`. The pose stays at the original construction
  origin; `reset-creation-pose` re-anchors it if needed.

## See also

- **Related:** `attach-path`, `translate`, `rotate`,
  `reset-creation-pose`, `bounds`
