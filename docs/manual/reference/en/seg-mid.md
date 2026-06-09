---
name: seg-mid
category: faces
since: ""
status: stable
---

# seg-mid

## Signature

`(seg-mid path i)`

## Description

Return the midpoint of segment `i` (0-based) of `path` — the i-th edge,
between waypoints `i` and `i+1`. Returns a `[x y 0]` point. This is the
explicit spelling of the `(mid path i)` form.

Useful with `ruler`/`distance` to measure to where a control-polygon
(midpoint) spline actually passes: the `bezier-as :control` curve goes
through each segment's midpoint.

## Parameters

- `path` — a path.
- `i` — 0-based segment index (an edge between two consecutive waypoints).

## Example

<!-- example-source: seg-mid-basic -->
```clojure
(def poly (path (f 30) (th 45) (f 25) (th 45) (f 30)))
(distance [0 45] (seg-mid poly 1))   ; distance to the 45° segment's midpoint
```
<!-- /example-source -->

## Notes

- Planar (XY) — reads the path's 2D waypoints.
- `(mid path i)` is a shorthand for the same thing.

## See also

- **Related:** `mid`, `ruler`, `distance`, `bezier-as`
