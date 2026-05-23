---
name: mesh-simplify
category: mesh-operations
since: ""
status: stable
---

# mesh-simplify

## Signature

`(mesh-simplify mesh ratio)`
`(mesh-simplify mesh ratio :max-passes n)`

## Description

Reduce the triangle count of a mesh by collapsing short edges until the target fraction is reached. Each collapse pair-merges two endpoints into their midpoint, removes the two degenerate faces incident to the edge, and remaps every reference to the deleted vertex in surviving faces. Returns a new mesh; the input is unchanged.

`ratio` is the target fraction of the original face count, in `0..1`. A ratio of `0.25` aims for roughly a quarter of the original triangles; the algorithm always keeps at least 4 faces. The threshold is auto-tuned from the mean edge length of the input — small ratios collapse longer edges, large ratios stop early — and grows by 1.5× per pass when progress stalls, up to `:max-passes` passes (default 20).

`mesh-simplify` is the standard decimation tool for procedurally generated meshes that ship with more triangles than needed. Typical uses are dropping the triangle count of an SDF materialisation or of a heightmap-derived mesh before export. The result preserves `:creation-pose` and `:material` from the source.

## Parameters

- `mesh` — any mesh value.
- `ratio` — target face fraction, in `0..1`. Values clamped against a minimum of 4 faces.
- `:max-passes n` — upper bound on collapse passes. Default `20`.

## Example

{{example: mesh-simplify-basic}}

<!-- example-source: mesh-simplify-basic -->
```clojure
;; Decimate a high-resolution sphere to ~25% of its faces
(def hi (sphere 20 48 24))
(register lo (mesh-simplify hi 0.25))

(out (str "before: " (count (:faces hi)) " faces"))
(out (str "after:  " (count (:faces lo)) " faces"))
```
<!-- /example-source -->

A sphere built at high resolution is decimated to a quarter of its triangles. The algorithm logs the before/after counts via `println`; you can also read them off the mesh value as shown.

## Variations

{{example: mesh-simplify-csg-cleanup}}

<!-- example-source: mesh-simplify-csg-cleanup -->
```clojure
;; A CSG result is often denser than needed for the final part
(register dense (mesh-difference (box 40 40 20) (cyl 12 30)))
(register light (mesh-simplify dense 0.4 :max-passes 30))
```
<!-- /example-source -->

CSG operations tend to over-tessellate near boolean seams. Decimation cuts the file size of an export without changing the silhouette appreciably; increasing `:max-passes` lets the threshold grow more times when the first pass leaves you above the target.

## Notes

- The collapse heuristic is **midpoint averaging**, not quadric error metrics: simple, predictable, and fast, but less topology-aware than QEM. Surface features that span only a handful of triangles can be over-smoothed at aggressive ratios — preview the result before committing to a small ratio.
- The algorithm preserves the mesh's `:creation-pose` and `:material`, so transform pipelines and color assignments survive decimation.
- Pure ClojureScript: no Manifold WASM dependency. Runs everywhere a Ridley mesh value is in scope.
- For *smoothing* (vertex re-positioning without removing triangles), prefer `mesh-laplacian`. For *coincident-vertex repair* before booleans, use `merge-vertices`. `mesh-simplify` is specifically about reducing triangle count.

## See also

- **Related:** `mesh-laplacian`, `merge-vertices`, `mesh-smooth`,
  `mesh-diagnose`
