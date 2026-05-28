---
name: heightmap
category: generative-operations
since: ""
status: stable
---

# heightmap

## Signature

`(heightmap shape-or-fn hm & {:keys [amplitude tile-x tile-y offset-x offset-y center]})`

## Description

Shape-fn that displaces the profile radially using values sampled from a
2D heightmap. The vertex's angular position maps to `u`, the loft
fraction `t` maps to `v`, both into the heightmap's parameter space.
The sampled value is scaled by `:amplitude` and used as the radial
offset. Tiling and centring let the same heightmap repeat or be biased
around zero. Used with `loft` or `revolve`. Does not modify turtle
state.

Heightmaps come from `mesh-to-heightmap` (rasterise a mesh's Z),
`weave-heightmap` (analytical generator), or any function building the
`{:type :heightmap …}` map by hand.

## Parameters

- `shape-or-fn` — a 2D shape, or another shape-fn (composes).
- `hm` — a heightmap value (map with `:data`, `:width`, `:height`,
  `:bounds`, `:z-min`, `:z-max`).
- `:amplitude` — radial displacement amplitude (default `1.0`).
- `:tile-x` — number of times the heightmap tiles around the contour
  (default `1`).
- `:tile-y` — number of times it tiles along the path (default `1`).
- `:offset-x`, `:offset-y` — phase offsets into the sampling window
  (default `0`).
- `:center` — when `true`, the sampled value is shifted to `[-0.5, 0.5]`
  before being scaled (so the average displacement is zero); when
  `false`, the raw `[0, 1]` value is used (default `false`).

## Example

{{example: heightmap-basic}}

<!-- example-source: heightmap-basic -->
```clojure
(def weave-hm (weave-heightmap :threads 4 :spacing 5 :radius 2 :resolution 128))
(register woven-tube
  (loft (heightmap (circle 20 128) weave-hm :amplitude 2 :tile-x 4 :tile-y 3)
        (f 60)))
```
<!-- /example-source -->

Use the analytical weave heightmap as a displacement source. With
`:tile-x 4 :tile-y 3` the pattern repeats around the contour four times
and along the path three times.

## Variations

{{example: heightmap-from-mesh}}

<!-- example-source: heightmap-from-mesh -->
```clojure
(def bump-mesh (heightmap-to-mesh (weave-heightmap :threads 6 :resolution 64) :z-scale 0.5))
(def hm       (mesh-to-heightmap bump-mesh :resolution 128))
(register textured (loft (heightmap (circle 15 96) hm :amplitude 1.5 :center true)
                         (f 40)))
```
<!-- /example-source -->

Build a heightmap by rasterising a mesh — useful when the source pattern
already exists as geometry. `:center true` averages out the displacement.

## Notes

- Sampling is bilinear and auto-tiling (values wrap modulo width/height);
  `:tile-x`/`:tile-y` multiply the input coordinates before sampling.
- Compose with other shape-fns via `->` threading.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `mesh-to-heightmap`, `weave-heightmap`, `sample-heightmap`,
  `heightmap-to-mesh`, `displaced`, `shape-fn`
