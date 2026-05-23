---
name: shape-union
category: 2d-shapes
since: ""
status: stable
---

# shape-union

## Signature

`(shape-union a b)`

## Description

Combine two 2D shapes into their union: the outline that encloses both.
Implemented with Clipper2; holes in either input are preserved in the
result, and overlapping regions are merged. Does not modify turtle state.

## Parameters

- `a`, `b` — 2D shapes.

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

## See also

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** `shape-difference`, `shape-intersection`, `shape-xor`,
  `shape-offset`, `shape-hull`
