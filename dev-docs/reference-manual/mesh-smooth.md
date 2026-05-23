---
name: mesh-smooth
category: mesh-operations
since: ""
status: stable
---

# mesh-smooth

## Signature

`(mesh-smooth m & {:keys [sharp-angle smoothness refine]})`

## Description

Tangent-based mesh smoothing built on Manifold's `smoothOut` + `refine`.
Detects every interior edge whose dihedral angle is shallower than
`:sharp-angle`, marks it as a soft crease, and subdivides every triangle
into a denser tessellation whose new vertices lie on a C1-continuous
Bezier surface. Edges sharper than the threshold are preserved as
design corners; everything else gets filleted automatically.

The typical use case is the canonical "CSG result looks too synthetic"
fix: after `mesh-union` / `mesh-difference` produced a body of mostly
right-angle corners, `mesh-smooth` rounds every crease shallower than
the chosen threshold while keeping the intentional ones razor-sharp.

| Option        | Default | Meaning                                                                                       |
|---------------|---------|-----------------------------------------------------------------------------------------------|
| `:sharp-angle`| 100     | Edges with dihedral GREATER than this stay sharp. Set to 180 to smooth absolutely everything. |
| `:smoothness` | 0       | Fillet at the surviving sharp edges (0..1). 0 leaves them sharp; 1 rounds them.               |
| `:refine`     | 3       | Subdivision count. Each triangle becomes `n²` sub-triangles. Higher = denser, smoother.       |

Returns a new mesh; the input is unchanged.

## Parameters

- `m` — a **manifold** mesh (closed, watertight, no open edges).
- options keyword args as in the table above.

## Example

{{example: mesh-smooth-basic}}

<!-- example-source: mesh-smooth-basic -->
```clojure
;; CSG box with rounded edges — the canonical use case
(register rounded-widget
  (-> (mesh-difference (box 40 40 20) (cyl 12 30))
      (mesh-smooth :sharp-angle 100 :refine 3)))
```
<!-- /example-source -->

A drilled box has its right-angle creases automatically softened.
The hole's edges and the box's top corners are all considered
non-sharp at the 100° threshold and get filleted in one pass.

## Variations

{{example: mesh-smooth-soft-loft}}

<!-- example-source: mesh-smooth-soft-loft -->
```clojure
;; Loft body with smoothed creases at every path turn
(register smooth-bead
  (-> (loft (rect 20 10) (f 40) (th 90) (f 30))
      (mesh-smooth :sharp-angle 80 :refine 3)))
```
<!-- /example-source -->

Lowering `:sharp-angle` smooths even the joint-mode corners that
`extrude`/`loft` produce — anything sharper than 80° stays as a crease.

## Notes

- **Input must be manifold.** `mesh-smooth` rejects perforated shells
  (e.g. `shell` with `:style :voronoi` / `:lattice` / `:checkerboard`),
  triangle soups, and self-intersecting meshes. Manifold returns
  `status 2 (NotManifold)` in that case.
- If the input fails, run `mesh-diagnose` to identify the issue:
  open edges, non-manifold edges, or degenerate faces. `merge-vertices`
  is the typical quick fix when the problem is coincident vertices
  produced by CSG.
- Four alternatives when a perforated shell needs smoothing:
  rebuild as a CSG of solid pieces, go through `sdf-shell`
  + `sdf-difference`, smooth before perforating, or fall back to
  `mesh-laplacian` (which is topology-preserving and accepts non-manifold input).
- `mesh-smooth` is heavy: vertex and face counts grow with `refine²`.
  Start with defaults; only crank `:refine` if facets are still
  visible after rendering.
- For surgical edge filleting (only the edges you select, by direction
  or position), use `fillet` instead. It works on non-manifold input
  and lets you cherry-pick which edges to round.

## See also

- **Guide:** placeholder → cap. 7 (Mesh operations)
- **Related:** `mesh-refine`, `fillet`, `chamfer`, `mesh-diagnose`,
  `mesh-laplacian`, `merge-vertices`
