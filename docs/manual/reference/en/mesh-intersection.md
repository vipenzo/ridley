---
name: mesh-intersection
category: mesh-operations
since: ""
status: stable
---

# mesh-intersection

## Signature

`(mesh-intersection a b)`
`(mesh-intersection a b c …)`
`(mesh-intersection [a b c …])`

## Description

Compute the Boolean intersection of two or more meshes — the
watertight volume that is inside every input. Returns a new manifold
mesh; inputs are unchanged.

Like `mesh-union`, the variadic and vector forms are equivalent
specifications of the same multi-operand CSG call. Manifold can plan
multi-way intersections as a single batch, so passing all operands at
once is cheaper than chaining pairwise calls.

## Parameters

- `a`, `b`, … — manifold meshes.
- `[a b c …]` — alternatively, a single vector.

## Example

{{example: mesh-intersection-basic}}

<!-- example-source: mesh-intersection-basic -->
```clojure
(register cube (box 30))
(register ball (sphere 18))

(register rounded (mesh-intersection cube ball))
(hide :cube) (hide :ball)
```
<!-- /example-source -->

Intersecting a cube with a sphere yields a cube whose edges and
corners are rounded by the sphere — a classic CSG primitive for
chamfered blocks.

## Variations

{{example: mesh-intersection-many}}

<!-- example-source: mesh-intersection-many -->
```clojure
;; Tetrahedral cuts: intersect a sphere with four half-spaces (large boxes)
(register stone
  (mesh-intersection
    (sphere 15)
    (attach (box 30) (tv  35) (f 10))
    (attach (box 30) (tv -35) (f 10))
    (attach (box 30) (th  90) (f 10))
    (attach (box 30) (th -90) (f 10))))
```
<!-- /example-source -->

Four cutting planes-as-boxes are intersected with a sphere in a
single CSG call. The vector form would work too: `(mesh-intersection
(into [sphere] half-spaces))`.

## Notes

- Inputs must be manifold. If the inputs do not overlap, the result
  is empty: Manifold returns an empty mesh, not an error.
- Intersection is commutative and associative; argument order does
  not affect the geometry of the result, only the order of vertices
  in the output (which is normally irrelevant).
- For approximation via the smallest convex shape containing the
  inputs, use `mesh-hull` instead — it ignores actual containment and
  builds the convex hull regardless of overlap.

## See also

- **Related:** `mesh-union`, `mesh-difference`, `mesh-hull`,
  `concat-meshes`
