---
name: morph-shape
category: 2d-shapes
since: ""
status: stable
---

# morph-shape

## Signature

`(morph-shape shape-a shape-b t)`

## Description

Linearly interpolate between two 2D shapes. With `t = 0` returns
`shape-a`, with `t = 1` returns `shape-b`, and values in between blend
the two pointwise. Holes are also interpolated when both shapes have a
matching number of holes (and each hole has the same point count). Does
not modify turtle state.

Both shapes must have the same number of points. When they don't, the
input is returned unchanged. Use `resample-shape` to match counts before
morphing.

## Parameters

- `shape-a` — start shape.
- `shape-b` — end shape (same point count as `shape-a`).
- `t` — interpolation factor; typically in `[0, 1]`, extrapolates outside.

## Example

{{example: morph-shape-basic}}

<!-- example-source: morph-shape-basic -->
```clojure
(def c (circle 15 32))
(def s (resample-shape (star 5 18 9) 32))
(register half-morph (extrude (morph-shape c s 0.5) (f 4)))
```
<!-- /example-source -->

Halfway between a circle and a 5-pointed star, with both resampled to 32
points so they're morph-compatible.

## Notes

- Returns `shape-a` unchanged when point counts differ — no exception is
  thrown. Resample beforehand to guarantee a blend.
- Holes interpolate only when both shapes have the same number of holes
  with matching point counts.
- For varying the profile along an extrusion path, see the `morphed`
  shape-fn.

## See also

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** `resample-shape`, `scale-shape`, `rotate-shape`
