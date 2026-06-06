---
name: slice-mesh
category: mesh-operations
since: ""
status: stable
---

# slice-mesh

## Signature

`(slice-mesh mesh-or-name)`
`(slice-mesh mesh-or-name :on rail-mark/t)`

## Description

Cross-section a mesh at the plane defined by the turtle's current
position and heading. Returns a vector of 2D shapes — the closed
contours where the slicing plane cuts through the mesh — in the
plane's local coordinates.

The heading vector is the plane normal; the turtle's right and up
become the shapes' local X and Y. Output shapes carry
`:preserve-position? true`, so `stamp` renders them at their exact
plane-local coordinates rather than centering them.

`slice-mesh` accepts a mesh value, a registered mesh name (keyword),
or an SDF node (auto-materialized via the current `*sdf-resolution*`).

For a plane defined explicitly by point and normal — without using
the turtle — see `slice-at-plane`.

### `:on` — recover the generative profile

`(slice-mesh mesh :on t)` is a different operation: instead of a geometric
cut, it hands back the **generative profile** that was swept to build the
mesh — *with its profile marks attached* — so re-extruding/lofting it
reproduces a mesh carrying the same marks. `t` is a rail `(mark …)` name or a
fraction `t∈[0,1]`. For a morphing `loft` (`tapered`/`twisted`/…) it returns
the cross-section **at t** (the actual scaled/rotated shape, with marks riding
the morphed points); for `extrude` the profile is constant. Requires a mesh
built by `extrude`/`loft` from a marked profile.

## Parameters

- `mesh-or-name` — a mesh value, a registered keyword, or an SDF
  node.

## Example

{{example: slice-mesh-basic}}

<!-- example-source: slice-mesh-basic -->
```clojure
;; Slice a revolved cup at a horizontal plane and stamp the cross-section
(register cup (revolve (shape (f 20) (th -90) (f 30) (th -90) (f 15))))

(tv 90) (f 15)                ; position turtle at the slice plane
(stamp (slice-mesh :cup))      ; visualise the section contour
```
<!-- /example-source -->

The cup is sliced at the plane perpendicular to the turtle's heading
at Z=15. The resulting contour stamps onto the viewport at the exact
plane location.

## Variations

{{example: slice-mesh-from-sdf}}

<!-- example-source: slice-mesh-from-sdf -->
```clojure
;; Slice an SDF — auto-materialized at the current *sdf-resolution*
(register profile
  (extrude (first (slice-mesh (sdf-difference (sdf-sphere 15) (sdf-cyl 5 30))))
           (u 10)))
```
<!-- /example-source -->

When the source is an SDF, slicing converts it to a mesh at the
current resolution and then sections it. The first contour of the
slice is extruded into a new solid — a common pattern for "build a
2D profile out of a 3D parametric shape".

## Notes

- A slice that misses the mesh entirely returns an empty vector.
  A slice that catches multiple disjoint contours returns one shape
  per contour; holes are returned as inner shapes with the same
  `:preserve-position?` flag.
- Shapes use the plane's local coordinates. After producing them you
  can immediately feed them to `extrude`, `revolve`, or another
  `stamp` — all 2D operations work in the same local frame.
- For exact contour reproducibility (e.g. cutting a sheet to match a
  3D shape's cross-section), `slice-mesh` is preferred over
  `project-mesh`: a slice catches only the geometry intersecting the
  plane, whereas a projection collapses everything in the heading
  direction onto the plane.

## See also

- **Related:** `slice-at-plane`, `project-mesh`, `stamp`,
  `path-to-shape`
