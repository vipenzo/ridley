---
name: mesh-to-heightmap
category: generative-operations
since: ""
status: stable
---

# mesh-to-heightmap

## Signature

`(mesh-to-heightmap mesh & {:keys [resolution bounds offset-x offset-y length-x length-y]})`

## Description

Rasterise a mesh's max-Z values onto a 2D grid, producing a heightmap.
Each grid cell stores the largest Z seen by any face covering that cell;
values are then normalised to `[0, 1]`. Useful for capturing surface
patterns from existing geometry so they can be reused as displacement
maps via the `heightmap` shape-fn. Does not modify turtle state.

## Parameters

- `mesh` — input mesh.
- `:resolution` — grid resolution (default `128`, square grid).
- `:bounds` — explicit XY bounds `[x0 y0 x1 y1]`. Overrides auto-bounds.
- `:offset-x`, `:offset-y` — custom origin of the sampling window.
- `:length-x`, `:length-y` — custom extent of the sampling window.

When neither `:bounds` nor offset/length options are given, the mesh's
XY bounding box is used.

## Example

{{example: mesh-to-heightmap-basic}}

<!-- example-source: mesh-to-heightmap-basic -->
```clojure
(def source (heightmap-to-mesh (weave-heightmap :resolution 64) :z-scale 0.5))
(def hm (mesh-to-heightmap source :resolution 128))
;; hm now usable with (heightmap shape hm :amplitude …)
```
<!-- /example-source -->

Round-trip: generate a heightmap, build a mesh from it, rasterise the
mesh back to a new heightmap. Useful when the source pattern is easier
to author as geometry than as a procedural function.

## Notes

- Cells not covered by any face come out as `0` after normalisation.
- The returned heightmap carries its original `:bounds`, `:z-min`,
  `:z-max` so `sample-heightmap` and `heightmap-to-mesh` can round-trip
  the values.
- For analytical weave-style patterns, prefer `weave-heightmap` — it
  skips the mesh/rasterise cycle.

## See also

- **Related:** `heightmap-to-mesh`, `weave-heightmap`, `sample-heightmap`,
  `heightmap`
