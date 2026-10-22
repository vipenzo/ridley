---
name: shell-styles
category: generative-operations
since: ""
status: stable
---

# shell-styles

## Signature

`(shell shape-or-fn :style style & style-options)`

## Description

The wall patterns of `shell` and their options. Part of the
[shell](shell.md) documentation.

A style's options are written at the same level as `:style` and
`:thickness`, not in a separate map:
`(shell (circle 20) :thickness 2 :style :lattice :openings 10 :rows 14)`.
Options that belong to another style are ignored.

| Style | What it makes |
|---|---|
| `:solid` (default) | A full wall, no openings. No options. |
| `:voronoi` | Irregular cells, like a natural foam or a leaf skeleton. |
| `:lattice` | A grid of rounded openings, like bricks or a grille. |
| `:checkerboard` | Square openings alternating with solid squares. |
| `:pattern` | Any 2D shape of your choice, repeated over the wall. |

Every style works with `:invert? true`, which swaps wall and openings.

## Parameters

**`:voronoi`**

| Option | Default | Meaning |
|---|---|---|
| `:cells` | `6` | Cells around the circumference. |
| `:rows` | `6` | Cells along the path. |
| `:seed` | `42` | Change it for a different random arrangement. |
| `:wall-width` | `0.3` | Width of the strips between openings, as a fraction of a cell (not mm). |
| `:margin` | `0.05` | Fraction of the path kept solid at each end, so the tube ends in a clean ring instead of cut cells. |
| `:softness` | `0.6` | Edge smoothing — see below. |

**`:lattice`**

| Option | Default | Meaning |
|---|---|---|
| `:openings` | `8` | Openings around the circumference. |
| `:rows` | `12` | Rows along the path. |
| `:shift` | `0.5` | How much each row is offset from the previous one (`0` = aligned columns, `0.5` = brick pattern). |
| `:softness` | `0.6` | Edge smoothing — see below. Ignored with `:invert? true`. |

**`:checkerboard`**

| Option | Default | Meaning |
|---|---|---|
| `:cols` | `8` | Squares around the circumference. |
| `:rows` | `8` | Squares along the path. |

**`:pattern`**

| Option | Default | Meaning |
|---|---|---|
| `:pattern` | — | The motif: any 2D shape with at least 3 points. Required. |
| `:cells` | `8` | Motifs around the circumference. |
| `:rows` | `6` | Motifs along the path. |
| `:grid` | `:square` | `:hex` offsets every other row by half a motif. |
| `:inset` | `0` | Grow (> 0) or shrink (< 0) the motif, in fractions of a cell: thinner or thicker strips between motifs. |
| `:margin` | `0.05` | Fraction of the path kept solid at each end. |
| `:softness` | `0.6` | Edge smoothing — see below. |

**Edge smoothing (`:softness`).** On `:voronoi`, `:lattice` and `:pattern`
the edges of the openings are cut smoothly, with a slight taper of the
wall towards the hole. Values around `0.4`–`0.8` work well. With
`:softness 0` the openings are cut along the mesh grid instead, so their
edges come out stepped; follow with
`(mesh-smooth m :sharp-angle 90 :refine 2)` for crisp walls with rounded
corners.

## Example

{{example: shell-voronoi}}

<!-- example-source: shell-voronoi -->
```clojure
(register voro-tube
  (loft (shell (circle 20 64) :thickness 2 :style :voronoi :cells 8 :rows 6)
        (f 60)))
```
<!-- /example-source -->

Voronoi wall: 8 cells around, 6 along the path.

This version is a quick preview: 64 points around the profile and the
default 16 rings along the path are too few for clean openings. For an
acceptable result raise both — the points with the profile's resolution,
the rings with `loft-n`:

```clojure
(register voro-tube
  (loft-n 512 (shell (circle 20 512) :thickness 2 :style :voronoi :cells 8 :rows 6)
        (f 60)))
```

Expect it to be **much slower** to generate. Work at low resolution while
you tune the pattern, and raise it only for the final model.

## Variations

{{example: shell-voronoi-inverted}}

<!-- example-source: shell-voronoi-inverted -->
```clojure
(register cells
  (loft (shell (circle 20 64) :thickness 2 :style :voronoi :cells 8 :rows 6
               :wall-width 0.5 :invert? true)
        (f 60)))
```
<!-- /example-source -->

The same pattern inverted: the cells are solid and the strips between them
are open. A wider `:wall-width` makes the gaps easier to see.

{{example: shell-lattice}}

<!-- example-source: shell-lattice -->
```clojure
(register grill
  (loft (shell (circle 18 64) :thickness 1.5 :style :lattice :openings 10 :rows 14)
        (f 50)))
```
<!-- /example-source -->

Lattice: 10 openings around, 14 rows, each row offset by half an opening.

{{example: shell-checkerboard}}

<!-- example-source: shell-checkerboard -->
```clojure
(register checker
  (loft (shell (circle 20 64) :thickness 2 :style :checkerboard :cols 8 :rows 8)
        (f 40)))
```
<!-- /example-source -->

Checkerboard: square openings alternating with solid squares.

{{example: shell-pattern}}

<!-- example-source: shell-pattern -->
```clojure
(register motif-tube
  (loft (shell (circle 20 96) :thickness 3
               :style :pattern :pattern (circle 24) :cells 12 :rows 8)
        (f 60)))
```
<!-- /example-source -->

A round motif repeated as openings: 12 around, 8 along the path. The
motif's own point count sets how round the holes are, so use `(circle 24)`
rather than `(circle 6)`.

{{example: shell-pattern-hex}}

<!-- example-source: shell-pattern-hex -->
```clojure
(register stars
  (loft (shell (circle 20 96) :thickness 3
               :style :pattern :pattern (star 5 10 4) :cells 10 :rows 7 :grid :hex)
        (f 60)))
```
<!-- /example-source -->

Any shape works as a motif — here a five-pointed star, on a staggered
grid.

## Notes

- **Resolution decides the quality.** The openings are cut on the mesh:
  their edges can only be as fine as the profile's points (around) and
  the loft's rings (along). The examples on this page use low values so
  they run fast; for a real model raise both, e.g. `(circle 20 512)` and
  `loft-n 512`, at the cost of a much slower generation.
- **Around and along.** Counts "around the circumference" are spread over
  the whole perimeter, so any whole number closes seamlessly. Counts
  "along the path" are spread over the path's length.
- **`:voronoi` and `:pattern` measure in cells, not mm.** `:wall-width`
  and `:inset` are fractions of one cell; if you change `:cells` or `:rows`,
  the strips scale with them.
- **`:checkerboard` has hard edges** — `:softness` does not apply to it.
- **The ends of the tube.** `:voronoi` and `:pattern` keep a solid ring at
  each end (`:margin`), so the tube ends cleanly. With `:invert? true` those
  rings are inverted too and become open: the tube looks shorter by
  `:margin` at each end. `:lattice` has no `:margin` and ends on a row of
  openings, so it can also come out a few mm shorter than the path. To
  close an end for sure, add a cap ([shell-caps](shell-caps.md)).

## See also

- **Shell pages:** [shell](shell.md), [shell-caps](shell-caps.md), [shell-custom-fn](shell-custom-fn.md)
- **Related:** `woven-shell`, `voronoi-shell`, `mesh-smooth`
