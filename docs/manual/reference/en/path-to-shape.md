---
name: path-to-shape
category: 2d-shapes
since: ""
status: stable
---

# path-to-shape

## Signature

`(path-to-shape path)`
`(path-to-shape path :preserve-position true)`

## Description

Convert a recorded 3D path into a 2D shape by projecting onto the XY plane.
The resulting contour uses the X and Y components of each waypoint as the
shape's points and is automatically rewound to CCW so normals point in the
expected direction when extruded or revolved. Does not modify turtle state.

Useful for turning a path into a `revolve` profile, or for reusing the
silhouette of a turtle drawing as a 2D shape.

If the path seeds `(mark …)`s, each mark is recorded on the shape as a
reference to its **point index** (`:mark-refs`). When the shape is
`extrude`d / `loft`ed / `revolve`d, those marks become mesh `:anchors`
(reachable with `(move-to mesh :at :mark …)`). Because a mark is a point
index, it **rides any shape-fn that scales/rotates/displaces the points**
(`tapered`, `twisted`, …) — so a mark stays on its corner as the profile
morphs along a loft, and `(slice-mesh mesh :on t)` hands the morphed
profile back with its marks intact.

## Parameters

- `path` — a recorded path map.
- `:preserve-position` — when `true` (opt-in, default off), the resulting shape is
  marked `:preserve-position?`, so the profile's frame origin `[0 0]` becomes the
  extruded/stamped mesh's **creation pose** instead of the first traced vertex. Use
  it for image-traced outlines (see `image-board` / `edit-image-board`) so the
  creation pose lands on the turtle point you framed — typically *off* the contour.
  Off by default, so existing profiles are unchanged.

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
- Mark-tracking holds for shape-fns that preserve the point count/order
  (`tapered`, `twisted`, displacement). A shape-fn that **resamples** the
  profile (e.g. one that adds points) shifts the indices; marks then fall
  back to their base positions.

## See also

- **Related:** `stroke-shape`, `shape`, `revolve`, `image-board`, `edit-image-board`
