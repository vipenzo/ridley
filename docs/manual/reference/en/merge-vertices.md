---
name: merge-vertices
category: mesh-operations
since: ""
status: stable
---

# merge-vertices

## Signature

`(merge-vertices mesh)`
`(merge-vertices mesh epsilon)`

## Description

Collapse coincident vertices in a mesh and remap face indices to share the surviving canonical index. Two vertices are considered coincident when their positions agree to within `epsilon` units in every axis (default `1e-6`). Returns a new mesh with deduplicated vertices, the face table remapped, and any degenerate faces (those that collapse to an edge or point as a result) removed.

`merge-vertices` is the standard quick fix for non-manifold meshes whose only problem is duplicate vertices at boolean seams. CSG operations occasionally produce two near-identical vertices at a corner where multiple operands meet; the topology *should* be a single shared vertex but ends up as a pair of unstitched ones — which Manifold reports as `non-manifold-edges > 0`. A single merge pass usually makes the mesh watertight again.

## Parameters

- `mesh` — any mesh value.
- `epsilon` — quantisation tolerance. Vertices whose coordinates round to the same `1/epsilon` grid bucket are merged. Default `1e-6`.

## Example

{{example: merge-vertices-csg-fixup}}

<!-- example-source: merge-vertices-csg-fixup -->
```clojure
;; CSG of two flush boxes can produce coincident vertices at the seam
(register seam
  (mesh-union (box 20 20 10)
              (attach (box 20 20 10) (u 10))))

(out (str "before: " (:is-watertight? (mesh-diagnose seam))))

(register clean (merge-vertices seam))
(out (str "after:  " (:is-watertight? (mesh-diagnose clean))))
```
<!-- /example-source -->

Two cuboids stacked at exactly Z=10 share a flat interface where the boolean result may carry duplicate vertices. `merge-vertices` collapses them; `mesh-diagnose` confirms watertightness before and after.

## Variations

{{example: merge-vertices-tolerance}}

<!-- example-source: merge-vertices-tolerance -->
```clojure
;; Loosen the tolerance for meshes whose duplicates differ by more than 1e-6
(register noisy (mesh-difference (cyl 15 20) (cyl 10 25)))
(register cleaned (merge-vertices noisy 1e-4))
```
<!-- /example-source -->

When the source has accumulated numerical drift (after several chained CSG ops, or after import from a lower-precision format), raise `epsilon` to a value larger than the drift but smaller than the smallest meaningful feature.

## Notes

- **Tolerance is a quantisation grid, not a clustering radius.** Each coordinate is rounded to `1/epsilon`; vertices whose rounded triple matches are merged. Two vertices `0.999999 1e-6` apart can land in adjacent buckets and miss the merge — increase `epsilon` if that happens.
- **Degenerate-face removal.** After remap, faces whose three indices are not pairwise distinct are dropped. Vertices left unused are then compacted out. The output is always smaller or equal to the input in both counts.
- **Choose `epsilon` smaller than your smallest feature.** Too aggressive a tolerance fuses real distinct vertices and destroys geometry. The default `1e-6` is safe for procedurally-generated millimetre-scale meshes.
- **Quick check.** Run `mesh-diagnose` before and after — `:non-manifold-edges` and `:open-edges` are the two metrics that should drop. If they do not, the issue is not coincident vertices and `merge-vertices` is not the right tool.

## See also

- **Related:** `mesh-diagnose`, `mesh-simplify`, `mesh-smooth`,
  `manifold?`
