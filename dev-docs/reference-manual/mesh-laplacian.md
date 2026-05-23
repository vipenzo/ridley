---
name: mesh-laplacian
category: mesh-operations
since: ""
status: stable
---

# mesh-laplacian

## Signature

`(mesh-laplacian mesh)`
`(mesh-laplacian mesh & {:keys [iterations lambda mu feature-angle]})`

## Description

Selective Taubin λ|μ smoothing. Identifies vertices that sit on creases shallower than `:feature-angle` and moves only those — large flat regions and intentionally sharp design edges are left untouched. Returns a new mesh with the same vertex count, face count, and topology; only the vertex positions change.

Each iteration runs two passes: a positive `λ` pass that averages each selected vertex toward its neighbours (smoothing), followed by a negative `μ` pass that pushes it back along the same Laplacian (inflation). The pair cancels the volumetric shrinkage that pure averaging would produce, so the mesh stays roughly the same size while creases get rounded out.

`mesh-laplacian` is topology-preserving and accepts non-manifold input — making it the practical alternative to `mesh-smooth` when the mesh has apertures (perforated shells, slatted facades) and Manifold rejects it. It is also useful for staircase-aliasing reduction on heightmap-derived meshes and for softening SDF marching-cubes artefacts.

## Parameters

- `mesh` — any mesh value (manifold or not).
- `:iterations n` — number of `λ + μ` pass pairs. Default `10`.
- `:lambda l` — positive smoothing factor (toward neighbours). Default `0.5`.
- `:mu m` — negative inflation factor (away from neighbours). Default `-0.53`. Standard Taubin values keep `|μ| > |λ|` slightly.
- `:feature-angle deg` — dihedral threshold for "smoothable" classification. Edges with dihedral angle **below** this stay sharp; everything else is smoothed. Default `150`.

## Example

{{example: mesh-laplacian-perforated-shell}}

<!-- example-source: mesh-laplacian-perforated-shell -->
```clojure
;; Voronoi shell is non-manifold — mesh-smooth would reject it
(register skin (shell (sphere 20) 2 :style :voronoi :n 14))

;; mesh-laplacian softens the aliased aperture rims without touching topology
(register smooth-skin (mesh-laplacian skin :iterations 6))
```
<!-- /example-source -->

A perforated shell mesh has open edges and cannot pass through `mesh-smooth`. `mesh-laplacian` operates on the raw vertex array, so the same mesh can be smoothed in place; only the vertex positions change, the apertures stay open.

## Variations

{{example: mesh-laplacian-aggressive}}

<!-- example-source: mesh-laplacian-aggressive -->
```clojure
;; Raise the feature angle to smooth more aggressively
(register noisy-bowl (revolve (shape (f 30) (th -90) (f 20))))
(register soft (mesh-laplacian noisy-bowl
                               :iterations 20
                               :feature-angle 170))
```
<!-- /example-source -->

A higher `:feature-angle` widens the "smoothable" zone; combined with more iterations it produces a strongly relaxed surface. Lower the value when too many design features start to dissolve.

## Notes

- **Topology preserving.** The vertex/face count is unchanged: no vertex collapses, no subdivision. Use `mesh-smooth` for refining tessellation with smoothing, or `mesh-simplify` for decimation.
- **Vertex count stays large.** Because no decimation happens, an over-smoothed mesh keeps the same triangle budget as the input. If file size matters, follow `mesh-laplacian` with `mesh-simplify`.
- **Default schedule is conservative.** The pre-tuned `λ = 0.5`, `μ = −0.53` are Taubin's recommended values and produce mild smoothing per iteration. Increase `:iterations` rather than `:lambda` for stronger effects — keeping `|μ| > |λ|` is what prevents volume drift.
- **Mask logging.** The function prints how many vertices were classified as smoothable so you can tune `:feature-angle` against the geometry at hand. Tighten the angle if too many features dissolve.
- **Manifold-safe input is fine but not required.** Unlike `mesh-smooth`, `mesh-laplacian` does not call Manifold WASM and has no watertightness requirement.

## See also

- **Related:** `mesh-smooth`, `mesh-simplify`, `merge-vertices`,
  `mesh-diagnose`
