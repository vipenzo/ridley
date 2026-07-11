---
name: convex?
category: mesh-operations
since: ""
status: stable
---

# convex?

## Signature

`(convex? mesh)`
`(convex? mesh epsilon)`

## Description

Test whether a mesh is convex, via the hull-ratio test:

```
volume(mesh) / volume(mesh-hull(mesh)) >= 1 - epsilon
```

A convex mesh coincides with its own convex hull, so the ratio sits
at (numerically) 1; any concavity removes volume from the mesh
without shrinking the hull, pulling the ratio down. `epsilon`
defaults to `0.01` — calibrated against measured ratios across both
smooth (tessellated sphere, cylinder) and faceted (box, hex prism)
convex shapes, all landing at 0.99999999+ regardless of tessellation
coarseness, versus 0.875 and below for genuinely non-convex shapes
(a box with an internal cavity, an L-shaped prism, a torus) — a wide
margin on both sides of the default threshold.

An empty mesh (no faces) is convex by definition — the empty set is
convex — and returns `true` immediately, without invoking Manifold.

`convex?` accepts a mesh or an SDF node (auto-materialized).

## Parameters

- `mesh` — the mesh (or SDF node) to test.
- `epsilon` (optional) — convexity tolerance, default `0.01`. Lower
  values are stricter (require the mesh to be closer to its own
  hull); higher values are more permissive.

## Example

{{example: convex-p-basic}}

<!-- example-source: convex-p-basic -->
```clojure
(def box1 (extrude (rect 10 10) (f 10)))
(def frame (mesh-difference box1 (mesh-hull box1)))
(out (str "box convex? " (convex? box1)))
(out (str "frame convex? " (convex? frame)))
```
<!-- /example-source -->

## Notes

- Uses `mesh-hull` internally — cost is roughly one hull computation
  plus two volume reads (cheap once the mesh is built; live-compatible
  even on meshes with several thousand triangles).
- This is a numeric hull-ratio test, not an exact geometric
  convexity proof. For polyhedra with known deduplicated planes, an
  exact O(V·F) test is more appropriate — that lives in the STL
  convex-decomposition converter, not here.

## See also

- **Related:** `mesh-hull`, `mesh-split`, `mesh-diagnose`
