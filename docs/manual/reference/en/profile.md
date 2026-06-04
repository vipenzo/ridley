---
name: profile
category: generative-operations
since: ""
status: stable
---

# profile

## Signature

`(profile shape-or-fn path)`

## Description

Shape-fn that scales the cross-section to match a path silhouette. The
path's X coordinates represent the radius at each point along the
extrusion; at each loft step the profile is uniformly scaled to that
radius. Used with `loft`. Does not modify turtle state.

The path should trace the silhouette starting at the base radius and
moving along the axis. Uses cumulative arc length for smooth
parameterisation. Works best with bezier-smoothed paths
(`(bezier-as …)`) for smooth, organic shapes.

## Parameters

- `shape-or-fn` — a 2D shape (or shape-fn) used as the base cross-section.
- `path` — a 2D path whose X coordinates encode the radius at each point.

## Example

{{example: profile-vase}}

<!-- example-source: profile-vase -->
```clojure
(def vase-sil (path (f 5) (th 80) (f 15) (arc-h 5 -160) (f 15)))
(register vase (loft (-> (circle 20 64) (profile vase-sil)) (f 40)))
```
<!-- /example-source -->

A vase: the circle is scaled at each ring to match the radius traced by
the silhouette path. The result has the cross-section of a circle and
the silhouette of `vase-sil`.

## Notes

- The initial horizontal segment of the path (origin → base radius) is
  detected and skipped so the silhouette starts at the first vertical
  movement. After `bezier-as` this segment may have many intermediate
  points; the heuristic finds the first Y change.
- If the path is too short to sample (fewer than 2 effective points),
  `profile` passes the shape through unchanged.
- Compose with other shape-fns via `->` threading
  (e.g. `(-> (circle r) (fluted …) (profile silhouette))`).

## See also

- **Related:** `tapered`, `loft`, `shape-fn`, `bezier-as`
