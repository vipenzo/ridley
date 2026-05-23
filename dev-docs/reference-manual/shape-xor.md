---
name: shape-xor
category: 2d-shapes
since: ""
status: stable
---

# shape-xor

## Signature

`(shape-xor a b)`

## Description

Symmetric difference of two 2D shapes: return the regions that belong to
exactly one of `a` or `b`. Because XOR can produce disconnected regions,
the result is a **vector of shapes**, not a single shape. Implemented with
Clipper2. Does not modify turtle state.

## Parameters

- `a`, `b` — 2D shapes.

## Example

{{example: shape-xor-basic}}

<!-- example-source: shape-xor-basic -->
```clojure
(def crescents (shape-xor (translate (circle 15) -5 0)
                          (translate (circle 15) 5 0)))
(register two-crescents (extrude crescents (f 4)))
```
<!-- /example-source -->

Two overlapping circles XOR'd produce two crescents — a vector of two
shapes. `extrude` accepts the vector directly and merges the resulting
meshes.

## Notes

- The return type is **vector of shapes**, even when only one region
  results. Downstream operations accept vectors of shapes natively:
  `extrude`, `loft`, `bloft`, `revolve`, `stamp`, `shape-offset`, and the
  shape transformations propagate over each element.
- For the single-shape booleans, see `shape-union`, `shape-difference`,
  `shape-intersection`.

## See also

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** `shape-union`, `shape-difference`, `shape-intersection`
