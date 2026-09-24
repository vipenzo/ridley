---
name: spread-points
category: 2d-shapes
since: ""
status: stable
---

# spread-points

## Signature

`(spread-points shape n)`
`(spread-points shape n & {:keys [seed iterations]})`

## Description

`n` points spread evenly inside a 2D shape, holes respected: deterministic
seeds relaxed by Lloyd's algorithm into a centroidal Voronoi layout, so
every point is as far as it can be from its neighbours and from the
boundary. On an L the points migrate into both arms, on a ring they
spread along it, on a long strip they line up. A point that still ends
up outside the material (one point on a C: the centroid is in the void)
is replaced by the shape's `deepest-point`. Returns a vector of `[x y]`.

Same seed, same points. This is the engine under `layout-anchors` and
`voronoi-shell`.

## Parameters

- `shape` — a 2D shape, with or without holes.
- `n` — how many points.
- `:seed` — generator seed (default `0`).
- `:iterations` — Lloyd iterations (default `10`).

## Example

```clojure
(spread-points (rect 60 20) 3)
;; => [[-20.0 0.0] [0.0 0.0] [20.0 0.0]] (approximately)
```

## See also

- **Related:** `deepest-point`, `voronoi-shell`, `layout-anchors`,
  `shape-area`
