---
name: shape-union
category: 2d-shapes
since: ""
status: stable
---

# shape-union

## Signature

`(shape-union a b)`
`(shape-union a b c …)`
`(shape-union [a b c …])`

## Description

Combine two or more 2D shapes into their union: the outline that encloses
all of them. Implemented with Clipper2; holes in any input are preserved in
the result, and overlapping regions are merged. Does not modify turtle state.

## Parameters

- `a`, `b`, … — 2D shapes. May also be passed as a single vector `[a b …]`,
  matching the mesh `union` form.

## Example

{{example: shape-union-basic}}

<!-- example-source: shape-union-basic -->
```clojure
(def plus (shape-union (rect 30 10) (rect 10 30)))
(register cross (extrude plus (f 5)))
```
<!-- /example-source -->

A plus sign by unioning a horizontal and a vertical rectangle.

## Notes

- Holes are detected automatically from winding direction.
- All shape transforms (`scale`, `rotate`, `translate`, `morph-shape`) and
  extrusion operations propagate holes correctly.

- A reference image (`set-image`) on the first operand carries over to the
  result and stays clipped to the combined outline when stamped.

## See also

- **Related:** `shape-difference`, `shape-intersection`, `shape-xor`,
  `shape-offset`, `shape-hull`, `set-image`
