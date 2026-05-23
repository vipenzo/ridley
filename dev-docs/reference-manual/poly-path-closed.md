---
name: poly-path-closed
category: path
since: ""
status: stable
---

# poly-path-closed

## Signature

`(poly-path-closed x1 y1 x2 y2 …)`
`(poly-path-closed [x1 y1 x2 y2 …])`

## Description

Same as `poly-path`, but the resulting path closes back to the first
point. A final segment is appended from the last input coordinate
back to `(x1, y1)`. Useful when an extrusion or attachment needs to
follow a closed loop rather than an open polyline.

## Parameters

- `x1 y1 x2 y2 …` — coordinate pairs in the XY plane, flat list or
  single vector.

## Example

{{example: poly-path-closed-basic}}

<!-- example-source: poly-path-closed-basic -->
```clojure
(def hex
  (poly-path-closed
    20 0  10 17  -10 17  -20 0  -10 -17  10 -17))
(register frame (extrude-closed (rect 2 1) hex))
```
<!-- /example-source -->

A hexagonal loop driven by explicit vertices. Pairing it with
`extrude-closed` yields a continuous picture frame with no end caps.

## Notes

- The closing segment is generated automatically; do not repeat the
  first point at the end of the input.
- Coordinates are 2D (XY). For closed 3D loops, build them explicitly
  with `path` and the appropriate `(tv …)` commands.

## See also

- **Guide:** placeholder → cap. 5 (Paths and anchors)
- **Related:** `poly-path`, `extrude-closed`, `path`
