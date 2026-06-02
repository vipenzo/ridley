---
name: shape-difference
category: 2d-shapes
since: ""
status: stable
---

# shape-difference

## Signature

`(shape-difference a b)`

## Description

Subtract shape `b` from shape `a`: return the region of `a` that does not
overlap `b`. When `b` is entirely inside `a` the result is a shape with a
hole. Implemented with Clipper2; holes are preserved and propagated.
Does not modify turtle state.

## Parameters

- `a` — base shape.
- `b` — shape to subtract.

## Example

{{example: shape-difference-basic}}

<!-- example-source: shape-difference-basic -->
```clojure
(def washer (shape-difference (circle 20) (circle 14)))
(register tube (extrude washer (f 40)))
```
<!-- /example-source -->

Subtracting a smaller circle from a larger one yields a washer profile;
extruded, that becomes a hollow tube.

## Notes

- Holes are detected automatically from winding direction.
- All subsequent extrusion operations (`extrude`, `loft`, `revolve`)
  correctly handle the resulting holes.

## See also

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** `shape-union`, `shape-intersection`, `shape-xor`,
  `shape-offset`, `pattern-tile`
