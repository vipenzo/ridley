---
name: deepest-point
category: 2d-shapes
since: ""
status: stable
---

# deepest-point

## Signature

`(deepest-point shape)`

## Description

The interior point of a 2D shape farthest from its boundary — an
approximate pole of inaccessibility, found on a 48×48 grid over the
bounding box, holes respected. The place for one pin on a C or a U,
whose centroid falls outside the material. Returns `[x y]`.

## Example

```clojure
(deepest-point (rect 60 20))   ;; => [0 0]
```

## See also

- **Related:** `spread-points`, `shape-centroid`
