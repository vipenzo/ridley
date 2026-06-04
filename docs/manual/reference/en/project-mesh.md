---
name: project-mesh
category: mesh-operations
since: ""
status: stable
---

# project-mesh

## Signature

`(project-mesh mesh-or-name)`

## Description

Project a mesh onto the plane orthogonal to the turtle's heading and
return its silhouette — the outline of the mesh's shadow as seen
looking along the heading. Returns a vector of 2D shapes in the
plane's local coordinates, like `slice-mesh`.

Whereas `slice-mesh` returns the cross-section AT a plane,
`project-mesh` returns the shadow OF a mesh as seen looking along the
heading. Useful for pulling a 2D footprint out of a 3D shape — e.g.
to extrude a slightly larger silhouette as a clearance pocket, or to
generate a planar template that traces the shape's overall outline.

Like `slice-mesh`, accepts a mesh value, a registered name, or an
SDF node (auto-materialised at the current `*sdf-resolution*`). Output
shapes carry `:preserve-position? true`. Holes inside the projected
silhouette are returned as separate inner shapes.

## Parameters

- `mesh-or-name` — a mesh value, a registered keyword, or an SDF
  node.

## Example

{{example: project-mesh-basic}}

<!-- example-source: project-mesh-basic -->
```clojure
;; Top-down silhouette of a sphere — looking down the +Z axis
(register ball (sphere 12))

(tv 90)                            ; heading now points up
(let [silhouette (first (project-mesh :ball))]
  (stamp silhouette))
```
<!-- /example-source -->

The sphere's silhouette is a circle. The stamp lands at the
projection plane in the turtle's frame.

## Variations

{{example: project-mesh-clearance-pocket}}

<!-- example-source: project-mesh-clearance-pocket -->
```clojure
;; Build a slightly larger silhouette of a tilted neck and use it as a cutter
(register neck (extrude (rect 12 6) (tv 30) (f 40)))

(def cut
  (turtle
    (tv 90) (f 20)                 ; position at projection plane
    (let [s (first (project-mesh :neck))
          bigger (scale-shape s 1.05)]
      (extrude bigger (f 10)))))

(register pocket (mesh-difference (box 60 30 30) cut))
```
<!-- /example-source -->

The neck's silhouette is captured, expanded by 5%, and re-extruded
into a clearance cutter. A single boolean subtracts the cut from a
hosting block.

## Notes

- The heading vector is the projection direction. Setting the turtle
  pose with `(tv …)`, `(th …)`, `(reset)` etc. before calling
  `project-mesh` is the standard way to choose the viewing axis.
- Compared to `slice-mesh`: a slice catches only geometry on the
  plane, a projection collapses everything along the heading. The
  silhouette is generally a superset of any single cross-section.
- Result shapes have `:preserve-position? true`, so they project
  onto the plane at their actual world-aligned coordinates when
  used as input to `stamp` or `extrude`.

## See also

- **Related:** `slice-mesh`, `slice-at-plane`, `stamp`,
  `path-to-shape`, `scale-shape`
