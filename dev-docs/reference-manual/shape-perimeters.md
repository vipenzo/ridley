---
name: shape-perimeters
category: path
since: ""
status: stable
---

# shape-perimeters

## Signature

`(shape-perimeters shape)`

## Description

Per-contour lengths of a shape, returned as a vector
`[outer hole1 hole2 …]`:

- element `0` is the **outer** contour length (identical to
  `shape-perimeter`),
- the remaining elements are the **hole** contours in order.

For a shape with no holes the vector has a single element. Returns `nil`
when the input is not a shape.

Each entry is a closed-ring perimeter and carries the same sampling
caveat as `shape-perimeter` (low-resolution curves underestimate).

## Parameters

- `shape` — a shape map (`{:type :shape …}`). Holes come from
  `:holes`, e.g. a washer built with `shape-difference`, or a glyph
  counter from `text-shape`.

## Example

{{example: shape-perimeters-washer}}

<!-- example-source: shape-perimeters-washer -->
```clojure
;; A washer: outer disc minus an inner hole.
(def washer (shape-difference (circle 30) (circle 18)))
(let [[outer & holes] (shape-perimeters washer)]
  (out (str "outer = " outer))
  (out (str "holes = " (vec holes)))
  ;; total cut length, e.g. for a laser path:
  (out (str "total = " (reduce + (shape-perimeters washer)))))
```
<!-- /example-source -->

## Notes

- To get the **total** length of all contours (e.g. total cut path),
  sum the vector: `(reduce + (shape-perimeters shape))`.
- `text-shape` returns one shape **per glyph**; map over the glyphs to
  measure a whole word: `(map shape-perimeters (text-shape "Hi"))`.
- See `shape-perimeter` for the inscribed-polygon sampling caveat.

## See also

- **Related:** `shape-perimeter`, `path-length`, `bounds-2d`,
  `shape-difference`
