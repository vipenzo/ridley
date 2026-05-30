---
name: heightmap
category: generative-operations
since: ""
status: stable
---

# heightmap

## Signature

`(heightmap shape-or-fn hm & {:keys [amplitude center direction fit scale surface-width surface-height tile-x tile-y offset-x offset-y]})`

## Description

Shape-fn that displaces the profile radially using values sampled from a
2D heightmap. Surface axes: `u` runs around the cross-section (the
circumference) and `v` runs along the loft path (the height). The sampled
value is scaled by `:amplitude` and used as the radial offset. Used with
`loft` or `revolve`. Does not modify turtle state.

This function owns the **placement** decision — how a heightmap lands on
the walls — separately from whoever produced the heightmap. There are two
fit modes:

- **`:stretch`** — one (optionally tiled) copy fills the whole surface,
  regardless of any physical size. This is the classic behaviour and the
  right choice for seamless patterns (`weave-heightmap`, `mesh-to-heightmap`).
- **`:physical`** — the heightmap is placed at its **real-world size**. A
  heightmap that knows its footprint (e.g. from `text-heightmap`, which
  carries `:phys-width`/`:phys-height`) lands at that size: `:size 5` text
  is ~5 units tall on the wall, not stretched to fill it. The surface's
  circumference is taken from the base shape's perimeter and its height
  from the loft's own length, so coverage derives from the ratio of the
  two — no manual normalisation.

`:fit :auto` (the default) picks `:physical` when the heightmap knows its
size, else `:stretch`.

Heightmaps come from `text-heightmap` (physical), `weave-heightmap`
(analytical), `mesh-to-heightmap` (rasterise a mesh's Z), or any function
building the `{:type :heightmap …}` map by hand.

## Parameters

- `shape-or-fn` — a 2D shape, or another shape-fn (composes).
- `hm` — a heightmap value.
- `:amplitude` — radial displacement amplitude (default `1.0`).
- `:center` — when `true`, the sampled value is shifted to `[-0.5, 0.5]`
  before scaling (zero-average displacement); when `false`, the raw
  `[0, 1]` value is used (default `false`). In `:physical` mode the area
  outside the placed copies sits at this same background level.
- `:direction` — `:circumference` (default; the heightmap's width wraps
  around the tube — text reads around it) or `:height` (the width runs
  along the path — text climbs the wall).
- `:fit` — `:auto` (default), `:physical`, or `:stretch` (see above).
- `:scale` — multiply the heightmap's physical size (`:physical` mode
  only, default `1.0`). Use it to enlarge/shrink text without re-sizing
  the source.
- `:surface-width` — circumference override (`:physical`). Default: the
  perimeter of the base shape (`shape-perimeter`).
- `:surface-height` — path-length override (`:physical`). Default: the
  loft's own total length.
- `:tile-x` / `:tile-y` — copies across the reading / height direction.
  An integer places exactly that many; `:fill` packs as many whole copies
  as the surface holds, snapping the cell so it tiles seamlessly (in
  `:physical` mode). Default `1` — a single copy, flat elsewhere.
- `:offset-x` / `:offset-y` — shift the placement as a fraction of the
  surface. Default centres a single copy.

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

The weave heightmap has no intrinsic size, so `:fit :auto` falls back to
`:stretch`: `:tile-x 4 :tile-y 3` repeats the pattern four times around
the contour and three times along the path, filling the surface.

## Variations

{{example: heightmap-text-physical}}

<!-- example-source: heightmap-text-physical -->
```clojure
;; Physical text: ~5 units tall, reads straight around the cylinder.
(def hm (text-heightmap "Ridley" :size 5))
(register embossed
  (loft (heightmap (circle 10 256) hm :amplitude 1.5 :center true) (f 60)))

;; Same text climbing the wall instead, and tiled to wrap fully around:
(register climbing
  (loft (heightmap (circle 8 256) hm :amplitude 1.5 :direction :height) (f 40)))
(register banded
  (loft (heightmap (circle 10 256) (text-heightmap "Ridley " :size 4)
                   :amplitude 1.2 :tile-x :fill) (f 24)))
```
<!-- /example-source -->

In `:physical` mode (auto-selected for `text-heightmap`) the text keeps
its real size; `:direction` swaps wrap-around vs climb-the-axis, and
`:tile-x :fill` repeats it seamlessly around the whole circumference.

## Notes

- Sampling is bilinear and auto-tiling (values wrap modulo width/height).
- Mirroring: in `:physical` mode the reading axis is reflected so text
  reads correctly when viewed from **outside** the surface (not as a
  mirror image).
- A heightmap reads as "physical" when it carries `:phys-width` /
  `:phys-height`; only `text-heightmap` sets these today. Force the mode
  with `:fit` when you want to override the default.
- Compose with other shape-fns via `->` threading.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `text-heightmap`, `mesh-to-heightmap`, `weave-heightmap`,
  `sample-heightmap`, `heightmap-to-mesh`, `shape-perimeter`, `displaced`,
  `shape-fn`
