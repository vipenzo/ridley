---
name: render-slice
category: export
since: ""
status: stable
---

# render-slice

## Signature

`(render-slice target axis position)`

## Description

Render a 2D cross-section of a target through an axis-aligned plane. Returns a PNG data URL showing the contour outlines where the plane intersects the mesh.

`render-slice` is the right tool when an orthographic view does not reveal the internal structure — for example to check wall thickness, hollow cavities, or alignment of internal features. The output is a 2D outline drawing, not a 3D render: clean, vectorial, and easy to read.

## Parameters

- `target` — a registered name keyword (e.g. `:cup`) or a mesh reference.
- `axis` — `:x`, `:y`, or `:z`. The plane is perpendicular to this axis.
- `position` — float, the position along the axis where the plane sits.

## Example

{{example: render-slice-mug}}

<!-- example-source: render-slice-mug -->
```clojure
;; A mug-like profile, then slice it
(register cup
  (revolve (shape (f 20) (th -90) (f 30) (th -90) (f 15))))

(def horizontal (render-slice :cup :z 15))   ; cross-section at Z = 15
(def sagittal   (render-slice :cup :x 0))    ; sagittal slice through centre

(save-image horizontal "cup-z15.png")
(save-image sagittal   "cup-x0.png")
```
<!-- /example-source -->

A revolved mug profile is sliced at two planes: a horizontal one at Z = 15 (showing the wall thickness as concentric rings), and a sagittal one at X = 0 (showing the full vertical profile).

## Notes

- Output is a PNG data URL. Pass it to `save-image` to download.
- The plane is always axis-aligned. For oblique slicing, use `slice-at-plane` to produce the geometry and then `render-view` it.
- `target` resolves to a single mesh; to slice the whole scene, register a union mesh and slice that.

## See also

- **Related:** `render-view`, `save-image`
