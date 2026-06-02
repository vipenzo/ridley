---
name: sdf-ensure-mesh
category: sdf-modeling
since: ""
status: stable
---

# sdf-ensure-mesh

## Signature

`(sdf-ensure-mesh x)`
`(sdf-ensure-mesh sdf ref-mesh)`
`(sdf-ensure-mesh sdf resolution)`
`(sdf-ensure-mesh sdf ref-mesh resolution)`

## Description

Coerce a value to a mesh: if `x` is already a mesh, return it
unchanged; if it is an SDF node, materialize it (and otherwise pass it
through). This is the controlled-resolution form of auto-meshing —
useful inside polymorphic helpers that accept either representation, or
when you want to materialize once with specific bounds/resolution rather
than at every implicit boundary.

The 2-arg form dispatches on the **type** of the second argument:

- a **number** is interpreted as an explicit resolution override
  (turtle-style units, same as `sdf-resolution!`);
- anything else (a mesh, `nil`, etc.) is interpreted as a reference
  mesh whose bounds extend the SDF's meshing region.

Pass `nil` plus a number in the 3-arg form when you want only a
resolution override and the 2-arg dispatch would be ambiguous.

> Desktop only: requires the libfive backend.

## Parameters

- `x` / `sdf` — an SDF node or a mesh (or any value to passthrough).
- `ref-mesh` — a mesh whose bounds extend the auto-bounds of `sdf`.
  Use this when the SDF will eventually be combined with the mesh and
  you want the meshed region to cover both.
- `resolution` — voxels per unit (same scale as `sdf-resolution!`).

## Example

{{example: sdf-ensure-mesh-polymorphic-helper}}

<!-- example-source: sdf-ensure-mesh-polymorphic-helper -->
```clojure
;; A helper that returns a mesh regardless of whether the input is SDF or mesh
(defn shell-out [x t]
  (let [m (sdf-ensure-mesh x)]
    (mesh-difference m (mesh-translate m 0 0 (- t)))))
```
<!-- /example-source -->

`sdf-ensure-mesh` makes the helper safe to call with either an SDF or
a mesh as input — the SDF is materialized only when needed.

## Variations

{{example: sdf-ensure-mesh-bounds-from-ref}}

<!-- example-source: sdf-ensure-mesh-bounds-from-ref -->
```clojure
;; Extend bounds so they cover an unrelated mesh you will combine with
(def ref-block (box 60 60 60))
(def sdf-detail (sdf-sphere 5))
(def materialized (sdf-ensure-mesh sdf-detail ref-block))
```
<!-- /example-source -->

The materialization uses bounds large enough to cover both the SDF's
extent and the reference block's extent — useful when the SDF is small
but will be combined into a larger region.

{{example: sdf-ensure-mesh-resolution-only}}

<!-- example-source: sdf-ensure-mesh-resolution-only -->
```clojure
;; Resolution override without a reference mesh: pass nil + number
(def fine (sdf-ensure-mesh (sdf-sphere 10) nil 60))
```
<!-- /example-source -->

The 2-arg form would interpret a bare `60` as a resolution override —
but for clarity (and to avoid future ambiguity), the 3-arg form with
`nil` makes the intent explicit.

## Notes

- The voxel-count warning that fires inside `sdf->mesh` also fires
  here when the chosen resolution × bounds combination produces an
  excessive grid.
- The function is idempotent on meshes: `(sdf-ensure-mesh m)` returns
  `m` unchanged when `m` is already a mesh.

## See also

- **Explicit materialization:** `sdf->mesh`
- **Global resolution:** `sdf-resolution!`
- **Predicate:** `sdf-node?`
