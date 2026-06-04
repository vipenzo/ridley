---
name: embroid
category: generative-operations
since: ""
status: experimental
---

# embroid

## Signature

`(embroid path width & {:keys [offset resolution wall] :as opts})`

## Description

Shape-fn that perforates a thin swept **wall** — the complement of
`shell`. Where `shell` hollows a solid into a thin patterned wall,
`embroid` cuts a window pattern into a wall that is *already a single
surface* (a stroked path swept into a panel), the case where `shell`
does not apply because there is nothing to hollow out. Think of it as
making "a portion of a shell".

Unlike the other shape-fns, `embroid` takes the **path** that defines
the wall's centerline (not a shape) plus the wall thickness `width`. It
rebuilds the two faces of the wall by offsetting `±width/2`
**perpendicular to the path at every point**, so the perforation runs
through the wall thickness no matter how the path curves or is angled.
Each opening is a through-hole rimmed between the two faces; the result
is watertight, manifold, and oriented outward. Used with `loft` (or
`loft-n`). Does not modify turtle state.

## Parameters

| Parameter | Default | Description |
|---|---|---|
| `path` | — | The wall centerline (a path, e.g. from `(path …)`). |
| `width` | — | Wall thickness. The two faces sit `±width/2` from the centerline. |
| `:offset` | `[0 0]` | Shift the wall in the profile plane, replacing a `translate` you would otherwise apply to the stroked shape (e.g. to stack variants). |
| `:resolution` | ≈ `2·path-length` | Samples **along the path** (`u`). Controls how crisp the opening edges read in the path direction; the loft step count only refines the **sweep** (`t`). Raise for smoother openings (mesh grows with `resolution × loft-steps`). |
| `:wall` | — | Map of the pattern options below (or pass them as top-level kwargs). |

**Wall pattern (`:style` inside `:wall`):**

| Style | Options |
|---|---|
| `:honeycomb` (default) | `:cells` (8; hexes across the wall), `:wall-width` (0.3; strut width in cell units) |
| `:voronoi` | `:cells` (8), `:rows` (12), `:seed` (42), `:wall-width` (0.3) |
| `:pattern` | `:pattern` (a 2D shape used as the opening motif), `:spacing` (15; number or `[sx sy]`), `:grid` (`:square` / `:hex`), `:inset` (0; shrink the motif to fatten struts), `:invert?` (false; swap motif↔gaps) |

**Shared options:**

| Option | Default | Description |
|---|---|---|
| `:softness` | `0.6` | Isocontour ramp: smooth opening edges; `0` = hard staircased cut. |
| `:margin` | `0.05` | **Fraction** of each side kept solid. On a non-square wall the side and top/bottom frames differ in physical width (fraction of wall length vs sweep depth). |
| `:border` | — | World-units frame thickness, **uniform** on all four sides. Overrides `:margin` — use this for an even border. |

## Example

{{example: embroid-honeycomb}}

<!-- example-source: embroid-honeycomb -->
```clojure
(register panel
  (loft (embroid (path (f 3) (arc-h 50 90) (f 70)) 3
                 :resolution 400
                 :wall {:style :honeycomb :cells 8 :border 4})
        (f 45)))
```
<!-- /example-source -->

A curved wall perforated with a regular honeycomb, a uniform 4-unit solid
frame on every side. The path's straight and curved runs are both
perforated, because the pattern follows the path rather than a fixed
direction.

## Variations

{{example: embroid-pattern-hex}}

<!-- example-source: embroid-pattern-hex -->
```clojure
(register holes
  (loft (embroid (path (f 3) (arc-h 50 90) (f 70)) 3
                 :wall {:style :pattern :pattern (circle 4)
                        :spacing 12 :grid :hex :inset 0.5})
        (f 45)))
```
<!-- /example-source -->

`:style :pattern` tiles an arbitrary motif shape as the opening — here
round holes on a hexagonal grid. `:spacing` sets the grid pitch, `:grid`
chooses square or offset-hex rows, `:inset` shrinks the motif to fatten
the struts.

{{example: embroid-pattern-invert}}

<!-- example-source: embroid-pattern-invert -->
```clojure
(register bricks
  (loft (embroid (path (f 3) (arc-h 50 90) (f 70)) 3
                 :wall {:style :pattern :pattern (rotate (rect 20 5) 30)
                        :spacing 12 :invert? true})
        (f 45)))

```
<!-- /example-source -->

`:invert?` swaps motif and gaps: the tiled `rect` becomes the solid and the
spacing between tiles becomes the openings (a brick/tile look).

## Notes

- `embroid` takes a **path**, not a shape — it rebuilds the wall faces
  from the centerline, which is robust to miters/caps and to the wall's
  angle (an index-paired stroke outline is not).
- **Does not compose in thread with other shape-fns.** In the loft it
  stamps its own stored faces, so transforms applied after it
  (`(-> (embroid …) (tapered …))`) are silently ignored. Apply
  positioning with `:offset`, or `translate` the resulting mesh.
- Tune crispness with `:resolution` (along the path) independently of the
  loft step count (along the sweep) — you usually do **not** need a large
  `loft-n` once `:resolution` is set.
- `:border` gives a uniform physical frame; `:margin` is a per-axis
  fraction and so is uneven on a non-square wall.

## See also

- **Related:** `shell`, `woven-shell`, `loft`, `loft-n`, `stroke-shape`,
  `path`
