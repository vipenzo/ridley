---
name: poly-path
category: path
since: ""
status: stable
---

# poly-path

## Signature

`(poly-path x1 y1 x2 y2 …)`
`(poly-path [x1 y1 x2 y2 …])`

## Description

Build an open path from explicit 2D coordinate pairs. Internally,
`poly-path` reconstructs the turtle commands (`f`, `th`) needed to
walk through the given points in order, starting at the origin and
heading along +X. The result is a path map identical in structure to a
`path` recording.

`poly-path` is the path counterpart of `poly` for shapes: it accepts
the same flat-pair or vector input and produces an open trajectory.

For a closed loop returning to the starting point, use
`poly-path-closed`.

## Parameters

- `x1 y1 x2 y2 …` — coordinate pairs in the XY plane, either as a flat
  argument list or wrapped in a single vector.

## Example

{{example: poly-path-basic}}

<!-- example-source: poly-path-basic -->
```clojure
(def L (poly-path 0 0 30 0 30 20 50 20))
(register stair (extrude (circle 1) L))
```
<!-- /example-source -->

Three segments traced through four points. The extrusion follows the
polyline exactly.

## Variations

{{example: poly-path-vector}}

<!-- example-source: poly-path-vector -->
```clojure
(def pts [0 0 20 0 20 20 0 20 0 40])
(register zigzag (extrude (circle 1) (poly-path pts)))
```
<!-- /example-source -->

The vector form is convenient when coordinates are computed
programmatically.

## Notes

- `poly-path` operates in 2D (XY plane). For 3D trajectories use `path`
  with explicit `(tv …)` / `(u …)` commands, or chain `poly-path`s with
  the right reorientation between them.
- The path starts at the first point of the input — but the recording
  begins at the turtle's current pose (origin + heading along +X by
  default). To anchor a `poly-path` at a non-default pose, use
  `with-path`, `follow-path`, or `extrude` from the desired starting
  pose.
- For closed contours that auto-return to the starting point, see
  `poly-path-closed`.

## See also

- **Related:** `poly-path-closed`, `path`, `quick-path`, `poly`
