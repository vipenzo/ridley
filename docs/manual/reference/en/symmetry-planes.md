---
name: symmetry-planes
category: mesh-operations
since: ""
status: stable
---

# symmetry-planes

## Signature

`(symmetry-planes mesh)`
`(symmetry-planes mesh epsilon)`

## Description

Propose the **verified** mirror-symmetry planes of a mesh, as a vector of
poses `{:position :heading :up}` — `heading` is the plane normal, and the
pose is directly usable to place the turtle (`goto`/`mark`). Planes are
ordered by quality (symmetric-difference ratio ascending; best first).

A pure function of the mesh. The pipeline:

1. **Area-weighted PCA** on the face centroids gives up to three
   candidate planes through the centroid, normal to the principal axes.
   Weighting each face centroid by triangle area is mandatory — PCA on
   the raw vertices is defeated by uneven tessellation (a denser region
   drags the centroid off the true plane).
2. **Degenerate fallback** — when two principal moments are nearly equal
   (a square or N-fold object, whose PCA axes in the tied subspace are
   arbitrary and would spuriously fail verification), the
   bounding-box axes are added as extra candidates.
3. **Verification** — each candidate is confirmed with the same cascade
   as `mirror?`; only the promoted planes are returned (the contract is
   *verified planes only* — callers never filter).

An asymmetric (or empty/degenerate) mesh returns `[]`.

## Parameters

- `mesh` — the mesh (or SDF node) to analyze.
- `epsilon` (optional) — tolerance passed to the `mirror?` verification.

## Example

{{example: symmetry-planes-basic}}

<!-- example-source: symmetry-planes-basic -->
```clojure
(register block (box 10 20 30))
(def planes (symmetry-planes (get-mesh :block)))
(out (str (count planes) " symmetry planes"))   ; 3 for a rectangular box
;; place the turtle on the best plane:  (goto (first planes))
```
<!-- /example-source -->

## Notes

- Cost ~250–450 ms — **on-demand**. In `edit-mesh-split` the result is
  cached per open piece (pieces are immutable) and driven by an explicit
  "propose symmetry plane" gesture.
- Returns only planes that actually verify, so an empty vector is a real
  answer ("no symmetry here"), not a failure.

## See also

- **Related:** `mirror?`, `mesh-mirror`, `mesh-split`
