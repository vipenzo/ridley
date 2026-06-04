---
name: voronoi-shell
category: 2d-shapes
since: ""
status: stable
---

# voronoi-shell

## Signature

`(voronoi-shell shape)`
`(voronoi-shell shape & {:keys [cells wall seed relax resolution]})`

## Description

Generate a perforated 2D shape with a Voronoi cell pattern. Cell borders
become material, cell interiors become holes. The result is a standard
shape with `:holes`, compatible with `extrude`, `loft`, `revolve`, and all
shape-fns (`tapered`, `twisted`, `noisy`, …). Does not modify turtle
state.

`voronoi-shell` is a 2D operation, not a shape-fn — it takes a shape and
returns a shape. Compose with shape-fns via `->` threading.

## Parameters

- `shape` — base 2D contour.
- `:cells` — number of Voronoi cells (default `20`).
- `:wall` — wall thickness between cells (default `1.5`).
- `:seed` — random seed for reproducibility (default `0`).
- `:relax` — Lloyd relaxation iterations; higher = more uniform cells
  (default `2`).
- `:resolution` — points per hole; affects loft smoothness (default `16`).

## Example

{{example: voronoi-shell-basic}}

<!-- example-source: voronoi-shell-basic -->
```clojure
(register voro-tube (extrude (voronoi-shell (circle 20) :cells 40 :wall 1.5) (f 50)))
```
<!-- /example-source -->

A perforated tube: the cylinder wall is broken up into 40 Voronoi cells
separated by 1.5-unit ribs.

## Variations

{{example: voronoi-shell-composed}}

<!-- example-source: voronoi-shell-composed -->
```clojure
(register voro-vase
  (loft (-> (voronoi-shell (circle 15 64) :cells 25 :wall 1.5 :seed 42)
            (twisted :angle 45)
            (tapered :from 0.8 :to 1.2))
    (f 60)))
```
<!-- /example-source -->

The output composes with shape-fns: thread `tapered` and `twisted` after
the voronoi step to twist and taper a perforated vase along the loft path.

## Notes

- Same `:seed` always produces the same pattern; different seeds vary the
  layout.
- `:relax 0` gives raw Voronoi cells; higher values make cells more
  uniform via Lloyd relaxation.
- Uses d3-delaunay for Voronoi computation and Clipper2 for cell
  clipping/inset.

## See also

- **Related:** `pattern-tile`, `shape-offset`, `shape-difference`
