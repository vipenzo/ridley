---
name: svg-shapes
category: 2d-shapes
since: ""
status: stable
---

# svg-shapes

## Signature

`(svg-shapes svg-data)`
`(svg-shapes svg-data & {:keys [segments scale center flip-y]})`
`(svg-shape svg-data & {:keys [id index segments scale center flip-y]})`

## Description

Import 2D shapes from parsed SVG data. `svg-shapes` returns a vector with
one Ridley shape per geometry element (path, rect, circle, polygon) in the
SVG. `svg-shape` is the single-element variant: it returns one shape,
selected by `:id` or `:index`. Both functions accept the result of
`(svg "<svg>...</svg>")` and curve elements are discretised at construction
time. Neither modifies turtle state.

The returned shapes are ready for `extrude`, `loft`, `revolve`, and the
shape booleans.

## Parameters

- `svg-data` — parsed SVG map produced by `(svg "...")`.
- `:segments` — curve discretisation per arc / bezier (default `64`).
- `:scale` — uniform scale applied to imported coordinates (default `1.0`).
- `:center` — center each shape at its centroid (default `true`).
- `:flip-y` — invert Y to map SVG screen coordinates to Ridley's math frame
  (default `true`).
- `:id` (svg-shape only) — select element by SVG `id` attribute.
- `:index` (svg-shape only) — select element by position (0-based).

## Example

{{example: svg-shapes-basic}}

<!-- example-source: svg-shapes-basic -->
```clojure
(def logo-data (svg "<svg><circle cx='0' cy='0' r='20'/><rect x='-30' y='-5' width='60' height='10'/></svg>"))
(def parts (svg-shapes logo-data))
(register logo (extrude (first parts) (f 3)))
```
<!-- /example-source -->

The SVG contains two geometry elements; `svg-shapes` returns a vector with
two shapes, one per element. Iterate or pick by index.

## Variations

{{example: svg-shape-by-id}}

<!-- example-source: svg-shape-by-id -->
```clojure
(def data (svg "<svg><path id='outline' d='M0 0 L20 0 L20 10 Z'/></svg>"))
(register tag (extrude (svg-shape data :id "outline") (f 2)))
```
<!-- /example-source -->

Use `svg-shape` with `:id` to pick a single named element instead of the
full vector.

## Notes

- `svg-shape` throws when neither `:id` nor `:index` matches an element.
- A vector of shapes (as returned by `svg-shapes`) is accepted by
  `extrude`, `loft`, `revolve`, `stamp`, and the shape transformations —
  each shape is processed independently and the meshes are merged.
- Default `:flip-y true` accounts for SVG's Y-down screen convention; set
  to `false` to keep raw SVG coordinates.

## See also

- **Related:** `make-shape`, `poly`, `path-to-shape`
