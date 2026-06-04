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

## Description

Rotate a mesh so that one of its faces ends up flush with the world
XY plane, then re-center the result at the origin. Designed for
preparing a model for 3D-print export: a slicer expects the model
sitting on a flat bed, and `lay-flat` is the explicit step to put it
there without re-modelling.

Two selection modes:

- **Direction keyword** — `:top`, `:bottom`, `:up`, `:down`,
  `:left`, `:right`. `lay-flat` finds the LARGEST face whose normal
  points in that direction and lays it down. Default (no argument)
  is `:bottom`.
- **Anchor keyword** — any other keyword. `lay-flat` resolves it as
  a mark anchor (set by `attach-path`) on the mesh and lays the
  anchor's plane flat. Useful when a named anchor was pre-positioned
  to mark the intended print face.

Returns a new mesh; the input is unchanged.

## Parameters

- `mesh` — a mesh value or a registered name.
- `target` (optional) — direction keyword or anchor keyword as above.

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

{{example: lay-flat-anchor}}

<!-- example-source: lay-flat-anchor -->
```clojure
;; Predefine an anchor at the desired print face, then lay it flat
(register part (box 30 20 8))
(attach-path :part (path (mark :print-face)))

(register oriented (lay-flat :part :print-face))
```
<!-- /example-source -->

When the print face is not aligned with a cardinal direction, mark
it as a named anchor and pass that keyword to `lay-flat`. The
anchor's plane is laid flat regardless of orientation.

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
