---
name: mesh-union
category: mesh-operations
since: ""
status: stable
---

# mesh-union

## Signature

`(mesh-union a b)`
`(mesh-union a b c …)`
`(mesh-union [a b c …])`

## Description

Compute the Boolean union of two or more 3D meshes — the watertight
volume that includes every point in any input. Returns a new manifold
mesh; inputs are unchanged.

`mesh-union` uses Manifold's CSG engine. Each input must be a closed
manifold (watertight, no self-intersections). For repair tools when an
input is rejected, see `mesh-diagnose` and the cleanup utilities
listed there.

The variadic form unions all arguments in sequence; the vector form
takes a single sequence (e.g. the result of `(for …)`) without
splatting.

## Parameters

- `a`, `b`, … — manifold meshes.
- `[a b c …]` — alternatively, a single vector of meshes.

## Example

{{example: mesh-union-basic}}

<!-- example-source: mesh-union-basic -->
```clojure
(register a (box 20))
(f 15)
(register b (sphere 12))
(register joined (mesh-union a b))
(hide :a) (hide :b)
```
<!-- /example-source -->

Two primitives merge into a single watertight body. The original
meshes are hidden so the union is what shows up in the viewport.

## Variations

{{example: mesh-union-from-vector}}

<!-- example-source: mesh-union-from-vector -->
```clojure
(def studs
  (for [i (range 4)]
    (attach (cyl 2 6) (th (* i 90)) (f 12))))

(register cluster
  (mesh-union (box 30 30 5) (concat-meshes studs)))
```
<!-- /example-source -->

A common pattern: `(for …)` returns a vector of pieces;
`concat-meshes` stitches them into a single tool; one `mesh-union`
call merges everything in a single CSG operation instead of N-1
pairwise unions.

## Notes

- Inputs must be manifold. `mesh-union` of a perforated shell, a
  loose triangle soup, or a self-intersecting mesh fails. Run
  `mesh-diagnose` on a suspect input to identify what is wrong (open
  edges, non-manifold edges, degenerate triangles).
- `mesh-union` is associative: `(mesh-union (mesh-union a b) c)` is
  equivalent to `(mesh-union a b c)`. The variadic form is generally
  cheaper because Manifold can plan the operation as a single batch.
- For purely additive geometry where you do not need the result to be
  manifold (visualization only, or as the tool of a subsequent
  CSG call), prefer `concat-meshes` — it is linear instead of CSG.

## See also

- **Guide:** placeholder → cap. 7 (Mesh operations)
- **Related:** `mesh-difference`, `mesh-intersection`, `mesh-hull`,
  `concat-meshes`, `mesh-diagnose`
