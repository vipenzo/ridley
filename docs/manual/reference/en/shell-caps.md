---
name: shell-caps
category: generative-operations
since: ""
status: stable
---

# shell-caps

## Signature

`(shell shape-or-fn … :cap-top cap :cap-bottom cap)` — `cap` is a number or a map

## Description

Closing the ends of a `shell` tube. Part of the [shell](shell.md)
documentation. Caps work with `loft`.

- `:cap-bottom` closes the **start** of the tube — where the turtle stands
  when the loft begins.
- `:cap-top` closes the **arrival** end — where the path finishes.

A cap is written in one of two ways:

- **a number** — a solid cap of that thickness in mm: `:cap-bottom 3`.
- **a map** — a cap with its own pattern of holes, for a grille or a
  strainer. The whole map, with every key it accepts:

```clojure
:cap-top {:thickness 3      ; required — cap thickness in mm
          :style :voronoi   ; required — :voronoi, :grid or :solid

          ;; only with :style :voronoi
          :cells 20         ; number of cells
          :wall 1.5         ; width of the strips between holes, in mm
          :seed 0           ; change it for a different arrangement
          :relax 2          ; how evenly the cells are spread (more = more regular)
          :resolution 16    ; detail of the cell outlines

          ;; only with :style :grid
          :spacing [5 5]    ; distance between hole centres in mm, [x y]
          :hole 1.5         ; hole radius in mm
          :hole-segments 16 ; points per hole (more = rounder)
          :inset 0          ; shrink every hole by this many mm
          }
```

`:style :solid` in a map is the same as giving just the number.

The cap does **not** lengthen the tube: it fills the last (or first) few
mm of it. A 50 mm loft with `:cap-top 3` is still 50 mm long, with the
last 3 mm solid.

## Parameters

| Key | Default | Meaning |
|---|---|---|
| `:thickness` | — | Cap thickness in mm. Required. |
| `:style` | — | `:voronoi`, `:grid` or `:solid`. Required. |
| `:cells` | `20` | `:voronoi` — number of cells. |
| `:wall` | `1.5` | `:voronoi` — strip width between holes, **in mm**. |
| `:seed` | `0` | `:voronoi` — random arrangement. |
| `:relax` | `2` | `:voronoi` — evenness of the cells. |
| `:resolution` | half the curve resolution | `:voronoi` — detail of the cell outlines. |
| `:spacing` | `[5 5]` | `:grid` — distance between hole centres, mm. |
| `:hole` | `1.5` | `:grid` — hole radius, mm. |
| `:hole-segments` | `16` | `:grid` — points per hole. |
| `:inset` | `0` | `:grid` — shrink every hole by this many mm. |

## Example

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

A voronoi top cap and a solid 2 mm bottom. The cap's pattern follows the
profile at its end and reaches the outer surface of the wall.

## Variations

{{example: shell-cap-grid}}

<!-- example-source: shell-cap-grid -->
```clojure
(register strainer
  (loft (shell (circle 20 64) :thickness 2
               :cap-bottom {:thickness 2 :style :grid :spacing [4 4] :hole 1.2})
        (f 30)))
```
<!-- /example-source -->

A strainer: a solid tube with a bottom drilled by a regular grid of round
holes.

## Notes

- **The cap and the wall use different names.** In a `:voronoi` *wall*
  the strip width is `:wall-width`, a fraction of a cell; in a `:voronoi`
  *cap* it is `:wall`, in mm. Writing `:wall-width` in a cap map does
  nothing and gives no error. Likewise `:seed` defaults to `0` in a cap
  and `42` in a wall.
- **Caps have fewer styles than walls:** only `:voronoi`, `:grid` and
  `:solid`. `:lattice`, `:checkerboard` and `:pattern` are wall-only.
- **`:thickness` and `:style` are both required in a map.** Without
  `:style` you get the error *Unknown cap :style*; without `:thickness`
  there is no error, but the cap comes out with no thickness at all. For a
  plain solid cap, give the number instead: `:cap-top 3`, not
  `:cap-top {:thickness 3}`.
- Caps are built by `loft`. With `revolve` they are ignored.

## See also

- **Shell pages:** [shell](shell.md), [shell-styles](shell-styles.md), [shell-custom-fn](shell-custom-fn.md)
- **Related:** `voronoi-shell` (the same voronoi pattern on a flat 2D shape), `capped`
