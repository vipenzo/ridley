---
name: text-heightmap
category: generative-operations
since: ""
status: stable
---

# text-heightmap

## Signature

`(text-heightmap text & {:keys [size resolution font depth supersample edge-softness curve-segments]})`

## Description

Generate a heightmap directly from a text string. Returns a heightmap
value (a map with `:type :heightmap`) — the same structure produced by
`weave-heightmap` and `mesh-to-heightmap` — ready for use with the
`heightmap` shape-fn, `sample-heightmap`, or `heightmap-to-mesh`. Does
not modify turtle state.

This packages the orient-bounds-rasterise dance you would otherwise do by
hand: extrude the glyphs, place them in the right plane so the letters
read once wrapped, frame the grid on the text's bounds, and rasterise.
The letters lie in the heightmap's XY in a **canonical orientation**:

- **width** = the text's horizontal development (total advance), and
- **height** = the glyph height,

both in real `:size` units. Background is flat `0`, letters raised. The
width spans the full text **advance**, so leading/trailing spaces become
real flat margin — `"Ridley "` is wider than `"Ridley"` (useful as a gap
when tiling around a profile).

Crucially, the returned heightmap **knows its physical size**: it carries
`:phys-width`/`:phys-height` (and `:source :text`). The `heightmap`
shape-fn reads those to place the text at its true size on a loft wall —
so `:size 5` text is ~5 units tall on the surface, not stretched to fill
it. How it lands (direction, coverage, tiling, scale) is decided entirely
on the `heightmap` side; this function only fixes the content and its size.

## Parameters

- `:size` — glyph size in world units (default `5`). This is the physical
  height of the relief band, preserved through to the loft surface.
- `:resolution` — grid size (default `256`, square). Glyphs need finer
  sampling than analytic patterns to stay legible.
- `:curve-segments` — Bezier flattening per glyph curve. Defaults to scale
  with `:resolution` (`max 16, resolution/8`); pin it to override. Affects
  the glyph *outline* fidelity, but is **not** what removes faceting on a
  loft — see `:edge-softness`.
- `:supersample` — anti-aliasing factor for the raster (default `3`). The
  relief is a binary mask (letter / background); without AA the hard `0/1`
  edge snaps to whole grid cells. Supersampling rasterises at this factor
  and averages, so the edge position becomes sub-cell accurate. Set `1` to
  disable.
- `:edge-softness` — edge-ramp width as a fraction of glyph height (default
  `0.02`). **This is what actually removes the comb/staircase on a loft.**
  The relief edge is otherwise ~one cell wide — too thin for a typical loft
  to resolve, so it facets. Softening widens the ramp to real-world scale
  (≈ the loft step) so it reads as a smooth bevel — *without* a denser loft,
  grid, or `:curve-segments`. Raise for softer letters; set `0` for crisp
  binary edges. (Implemented as a world-unit box blur via `mesh-to-heightmap`'s
  `:blur`, isotropic in real space.)
- `:font` — font id keyword (default `:roboto`). Any registered font id,
  e.g. `:roboto-mono`.
- `:depth` — extrusion depth (default `1`). Irrelevant to the output
  since heightmaps are normalised to `[0, 1]`; exposed only for symmetry.

> **Faceted letters?** It is almost never `:curve-segments`, `:resolution`,
> or the loft step count. The relief is a binary mask, and its sharp edges
> comb when the loft samples coarser than the grid. Raise `:edge-softness`
> (and keep `:supersample` ≥ 3) — that widens the edge ramp so even a modest
> loft renders a smooth bevel.

## Example

{{example: text-heightmap-basic}}

<!-- example-source: text-heightmap-basic -->
```clojure
(def hm (text-heightmap "Ridley" :size 5))
(register embossed-cylinder
  (loft (heightmap (circle 10 256) hm :amplitude 1.5 :center true)
        (f 60)))
```
<!-- /example-source -->

Build the heightmap once, then apply it as a displacement via the
`heightmap` shape-fn. Because the heightmap carries its real size, the
default `heightmap` placement (`:fit :auto` → physical, a single centred
copy) lands "Ridley" at ~5 units tall, reading straight around the
cylinder. `:center true` raises and lowers symmetrically so the relief
sits on the surface rather than bulging outward only.

## Variations

{{example: text-heightmap-around}}

<!-- example-source: text-heightmap-around -->
```clojure
;; Repeat the text seamlessly around the whole circumference,
;; and make it climb the wall instead of wrapping:
(def band (text-heightmap "Ridley " :size 4))
(register tube
  (loft (heightmap (circle 10 256) band :amplitude 1.2
                   :tile-x :fill)                 ; pack copies around
        (f 30)))
(register column
  (loft (heightmap (circle 8 256) band :amplitude 1.2
                   :direction :height)            ; text runs up the axis
        (f 40)))
```
<!-- /example-source -->

## Notes

- The grid data is normalised to `[0, 1]` like all heightmaps (raised
  pixels `1`, background `0`); the **physical** scale lives in
  `:phys-width`/`:phys-height`, not in the pixel values.
- Direction, coverage and tiling are `heightmap` shape-fn concerns, not
  `text-heightmap` ones — see `heightmap` for `:direction`, `:fit`,
  `:scale`, `:tile-x`/`:tile-y`.
- The same heightmap works for flat relief: pass it to `heightmap-to-mesh`
  instead of wrapping it on a loft.
- For text relief from a hand-built or transformed mesh, use
  `mesh-to-heightmap` on the mesh directly (that path stays normalised /
  aspect-agnostic — it has no intrinsic physical size).

## See also

- **Guide:** placeholder → cap. 13 (Testo)
- **Related:** `heightmap`, `weave-heightmap`, `mesh-to-heightmap`,
  `sample-heightmap`, `heightmap-to-mesh`, `extrude-text`
