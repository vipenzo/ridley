---
name: sdf->mesh
category: sdf-modeling
since: ""
status: stable
---

# sdf->mesh

## Signature

`(sdf->mesh node)`
`(sdf->mesh node bounds)`
`(sdf->mesh node bounds resolution)`

## Description

Convert an SDF tree into a triangle mesh by marching the libfive
backend over a voxel grid. Returns a mesh with the SDF's anchors and
creation-pose preserved.

Most of the time you do **not** call `sdf->mesh` directly:
materialization happens automatically at the SDF→mesh boundary
(`register`, boolean against a mesh, export). Use this function when
you need explicit control over the meshing region or resolution — for
unusually thin features, very large bounds, or batch precomputation.

For conditional materialization (mesh passes through, SDF materializes)
inside polymorphic helpers, use `sdf-ensure-mesh`.

> Desktop only: requires the libfive backend.

## Parameters

- `node` — the SDF tree to materialize.
- `bounds` (optional) — `[[xmin xmax] [ymin ymax] [zmin zmax]]`. If
  omitted or `nil`, bounds are auto-computed from the tree.
- `resolution` (optional, default `15`) — voxels per unit. Higher
  values give finer meshes but increase voxel count cubically.

## Example

{{example: sdf-mesh-explicit-bounds}}

<!-- example-source: sdf-mesh-explicit-bounds -->
```clojure
;; Explicit bounds + high resolution
(register hires
  (sdf->mesh (sdf-sphere 10) [[-12 12] [-12 12] [-12 12]] 30))
```
<!-- /example-source -->

The explicit bounds give exact control over the meshing region, and
`30` voxels per unit produces a notably finer triangulation than the
default `15`.

## Notes

- The default resolution `15` matches the "turtle-style" scale used by
  curve commands and by `sdf-resolution!`. To raise it globally for
  every auto-materialization, call `(sdf-resolution! 60)` instead of
  passing per-call.
- Total voxel count is capped to keep meshes printable. If the cube
  bounds × resolution would exceed the cap, a warning is printed.
- Bounds smaller than the actual SDF clip the visible surface; bounds
  much larger than necessary inflate voxel count quadratically (in 3D,
  cubically). When in doubt, omit them and trust auto-bounds.

## See also

- **Auto-materialization:** `sdf-ensure-mesh`, `sdf-resolution!`
- **Predicate:** `sdf-node?`
- **All SDF primitives:** `sdf-sphere`, `sdf-box`, `sdf-cyl`,
  `sdf-rounded-box`, `sdf-torus`
