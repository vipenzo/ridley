---
name: fillet-shape
category: 2d-shapes
since: ""
status: stable
---

# fillet-shape

## Signature

`(fillet-shape shape radius)`
`(fillet-shape shape radius & {:keys [segments indices]})`

## Description

Round the corners of a 2D shape with circular arcs. Each selected vertex
is replaced by a discretised arc of the given radius, tangent to the two
adjacent edges. Holes are filleted with the same parameters. Does not
modify turtle state.

Operates on 2D vertices in the cross-section plane — these are the edges
that run **along** the extrusion direction. For rounding the 3D cap edges
(where the profile meets the top/bottom face), use the `capped` shape-fn.

## Parameters

- `shape` — a 2D shape.
- `radius` — corner radius.
- `:segments` — number of arc segments per corner (default `8`).
- `:indices` — vector of vertex indices to fillet. Default: all vertices.

## Example

{{example: fillet-shape-basic}}

<!-- example-source: fillet-shape-basic -->
```clojure
(register pill (extrude (fillet-shape (rect 40 20) 5) (f 10)))
```
<!-- /example-source -->

Rounding all four corners of a rectangle yields a pill-shaped profile.

## Variations

{{example: fillet-shape-selective}}

<!-- example-source: fillet-shape-selective -->
```clojure
(register tab (extrude (fillet-shape (rect 30 15) 4 :indices [2 3]) (f 8)))
```
<!-- /example-source -->

Round only two of the four corners of a rectangle — useful for tabs and
asymmetric outlines.

## Notes

- Higher `:segments` produces smoother arcs but more triangles downstream;
  the default `8` is a good balance for most shapes.
- Returns the shape unchanged when `radius` is `0` or non-positive.
- For chamfered (flat-cut) corners instead of rounded, use `chamfer-shape`.

## See also

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** `chamfer-shape`, `shape-offset`, `capped` (cap fillet)
