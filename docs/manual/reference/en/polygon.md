---
name: polygon
category: 2d-shapes
since: ""
status: stable
---

# polygon

## Signature

`(polygon n radius)`

## Description

Construct a regular n-sided polygon centered at the origin. The first
vertex sits at the top (12 o'clock), and vertices are distributed evenly
around a circle of the given radius. The shape is centered
(`:centered? true`). Does not modify turtle state.

## Parameters

- `n` — number of sides (`>= 3`). Throws on smaller values.
- `radius` — distance from center to each vertex (circumradius).

## Example

{{example: polygon-basic}}

<!-- example-source: polygon-basic -->
```clojure
(register hex (extrude (polygon 6 15) (f 10)))
```
<!-- /example-source -->

A hexagonal prism. The first vertex of `(polygon 6 15)` is at the top, so
the flat sides are on the left and right.

## Notes

- For an irregular polygon from explicit coordinates, use `poly`.
- For a circle that uses turtle resolution, use `circle`.

## See also

- **Related:** `poly`, `star`, `circle`, `rect`
