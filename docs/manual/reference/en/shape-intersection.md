---
name: shape-intersection
category: 2d-shapes
since: ""
status: stable
---

# shape-intersection

## Signature

`(shape-intersection a b)`

## Description

Return the region where shapes `a` and `b` overlap. Implemented with
Clipper2; holes are preserved. Does not modify turtle state.

## Parameters

- `a`, `b` — 2D shapes.

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

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** `shape-union`, `shape-difference`, `shape-xor`,
  `shape-offset`
