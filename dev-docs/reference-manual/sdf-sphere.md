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

Construct an SDF node for a sphere of radius `r`, centered on the
current turtle position. Returns an immutable map representing the SDF
tree — no geometry is computed until meshing happens (at `register`,
boolean with a mesh, or `sdf->mesh`).

Like mesh primitives (`sphere`, `box`, `cyl`), SDF primitives spawn at
the current turtle pose. A bare `(sdf-sphere 10)` after `(f 30)` lives
at `(30 0 0)`, not at the origin.

SDF spheres are the lightest possible SDF: a small tree with `:op`,
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

- The sphere inherits the current turtle position at construction
  time (heading/up are irrelevant since the sphere is symmetric). Use
  `translate` afterwards if you need to nudge it further.
- For a sphere with a smooth blend into another shape, see `sdf-blend`.
- For a hollow shell, see `sdf-shell`.

## See also

- **SDF primitives:** `sdf-box`, `sdf-cyl`, `sdf-cone`,
  `sdf-rounded-box`, `sdf-torus`
- **Booleans / blends:** `sdf-union`, `sdf-blend`, `sdf-difference`
- **Materialization:** `sdf->mesh`, `sdf-ensure-mesh`,
  `sdf-resolution!`
- **Transforms:** `translate`, `rotate`, `scale`, `attach`
