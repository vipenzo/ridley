---
name: stroke-shape
category: 2d-shapes
since: ""
status: stable
---

# stroke-shape

## Signature

`(stroke-shape path width)`
`(stroke-shape path width & {:keys [start-cap end-cap join miter-limit]})`

## Description

Build a 2D outline shape by stroking a recorded path with a given width.
The result is a closed contour suitable for `extrude`, `revolve`, `loft`,
and the shape booleans. Does not modify turtle state.

The path is sampled in 2D (XY projection); the stroke is offset by `width / 2`
on each side, with the chosen end caps and join style.

## Parameters

- `path` — a recorded path map.
- `width` — total stroke width. Required.
- `:start-cap` — `:flat` (default), `:round`, `:square`.
- `:end-cap` — `:flat` (default), `:round`, `:square`.
- `:join` — corner join style: `:miter` (default), `:bevel`, `:round`.
- `:miter-limit` — maximum miter ratio before falling back to bevel
  (default `4`).

## Example

{{example: stroke-shape-basic}}

<!-- example-source: stroke-shape-basic -->
```clojure
(def trail (path (f 30) (th 60) (f 25) (th -90) (f 20)))
(register ribbon (extrude (stroke-shape trail 3) (f 4)))
```
<!-- /example-source -->

The path defines the centerline; the stroke produces a 3-unit wide ribbon
profile, then extruded forward into a flat plate.

## Variations

{{example: stroke-shape-round-caps}}

<!-- example-source: stroke-shape-round-caps -->
```clojure
(def arc-path (path (arc-h 20 90)))
(register lozenge
  (extrude (stroke-shape arc-path 4
             :start-cap :round :end-cap :round :join :round)
           (f 2)))
```
<!-- /example-source -->

Round caps and round joins for a smooth lozenge-like outline along a
curved path.

## Notes

- The output shape is centered (`:centered? true`).
- For sharp turns where the miter would explode, the join falls back to a
  bevel once the ratio exceeds `:miter-limit`.

## See also

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** `path-to-shape`, `shape`, `shape-offset`, `path`
