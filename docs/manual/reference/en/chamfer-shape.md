---
name: chamfer-shape
category: 2d-shapes
since: ""
status: stable
---

# chamfer-shape

## Signature

`(chamfer-shape shape distance)`
`(chamfer-shape shape distance & {:keys [indices]})`

## Description

Cut the corners of a 2D shape flat. Each selected vertex is replaced by a
straight segment at the given distance along each adjacent edge. Holes are
chamfered with the same parameters. Does not modify turtle state.

Operates on 2D vertices in the cross-section plane.

## Parameters

- `shape` — a 2D shape.
- `distance` — distance along each adjacent edge from the corner to the
  start of the chamfer.
- `:indices` — vector of vertex indices to chamfer. Default: all vertices.

## Example

{{example: chamfer-shape-basic}}

<!-- example-source: chamfer-shape-basic -->
```clojure
(register hex-bevel (extrude (chamfer-shape (polygon 6 20) 3) (f 15)))
```
<!-- /example-source -->

Cutting 3-unit chamfers on every corner of a hexagon yields a 12-sided
outline.

## Variations

{{example: chamfer-shape-selective}}

<!-- example-source: chamfer-shape-selective -->
```clojure
(register notched (extrude (chamfer-shape (rect 30 20) 5 :indices [0 1]) (f 6)))
```
<!-- /example-source -->

Chamfer only specific vertices for asymmetric profiles.

## Notes

- Returns the shape unchanged when `distance` is `0` or non-positive.
- For rounded corners instead of flat cuts, use `fillet-shape`.

## See also

- **Related:** `fillet-shape`, `shape-offset`
