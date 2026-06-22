---
name: shell
category: generative-operations
since: ""
status: stable
---

# shell

## Signature

`(shell shape-or-fn & {:keys [thickness style threshold cap-top cap-bottom fn invert?] :as style-opts})`

## Description

Shape-fn that produces a hollow extrusion with variable-thickness walls
and optional openings. At each ring, the profile is annotated with a
per-vertex thickness value (`0` = no wall, `1` = full thickness); the
loft uses these to build an inner and outer wall around each point. The
wall pattern is chosen via `:style` (one of `:solid`, `:lattice`,
`:checkerboard`, `:weave`, `:voronoi`) or supplied as a custom function
via `:fn`. Used with `loft` or `revolve`. Does not modify turtle
state.

`:pattern` tiles an arbitrary 2D motif shape around the wall (the
shell analogue of `embroid`'s `:pattern`), instead of a procedural
texture — see the parameter table and the `shell-pattern` example.

Caps (top and/or bottom) close the ends of the shell. They can be solid
(a single thickness number) or patterned (a map with their own `:style`).
By convention **top** is the extrusion's **arrival** end (where the path
finishes) and **bottom** is its **start** (the turtle's pose when the
loft begins): `:cap-top` closes the far end, `:cap-bottom` the near one.

## Parameters

| Parameter | Default | Description |
|---|---|---|
| `shape-or-fn` | — | Base profile (shape or shape-fn). |
| `:thickness` | `2` | Wall thickness. Outer ring offset outward by `thickness/2`, inner ring inward by the same amount. |
| `:style` | `:solid` | Wall pattern: `:solid`, `:lattice`, `:checkerboard`, `:weave`, `:voronoi`, `:pattern`. Ignored when `:fn` is supplied. |
| `:fn` | — | Custom thickness function `(fn [a t] -> 0..1)` overriding `:style`. `a` = angular position (radians), `t` = path progress. |
| `:threshold` | `0.05` | Values below this snap to 0 (no wall). |
| `:invert?` | `false` | Swap solid/empty (`v → 1 - v`): e.g. turn `:lattice` bricks into brick-shaped openings, or a `:voronoi` wireframe into solid cells. Works with every style and with custom `:fn`. |
| `:cap-top` | — | Close the **arrival** end (where the path finishes). Number (solid cap) or map `{:thickness :style …}` (patterned cap). |
| `:cap-bottom` | — | Same as `:cap-top`, for the **start** end (the turtle's pose when the loft begins). |

**Style-specific options** (passed at the same level as `:thickness`):

| Style | Options |
|---|---|
| `:lattice` | `:openings` (8), `:rows` (12), `:shift` (0.5), `:softness` (0.6) |
| `:checkerboard` | `:cols` (8), `:rows` (8) |
| `:weave` | `:strands` (6), `:frequency` (8), `:width` (0.3) |
| `:voronoi` | `:cells` (6), `:rows` (6), `:seed` (42), `:wall-width` (0.3), `:margin` (0.05), `:softness` (0.6) |
| `:pattern` | `:pattern` (motif shape, ≥3 pts), `:cells` (8), `:rows` (6), `:grid` (`:square` / `:hex`), `:inset` (0), `:margin` (0.05), `:softness` (0.6) |

`:pattern` (the shell counterpart of `embroid`'s `:pattern`) tiles an
arbitrary 2D motif **by arc-length around the perimeter**, in *cell units*:
`:cells` motifs span the circumference and `:rows` span the sweep, so any
integer `:cells` wraps seamlessly at the seam. The motif is the **opening**
by default; `:invert?` makes it the solid. `:grid :hex` offsets alternate
rows by half a cell; `:inset` grows (>0) / shrinks (<0) the motif in cell
units to thin/fatten the struts.

`:softness` (on `:voronoi` and `:lattice`, **default `0.6`**) cuts opening
edges with a smooth **isocontour**: the wall→opening boundary is sliced at
sub-grid positions, so openings read smooth (with a graceful tapered lip)
rather than staircasing along the ring/segment grid. `~0.4–0.8` works well.
Pass `:softness 0` for the original hard binary cut (whole grid triangles
kept/dropped). Exception: `:lattice` with `:invert?` always uses the hard cut
(its band-boundary plateau doesn't close manifold under the isocontour build
when inverted; `:voronoi` is fine inverted). `:margin` (`:voronoi` only) forces
the wall solid over this fraction of `t` at each end, closing the tube cleanly
instead of cutting jagged cells at the rims.

**Cap styles** (for `:cap-top` / `:cap-bottom` maps):

| Cap style | Options |
|---|---|
| `:voronoi` | `:cells` (20), `:wall` (1.5), `:seed` (0), `:relax` (2), `:resolution` (16) |
| `:grid` | `:spacing` `[sx sy]` (`[5 5]`), `:hole` (1.5), `:hole-segments` (16), `:inset` (0) |
| `:solid` | (none; equivalent to passing a number) |

## Example

{{example: shell-solid}}

<!-- example-source: shell-solid -->
```clojure
(register tube (loft (shell (circle 20 64) :thickness 2 :style :solid) (f 50)))
```
<!-- /example-source -->

A plain hollow cylinder with 2-unit wall — the simplest shell.

## Variations

{{example: shell-voronoi}}

<!-- example-source: shell-voronoi -->
```clojure
(register voro-tube
  (loft (shell (circle 20 64) :thickness 2 :style :voronoi :cells 8 :rows 6)
        (f 60)))
```
<!-- /example-source -->

Voronoi-patterned wall: 8 cells circumferentially, 6 longitudinally. By
default (`:softness 0.6`) the opening edges are a smooth isocontour cut with
a tapered lip. Pass `:softness 0` for the original hard binary cut (and
optionally `(mesh-smooth m :sharp-angle 90 :refine 2)` for crisp walls with
rounded corners).

{{example: shell-lattice}}

<!-- example-source: shell-lattice -->
```clojure
(register grill
  (loft (shell (circle 18 64) :thickness 1.5 :style :lattice :openings 10 :rows 14)
        (f 50)))
```
<!-- /example-source -->

Lattice / grid openings. `:openings` columns, `:rows` rows; `:shift`
offsets odd rows.

{{example: shell-weave}}

<!-- example-source: shell-weave -->
```clojure
(register weave-tube
  (loft (shell (circle 20 64) :thickness 2 :style :weave :strands 6 :frequency 8)
        (f 50)))
```
<!-- /example-source -->

Woven-style pattern: interlocking strands modulate the wall thickness.

{{example: shell-checkerboard}}

<!-- example-source: shell-checkerboard -->
```clojure
(register checker
  (loft (shell (circle 20 64) :thickness 2 :style :checkerboard :cols 8 :rows 8)
        (f 40)))
```
<!-- /example-source -->

Checkerboard pattern, useful as a base for filtered or screened surfaces.

{{example: shell-pattern}}

<!-- example-source: shell-pattern -->
```clojure
(register motif-tube
  (loft (shell (circle 20 96) :thickness 3
                :style :pattern :pattern (circle 24) :cells 12 :rows 8)
        (f 60)))
```
<!-- /example-source -->

Tiles a circular motif as openings: 12 around the perimeter, 8 along the
sweep. Use a higher-resolution motif (e.g. `(circle 24)`, not `(circle 6)`)
for smooth holes — the motif's own point count sets how round the openings
read. `:invert? true` swaps it to solid discs on an open wall; `:grid :hex`
staggers the rows.

{{example: shell-custom-fn}}

<!-- example-source: shell-custom-fn -->
```clojure
(register custom
  (loft (shell (circle 20 64) :thickness 3
                :fn (fn [a t] (max 0 (sin (+ (* a 8) (* t PI 6))))))
        (f 50)))
```
<!-- /example-source -->

A custom thickness function: full control over the wall pattern.
Return values clamp to `[0, 1]`; sub-threshold values snap to 0.

{{example: shell-cap-patterned}}

<!-- example-source: shell-cap-patterned -->
```clojure
(register capped-tube
  (loft (shell (circle 20 64) :thickness 2 :style :voronoi :cells 8 :rows 6
                :cap-top {:thickness 3 :style :voronoi :cells 25 :wall 1.5}
                :cap-bottom 2)
        (f 50)))
```
<!-- /example-source -->

A patterned top cap (own voronoi style) and a solid bottom cap. Cap
styles automatically match the shape at the cap position and expand to
the outer wall radius.

## Notes

- The wall is symmetric: outer ring displaced outward by `thickness/2`,
  inner ring inward by the same amount. Where the thickness function
  returns 0, both rings coincide (opening).
- Shape-fns compose:
  `(-> (circle 20 64) (shell :thickness 2 :style :voronoi …) (tapered :to 0.5))`.
- The current `joint-mode` does not affect shell walls (they follow the
  per-ring thickness function rather than corner geometry).
- **Curved rails:** patterned shells are intended for straight or smoothly
  curved sweeps. An `arc-h`/`arc-v` rail is recorded as a chain of hard
  corners, so the loft splits it into many segments and the openings
  staircase/facet along the curve (the mesh stays watertight). For a clean
  curved patterned wall, sweep along a **bezier rail** (`bezier-to`, which
  is smooth) or raise the motif/`loft` resolution.

## See also

- **Related:** `woven-shell`, `voronoi-shell`, `loft`, `capped`,
  `shape-fn`
