---
name: shape-offset
category: 2d-shapes
since: ""
status: stable
---

# shape-offset

## Signature

`(shape-offset shape delta)`
`(shape-offset shape delta & {:keys [join-type]})`

## Description

Expand or contract a 2D shape's outline by a fixed distance. Positive
`delta` grows the contour outward; negative `delta` shrinks it inward.
Implemented with Clipper2; holes are offset in the opposite direction so
hollow shapes thicken or thin as expected. Does not modify turtle state.

Accepts a single shape or a vector of shapes.

## Parameters

- `shape` — a 2D shape (or a vector of shapes).
- `delta` — offset distance. Positive for expansion, negative for
  contraction.
- `:join-type` — corner style: `:round` (default), `:square`, or `:miter`.

## Example

{{example: shape-offset-basic}}

<!-- example-source: shape-offset-basic -->
```clojure
(register pad (extrude (shape-offset (rect 30 20) 3) (f 4)))
```
<!-- /example-source -->

Round the outline of a rectangle by expanding it 3 units with the default
round joins.

## Variations

{{example: shape-offset-inner}}

<!-- example-source: shape-offset-inner -->
```clojure
(def outer (shape-union (rect 20 40) (rect 40 20)))
(def inner (shape-offset outer -3))
(register cross-tube (extrude (shape-difference outer inner) (f 10)))
```
<!-- /example-source -->

Building a thin-walled cross tube: contract the outer outline by 3 units
and subtract the result to get a uniform 3-unit wall.

## Notes

- Negative `delta` smaller (in absolute value) than the shape's local
  thickness collapses parts of the contour. The behaviour mirrors
  Clipper2's: regions that disappear are removed.
- `:miter` joins fall back to bevel-like behaviour when the corner is too
  sharp.

## See also

- **Related:** `fillet-shape`, `chamfer-shape`, `shape-bridge`,
  `shape-difference`
