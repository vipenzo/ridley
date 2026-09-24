---
name: shape-area
category: 2d-shapes
since: ""
status: stable
---

# shape-area

## Signature

`(shape-area shape)`

## Description

Area of a 2D shape: its outer contour minus its holes. Always ≥ 0.
(`area` is the different, mesh-face measure.)

## Example

```clojure
(shape-area (rect 60 20))   ;; => 1200
```

## See also

- **Related:** `shape-perimeter`, `shape-centroid`, `area`
