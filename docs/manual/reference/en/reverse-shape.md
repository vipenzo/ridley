---
name: reverse-shape
category: 2d-shapes
since: ""
status: stable
---

# reverse-shape

## Signature

`(reverse-shape shape)`

## Description

Reverse the winding order of a shape's points (and of each hole). This
flips the direction of the surface normals when the shape is later
extruded or revolved — useful when normals point the wrong way after a
boolean or custom construction. Also accepts a vector of shapes. Does not
modify turtle state.

## Parameters

- `shape` — a 2D shape, or a vector of shapes.

## Example

{{example: reverse-shape-basic}}

<!-- example-source: reverse-shape-basic -->
```clojure
(def cw-tri (poly 0 5  5 0  -5 0))  ;; clockwise winding
(register tri-flipped (extrude (reverse-shape cw-tri) (f 6)))
```
<!-- /example-source -->

A triangle defined CW; reversing makes it CCW so the extrusion produces
outward-facing normals.

## Notes

- Holes are reversed in lockstep with the outer contour so the relative
  winding (outer CCW, holes CW, or vice versa) is preserved.

## See also

- **Related:** `shape-difference`, `path-to-shape`
