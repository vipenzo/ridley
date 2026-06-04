---
name: weave-heightmap
category: generative-operations
since: ""
status: stable
---

# weave-heightmap

## Signature

`(weave-heightmap & {:keys [threads spacing radius lift resolution profile thickness]})`

## Description

Generate a weave-pattern heightmap analytically — no mesh required.
Returns a heightmap value (a map with `:type :heightmap`) ready for use
with the `heightmap` shape-fn, `sample-heightmap`, or
`heightmap-to-mesh`. Much faster than building tube meshes and
rasterising them. Does not modify turtle state.

The output represents a tile of an over/under woven fabric: warp threads
running along one axis, weft threads along the other, with smooth
sinusoidal undulation at crossings.

## Parameters

- `:threads` — threads per direction in one tile (default `4`; should be
  even for clean tiling).
- `:spacing` — centre-to-centre thread distance (default `5`).
- `:radius` — thread radius (default `2`).
- `:lift` — over/under amplitude. Defaults to `:radius`.
- `:resolution` — grid size (default `128`, square).
- `:profile` — thread cross-section: `:round` (default) or `:flat`.
- `:thickness` — ribbon thickness for `:flat` profile (default
  `radius * 0.5`).

## Example

{{example: weave-heightmap-basic}}

<!-- example-source: weave-heightmap-basic -->
```clojure
(def hm (weave-heightmap :threads 4 :spacing 5 :radius 2 :resolution 128))
(register weave-tube
  (loft (heightmap (circle 20 128) hm :amplitude 2 :tile-x 4 :tile-y 3)
        (f 60)))
```
<!-- /example-source -->

Build the heightmap once, then apply it as a displacement via the
`heightmap` shape-fn. `:tile-x` and `:tile-y` repeat the pattern around
the contour and along the path.

## Variations

{{example: weave-heightmap-flat-ribbon}}

<!-- example-source: weave-heightmap-flat-ribbon -->
```clojure
(def hm (weave-heightmap :threads 6 :profile :flat :thickness 1 :resolution 128))
(register flat-weave
  (loft (heightmap (circle 18 128) hm :amplitude 1.5 :tile-x 5 :tile-y 4)
        (f 50)))
```
<!-- /example-source -->

`:profile :flat` produces ribbon-like threads instead of round cross-
sections — useful for woven fabric or basket weaves.

## Notes

- Output is normalised to `[0, 1]` like all heightmaps.
- The output's `:bounds` is `[0 0 size size]` where
  `size = threads × spacing`.
- For arbitrary patterns from existing geometry, use `mesh-to-heightmap`
  on a hand-built mesh instead.

## See also

- **Related:** `heightmap`, `mesh-to-heightmap`, `sample-heightmap`,
  `heightmap-to-mesh`
