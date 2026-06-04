---
name: resample-shape
category: 2d-shapes
since: ""
status: stable
---

# resample-shape

## Signature

`(resample-shape shape n)`

## Description

Resample a 2D shape so it has exactly `n` points distributed evenly along
its perimeter. Useful for matching point counts before morphing, or for
giving downstream `loft` / `revolve` operations a predictable circumferential
density. Does not modify turtle state.

## Parameters

- `shape` — a 2D shape.
- `n` — desired number of points along the outer contour.

## Example

{{example: resample-shape-basic}}

<!-- example-source: resample-shape-basic -->
```clojure
(def s (resample-shape (star 5 20 8) 64))
(register smoothed-star (loft (tapered s :to 0.3) (f 30)))
```
<!-- /example-source -->

Resample a 10-vertex star to 64 evenly-spaced points before lofting, so
the tapered cone has uniform circumferential density.

## Variations

{{example: resample-shape-morph-compat}}

<!-- example-source: resample-shape-morph-compat -->
```clojure
(def a (resample-shape (circle 15) 48))
(def b (resample-shape (polygon 5 15) 48))
(register half-blend (extrude (morph-shape a b 0.5) (f 4)))
```
<!-- /example-source -->

Resample two shapes to the same point count so `morph-shape` can blend
between them.

## Notes

- Holes are not resampled — only the outer contour. Build morph-compatible
  holes separately if needed.
- Resampling smooths discrete sharp corners: a polygon resampled to many
  points still has straight edges, but the corner vertices may shift
  slightly along their adjacent edges.

## See also

- **Related:** `morph-shape`, `shape-hull`, `fit`
