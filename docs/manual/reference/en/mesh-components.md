---
name: mesh-components
category: mesh-operations
since: ""
status: stable
---

# mesh-components

## Signature

`(mesh-components mesh)`

## Description

Decompose a mesh into its **connected components** — the disjoint
solids that make it up — via Manifold's `Decompose()`. This is a
purely topological split (union-find on connected components): it is
exact, involves no boolean operation, and is cheap (well under a
millisecond even on meshes with thousands of triangles).

Returns a **vector of meshes in a deterministic order**: decreasing
volume, with ties broken by a lexicographic comparison of the
vertex-mean centroid (x, then y, then z). This order is a contract,
not an incidental detail — it lets you destructure the result
positionally (`(let [[a b] (mesh-components m)] …)`) and rely on `a`
and `b` naming the same pieces every run, regardless of how the mesh
was constructed. (Manifold's own decompose order is
implementation-defined; the ordering is imposed here.)

A single-component mesh returns a one-element vector — `[mesh]`, the
whole thing. An empty mesh (no faces) returns `[]`.

Each component inherits the source mesh's creation-pose, material, and
anchors (the same single-source metadata policy as `mesh-split` and
`mesh-hull`).

`mesh-components` accepts a mesh or an SDF node (auto-materialized).

## Parameters

- `mesh` — the mesh (or SDF node) to decompose.

## Example

{{example: mesh-components-basic}}

<!-- example-source: mesh-components-basic -->
```clojure
;; A U-shape cut across its base leaves two separate prongs in ONE
;; mesh — a single mesh that is topologically two components. No plane
;; separates them; the split is topological.
(register u-base (extrude (rect 30 10) (f 20)))
(register prong-l (extrude (rect 8 10) (f 20)))
(register prong-r (extrude (rect 8 10) (f 20)))
;; (assume the three are positioned so the two prongs are disjoint)
(def two-prongs (concat-meshes (get-mesh :prong-l) (get-mesh :prong-r)))
(def parts (mesh-components two-prongs))
(out (str "components: " (count parts)))   ; => 2
```
<!-- /example-source -->

## Notes

- The natural companion to `convex?` for deciding when a piece is
  "done": a piece whose every connected component is convex is finished
  even if the piece as a whole reads as concave (a U with two convex
  prongs has a hull-ratio near 0.5, but nothing about it needs
  cutting — only separating). `(every? convex? (mesh-components m))`
  is exactly that per-component finiteness test.
- Because the two sort keys (volume and vertex-mean centroid) are both
  order-independent, the returned vector is invariant to any
  construction- or vertex-order permutation of the source — the same
  geometry always decomposes to the same ordered vector.
- Separation is free relative to cutting: no plane, no mark. Reach for
  it whenever a single `mesh-split` half comes back as several
  disconnected solids.

## See also

- **Related:** `split-parts`, `convex?`, `mesh-split`, `concat-meshes`
