---
name: shape-perimeter
category: path
since: ""
status: stable
---

# shape-perimeter

## Signature

`(shape-perimeter shape)`

## Description

Total length of a shape's **outer** closed contour: the sum of the
euclidean distances between consecutive contour points, including the
closing edge from the last point back to the first.

Holes are ignored. For a holed shape (a washer, a glyph with a counter)
or a shape with multiple contours, use `shape-perimeters` to get the
per-contour breakdown.

Returns `nil` when the input is not a shape.

## Parameters

- `shape` — a shape map (`{:type :shape …}`), e.g. from `circle`,
  `rect`, `star`, `poly`, `text-shape`, or `path-to-shape`.

## Example

{{example: shape-perimeter-circle}}

<!-- example-source: shape-perimeter-circle -->
```clojure
;; Size a profile to a target circumference (e.g. a hatter's sizing
;; band, or a profile to wrap around a cylinder).
(def band (circle 50))
(out (str "perimeter = " (shape-perimeter band)))
```
<!-- /example-source -->

## Variations

{{example: shape-perimeter-star}}

<!-- example-source: shape-perimeter-star -->
```clojure
(def s (star 5 12 24))
(out (str "star outline = " (shape-perimeter s)))
```
<!-- /example-source -->

## Notes

- **Sampling caveat.** The value is the length of the *sampled* polygon,
  not the ideal smooth contour. A circle of radius `r` drawn with `n`
  segments yields `2·n·r·sin(π/n)`, which is slightly **less** than the
  true `2πr` (an inscribed polygon). The error shrinks fast with
  resolution: ≈0.16% at 32 segments, ≈0.04% at 64. Increase the shape's
  segment count (e.g. `(circle r 128)` or a higher global `resolution`)
  when you need the circumference to a tight tolerance.
- The contour ring does **not** repeat its first point; the closing edge
  is added implicitly, so a square `(rect 10 10)` reports `40`.
- For an **open** path (a trajectory, not an enclosed area), use
  `path-length`.

## See also

- **Related:** `shape-perimeters`, `path-length`, `bounds-2d`, `area`
