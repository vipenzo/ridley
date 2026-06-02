---
name: mesh-refine
category: mesh-operations
since: ""
status: stable
---

# mesh-refine

## Signature

`(mesh-refine mesh n)`

## Description

Subdivide every triangle of a mesh into `n²` sub-triangles, without
smoothing. The new vertices lie on the original planar faces, so the
shape is unchanged — only the triangulation becomes denser.

`mesh-refine` is the lower-level companion of `mesh-smooth`: where
`mesh-smooth` invokes `smoothOut + refine` to lay new vertices on
Bezier tangents (rounding the geometry), `mesh-refine` skips the
tangent computation and just inserts vertices linearly. Useful only
when an operation later in the pipeline benefits from denser
triangulation (e.g. an SDF displacement, a per-vertex shader, or a
heightmap sample that would alias on a coarse mesh).

Returns a new mesh.

## Parameters

- `mesh` — a manifold mesh.
- `n` — subdivision count; each triangle becomes `n²` sub-triangles
  (`n=2` → 4-fold; `n=3` → 9-fold; etc.). Cost grows quadratically.

## Example

{{example: mesh-refine-basic}}

<!-- example-source: mesh-refine-basic -->
```clojure
(register coarse (box 10))
(f 25)
(register dense  (mesh-refine (box 10) 4))
```
<!-- /example-source -->

The two boxes are visually identical; `dense` has 16× the triangles.
Useful as a setup step before any operation that benefits from finer
sampling.

## Notes

- Subdivision is planar: the shape is preserved, only the
  triangulation is changed. For rounding while subdividing, use
  `mesh-smooth`.
- Manifold's `refine` is the underlying operation: input must be
  manifold; the result is too.
- Cost is `O(n² · faces)`. `n=2` doubles linear resolution; `n=4`
  is rarely needed.

## See also

- **Guide:** placeholder → cap. 7 (Mesh operations)
- **Related:** `mesh-smooth`, `mesh-simplify`, `mesh-diagnose`
