---
name: heightmap-to-mesh
category: generative-operations
since: ""
status: stable
---

# heightmap-to-mesh

## Signature

`(heightmap-to-mesh hm)`
`(heightmap-to-mesh hm & {:keys [z-scale size]})`

## Description

Convert a heightmap to a flat mesh in the XY plane with Z values driven
by the heightmap. Useful for visualisation, debugging, or building a
mesh from a procedural heightmap so it can be passed to mesh booleans.
Does not modify turtle state.

The mesh has one vertex per grid cell and two triangles per quad. The
original bounds and `z-range` stored on the heightmap are used by
default; `:size` overrides the XY span and rescales Z proportionally.

## Parameters

- `hm` — a heightmap value.
- `:z-scale` — multiplier on the Z output (default `1.0`).
- `:size` — when set, the mesh is fit into a `size × size` square at the
  origin and Z scales with it.

## Example

{{example: heightmap-to-mesh-basic}}

<!-- example-source: heightmap-to-mesh-basic -->
```clojure
(def hm (weave-heightmap :threads 4 :resolution 64))
(register weave-tile (heightmap-to-mesh hm :z-scale 0.3))
```
<!-- /example-source -->

Visualise the analytical weave heightmap as a textured tile.

## Variations

{{example: heightmap-to-mesh-sized}}

<!-- example-source: heightmap-to-mesh-sized -->
```clojure
(def hm (weave-heightmap :resolution 96))
(register weave-tile (heightmap-to-mesh hm :size 40))
```
<!-- /example-source -->

`:size` rescales the mesh to fit in a 40×40 square at the origin, with Z
scaled proportionally.

## Notes

- Companion of `mesh-to-heightmap`: rasterise a mesh, then convert back
  to inspect what was captured.
- The resulting mesh is flat (in XY) regardless of the heightmap's
  original orientation.

## See also

- **Related:** `mesh-to-heightmap`, `weave-heightmap`, `sample-heightmap`,
  `heightmap`
