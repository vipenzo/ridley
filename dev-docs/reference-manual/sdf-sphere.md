---
name: sdf-sphere
category: sdf-modeling
since: ""
status: stable
---

# sdf-sphere

## Signature

`(sdf-sphere r)`

## Description

Construct an SDF node for a sphere of radius `r` centered at the
origin. Returns an immutable map representing the SDF tree — no
geometry is computed until meshing happens (at `register`, boolean with
a mesh, or `sdf->mesh`).

SDF spheres are the lightest possible SDF: a small map with `:op`,
`:r`, and a creation-pose. Use them as building blocks for booleans,
blends, and morphs.

SDFs support the same polymorphic transforms as meshes (`translate`,
`rotate`, `scale`) and the `attach` family. Materialization to a mesh
happens automatically when the SDF crosses a boundary — see
`sdf->mesh`, `sdf-ensure-mesh`, and `sdf-resolution!` for explicit
control.

> Desktop only: SDF operations require the libfive backend served by
> the Rust sidecar. They are not available in the browser build.

## Parameters

- `r` — radius in world units.

## Example

{{example: sdf-sphere-basic}}

<!-- example-source: sdf-sphere-basic -->
```clojure
(register s (sdf-sphere 10))
```
<!-- /example-source -->

The SDF is materialized into a triangle mesh as soon as `register`
needs to display it.

## Notes

- The sphere is centered on the current turtle position at construction
  time via the default creation-pose; use `translate` or wrap in
  `turtle (…)` to position it elsewhere.
- For a sphere with a smooth blend into another shape, see `sdf-blend`.
- For a hollow shell, see `sdf-shell`.

## See also

- **SDF primitives:** `sdf-box`, `sdf-cyl`, `sdf-rounded-box`,
  `sdf-torus`
- **Booleans / blends:** `sdf-union`, `sdf-blend`, `sdf-difference`
- **Materialization:** `sdf->mesh`, `sdf-ensure-mesh`,
  `sdf-resolution!`
- **Transforms:** `translate`, `rotate`, `scale`, `attach`
