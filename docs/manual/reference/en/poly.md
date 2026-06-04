---
name: poly
category: 2d-shapes
since: ""
status: stable
---

# poly

## Signature

`(poly x1 y1 x2 y2 ...)`
`(poly [x1 y1 x2 y2 ...])`
`(poly coords)`

## Description

Construct an arbitrary 2D shape from flat coordinate pairs. Coordinates may
be passed as positional arguments, a vector, or a bound variable. The shape
is centered (`:centered? true`). Does not modify turtle state.

At least three points (six coordinates) are required; an odd coordinate
count throws. Winding is taken as given — use `reverse-shape` to flip it.

## Parameters

- `x1 y1 x2 y2 …` — alternating x/y values, or a single vector/sequence of
  the same. Pairs become the polygon vertices in order.

## Example

{{example: poly-basic}}

<!-- example-source: poly-basic -->
```clojure
(register arrow (extrude (poly -3 -2  5 0  -3 2) (f 8)))
```
<!-- /example-source -->

A triangular arrow defined by three explicit vertices. The polygon closes
back to the first point automatically.

## Variations

{{example: poly-from-vector}}

<!-- example-source: poly-from-vector -->
```clojure
(def diamond-pts [0 5  5 0  0 -5  -5 0])
(register gem (extrude (poly diamond-pts) (f 10)))
```
<!-- /example-source -->

Coordinates collected in a `def` and passed as a single vector. Useful when
the same set of points is reused across multiple shapes.

## Notes

- Throws if the coordinate count is odd or if fewer than 3 points are given.
- For a regular n-sided polygon, prefer `polygon`.
- To build a contour from turtle-style movements rather than coordinates,
  use the `shape` macro.

## See also

- **Related:** `polygon`, `shape`, `make-shape`, `reverse-shape`
