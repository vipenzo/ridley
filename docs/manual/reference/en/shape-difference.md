---
name: shape-difference
category: 2d-shapes
since: ""
status: stable
---

# shape-difference

## Signature

`(shape-difference a b)`
`(shape-difference a b c …)`
`(shape-difference [a b c …])`

## Description

Subtract shape `b` (and any further shapes `c`, `d`, …) from shape `a`:
return the region of `a` that does not overlap the rest. When a subtracted
shape is entirely inside `a` the result is a shape with a hole. Implemented
with Clipper2; holes are preserved and propagated. Does not modify turtle
state.

## Parameters

- `a` — base shape.
- `b`, … — shapes to subtract. May also be passed as a single vector
  `[a b …]` with the first element as the base, matching the mesh
  `difference` form.

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

- A reference image (`set-image`) on the base shape (the first operand)
  carries over to the result and stays clipped to the outline when stamped.

## See also

- **Related:** `shape-union`, `shape-intersection`, `shape-xor`,
  `shape-offset`, `pattern-tile`, `set-image`
