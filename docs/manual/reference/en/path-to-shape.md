---
name: path-to-shape
category: 2d-shapes
since: ""
status: stable
---

# path-to-shape

## Signature

`(path-to-shape path)`

## Description

Convert a recorded 3D path into a 2D shape by projecting onto the XY plane.
The resulting contour uses the X and Y components of each waypoint as the
shape's points and is automatically rewound to CCW so normals point in the
expected direction when extruded or revolved. Does not modify turtle state.

Useful for turning a path into a `revolve` profile, or for reusing the
silhouette of a turtle drawing as a 2D shape.

## Parameters

- `path` — a recorded path map.

## Example

{{example: path-to-shape-basic}}

<!-- example-source: path-to-shape-basic -->
```clojure
(def silhouette (path (f 5) (th 80) (f 15) (arc-h 5 -160) (f 15)))
(register vase (revolve (path-to-shape silhouette)))
```
<!-- /example-source -->

A turtle silhouette is captured as a path, projected to 2D, and revolved
into an axisymmetric vase.

## Notes

- The Z component of path waypoints is ignored. Paths that escape the XY
  plane will be flattened by projection.
- For stroking a path into a filled outline (rather than projecting it as
  a closed contour), use `stroke-shape`.

## See also

- **Related:** `stroke-shape`, `shape`, `revolve`
