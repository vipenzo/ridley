---
name: path-length
category: path
since: ""
status: stable
---

# path-length

## Signature

`(path-length path)`

## Description

Total length of an **open** path: the sum of the euclidean distances
between consecutive turtle waypoints, with **no** closing edge back to
the start (unlike `shape-perimeter`, which closes the ring).

The path is replayed from the world origin (heading `+X`, up `+Z`) with
the real 3D turtle, so the result is the true **3D** length. For a
planar path it equals the 2D length.

Returns `nil` when the input is not a path.

## Parameters

- `path` — a path map (`{:type :path …}`), e.g. from the `path` macro.

## Example

{{example: path-length-basic}}

<!-- example-source: path-length-basic -->
```clojure
(def trail (path (f 30) (th 90) (f 40)))
(out (str "length = " (path-length trail)))   ;; => 70
```
<!-- /example-source -->

## Notes

- **Waypoint polyline.** The measurement runs over the corners between
  forward (`f`) moves. For a path built from curved/bezier segments this
  is the *control polygon*, which **underestimates** the smooth length.
  For a closed 2D profile, prefer `shape-perimeter`.
- The length is computed in the path's own frame and does not reflect any
  positioning the turtle imposes at extrusion time.
- For a closed shape contour, use `shape-perimeter` /
  `shape-perimeters`.

## See also

- **Related:** `shape-perimeter`, `shape-perimeters`, `bounds-2d`,
  `path`, `mark-pos`
