---
name: mesh-hull
category: mesh-operations
since: ""
status: stable
---

# mesh-hull

## Signature

`(mesh-hull m1 m2)`
`(mesh-hull m1 m2 m3 …)`
`(mesh-hull [m1 m2 m3 …])`

## Description

Compute the convex hull of one or more meshes — the smallest convex
manifold mesh that contains every input vertex. Returns a new
manifold mesh.

Convex hulls are useful for capsules (hull of two spheres), envelopes
(hull of a scattered cloud of pieces), or as a quick approximation
when the exact shape does not matter — for instance, when building a
collision or selection volume around a complex assembly.

## Parameters

- `m1`, `m2`, … — meshes whose combined vertices define the hull.
  Inputs need not be manifold themselves; only the vertex positions
  participate.
- `[m1 m2 …]` — alternatively, a single vector.

## Example

{{example: mesh-hull-capsule}}

<!-- example-source: mesh-hull-capsule -->
```clojure
(register a (sphere 10))
(f 25)
(register b (sphere 10))

(register capsule (mesh-hull a b))
(hide :a) (hide :b)
```
<!-- /example-source -->

The hull of two separated spheres is a capsule — a cylinder smoothly
capped at each end.

## Variations

{{example: mesh-hull-from-vector}}

<!-- example-source: mesh-hull-from-vector -->
```clojure
;; Envelope around a ring of pieces
(def pieces
  (for [i (range 6)]
    (attach (sphere 3) (th (* i 60)) (f 15))))

(register envelope (mesh-hull pieces))
```
<!-- /example-source -->

The hull of a procedurally generated cloud yields a convex envelope
suitable as a wireframe or collision volume.

## Notes

- `mesh-hull` ignores the input meshes' connectivity — only the
  vertex cloud matters. Non-manifold or self-intersecting inputs are
  acceptable, since the hull is built from a point set.
- The output is always manifold by construction.
- For convex hulls of 2D shapes, see `shape-hull`.
- For 3D bridging that is NOT a convex hull (i.e. blend two meshes
  preserving non-convex profiles), use `loft-between` between
  cross-sections rather than `mesh-hull`.

## See also

- **Related:** `shape-hull`, `mesh-union`, `mesh-intersection`,
  `loft-between`
