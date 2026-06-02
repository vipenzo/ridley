---
name: pattern-tile
category: 2d-shapes
since: ""
status: stable
---

# pattern-tile

## Signature

`(pattern-tile target pattern & {:keys [spacing inset]})`

## Description

Tile a pattern shape across the bounding box of a target shape and
subtract: every pattern copy that overlaps the target becomes a hole. The
result is a shape with holes, compatible with `extrude`, `loft`,
`revolve`, and all shape-fns. Does not modify turtle state.

The pattern can be a single shape or a vector of shapes.

## Parameters

- `target` — base 2D shape that will hold the holes.
- `pattern` — shape (or vector of shapes) repeated on a grid.
- `:spacing` — `[sx sy]` tile period. Defaults to the pattern's bounding
  box size.
- `:inset` — shrink each pattern copy by this amount before subtraction
  (default `0`).

## Example

{{example: pattern-tile-basic}}

<!-- example-source: pattern-tile-basic -->
```clojure
(register grid-plate (extrude (pattern-tile (rect 60 40) (circle 2 16) :spacing [6 6]) (f 3)))
```
<!-- /example-source -->

A flat plate with a regular 6×6 grid of circular holes.

## Variations

{{example: pattern-tile-svg}}

<!-- example-source: pattern-tile-svg -->
```clojure
(def motif (svg-shape (svg "<svg><path d='M0 0 L4 0 L2 4 Z'/></svg>")))
(register decoded (extrude (pattern-tile (rect 40 40) motif :spacing [12 12]) (f 2)))
```
<!-- /example-source -->

Tile an SVG motif across a square plate and subtract — useful for
decorative panels.

## Notes

- The result may contain holes; downstream operations handle them
  natively.
- Works with any target / pattern combination: circles, SVG imports,
  custom polygons.
- Compatible as a `:cap-top` / `:cap-bottom` style for `shell` via the
  `:grid` cap style — see the `shell` documentation.

## See also

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** `voronoi-shell`, `shape-difference`, `shape-offset`
