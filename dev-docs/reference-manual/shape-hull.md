---
name: shape-hull
category: 2d-shapes
since: ""
status: stable
---

# shape-hull

## Signature

`(shape-hull a b)`
`(shape-hull a b c & more)`

## Description

Compute the 2D convex hull of N input shapes. The result is the true
convex hull of the union of all input points: only the points on the
convex boundary are kept, joined by straight edges. Useful for fairing
complex outlines from a few seed shapes, or for capsule and lozenge
profiles. Does not modify turtle state.

`shape-hull` is variadic and takes shapes as positional arguments only —
**there is no `:segments` option**. Output vertex count equals the number
of hull vertices.

## Parameters

- `a b c …` — two or more 2D shapes.

## Example

{{example: shape-hull-capsule}}

<!-- example-source: shape-hull-capsule -->
```clojure
(register pill (extrude (shape-hull (circle 10) (translate (circle 10) 30 0)) (f 5)))
```
<!-- /example-source -->

The hull of two offset circles is a capsule outline: round caps connected
by straight tangents.

## Variations

{{example: shape-hull-resampled}}

<!-- example-source: shape-hull-resampled -->
```clojure
(def s1 (translate (circle 50 128) 130 20))
(def s2 (circle 25 128))
(def s3 (translate (circle 16 128) 130 -20))
(def base (resample-shape (shape-hull s3 s1 s2) 256))
(register pistachio (loft base (f 50)))
```
<!-- /example-source -->

To control resolution, use dense input shapes (`(circle r 128)`) so the
hull preserves more boundary points along curved sections, then wrap the
result in `resample-shape` to land on an exact target vertex count for
loft compatibility.

## Notes

- Holes on input shapes are ignored. Only the outer contour participates
  in the hull.
- The returned shape is centered (`:centered? true`), regardless of where
  the inputs sit.
- The tangent straight segments between two distinct input shapes are
  always 2-vertex lines, regardless of input density. To smooth them out,
  wrap in `resample-shape` or `shape-offset`.
- For 3D mesh hulls, see the analogous mesh operation in Mesh Operations.

## See also

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** `shape-bridge`, `shape-union`, `resample-shape`,
  `shape-offset`
