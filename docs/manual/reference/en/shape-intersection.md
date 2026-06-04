---
name: shape-intersection
category: 2d-shapes
since: ""
status: stable
---

# shape-intersection

## Signature

`(shape-intersection a b)`
`(shape-intersection a b c …)`
`(shape-intersection [a b c …])`

## Description

Return the region where all the given shapes overlap. Implemented with
Clipper2; holes are preserved. Does not modify turtle state.

## Parameters

- `a`, `b`, … — 2D shapes. May also be passed as a single vector `[a b …]`,
  matching the mesh `intersection` form.

## Example

{{example: shape-intersection-basic}}

<!-- example-source: shape-intersection-basic -->
```clojure
(def lens (shape-intersection (translate (circle 15) -5 0)
                              (translate (circle 15) 5 0)))
(register lens-prism (extrude lens (f 3)))
```
<!-- /example-source -->

The overlap of two offset circles produces a lens-shaped contour.

## Notes

- Returns an empty shape when the inputs do not overlap.

## See also

- **Related:** `shape-union`, `shape-difference`, `shape-xor`,
  `shape-offset`
