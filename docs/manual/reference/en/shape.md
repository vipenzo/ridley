---
name: shape
category: 2d-shapes
since: ""
status: stable
---

# shape

## Signature

`(shape & body)`

## Description

Macro. Build a 2D shape from turtle-style movements. Inside the body a
local 2D turtle starts at the origin facing `+X`; only `f` (forward) and
`th` (turn in plane) are available. The contour is automatically closed
back to the starting point.

Returns a shape suitable for `extrude`, `loft`, `revolve`, and the shape
booleans. Does not modify the outer (3D) turtle state.

The default anchoring is "first point at turtle position" (neither
`:centered?` nor `:preserve-position?`), which makes the starting vertex
the natural anchor.

## Parameters

- `body` — a sequence of `(f d)` and `(th a)` forms describing the
  contour. `tv` and `tr` are explicitly disallowed (the macro is 2D).

## Example

{{example: shape-triangle}}

<!-- example-source: shape-triangle -->
```clojure
(register bar
  (extrude
    (shape (th 120) (f 15) (th 150) (f 8) (th -90) (f 20) (th -90) (f 8) (th 150) (f 15))
    (f 40)))
```
<!-- /example-source -->

A custom prism profile drawn by an imaginary 2D turtle. The contour
auto-closes between the last and first vertex.

## Variations

{{example: shape-right-triangle}}

<!-- example-source: shape-right-triangle -->
```clojure
(def right-tri (shape (f 4) (th 90) (f 3)))
(register tri-prism (extrude right-tri (f 20)))
```
<!-- /example-source -->

A right triangle defined by two edges. The third side is implied by the
auto-close.

## Notes

- Inside the body, the symbols `f` and `th` are shadowed by the macro: they
  drive the 2D recording turtle, not the outer 3D turtle.
- For coordinate-based input, use `poly` instead.
- For converting an existing 3D path to a shape, use `path-to-shape`.

## See also

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** `poly`, `path-to-shape`, `stroke-shape`, `make-shape`
