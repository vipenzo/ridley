---
name: bezier-as
category: turtle-movement
since: ""
status: stable
---

# bezier-as

## Signature

`(bezier-as path)`
`(bezier-as path & {:keys [tension steps cubic max-segment-length control]})`
`(bezier-as path :control true)`

## Description

Smooth a recorded path with one bezier curve per segment, keeping C1
continuity at the junctions. Returns a new path; does not modify turtle
state on its own.

Works both in direct turtle mode (the smoothed path is replayed) and
inside `path` recordings (the smoothed segments are merged into the
recorded path).

## Parameters

- `path` — input recorded path.
- `:tension` — control-point distance factor as a fraction of segment
  length. Default `0.33`.
- `:steps` — number of segments per bezier (default: resolution-derived).
- `:cubic` — when `true`, use Catmull-Rom spline tangents (cubic
  bezier). Default `false` (quadratic).
- `:max-segment-length` — subdivide long input segments before smoothing
  so each bezier covers at most this length. Useful for sparse paths.
- `:control` — when `true`, treat the path's vertices as **off-curve
  control points** instead of interpolating them: the curve passes through
  each segment *midpoint*, tangent to the polygon there, with the ends
  clamped (one quadratic per interior vertex). This *rounds* the control
  polygon — the dual of the default interpolating mode — like a quadratic
  B-spline / TrueType outline. `:tension`/`:cubic` don't apply here (the
  shape is fixed by the polygon).

## Example

{{example: bezier-as-basic}}

<!-- example-source: bezier-as-basic -->
```clojure
(def waypoints (path (f 20) (th 90) (f 20) (th -45) (f 20)))
(register tube (extrude (circle 4) (bezier-as waypoints)))
```
<!-- /example-source -->

Smooth a piecewise-linear path and extrude along the resulting curve.

## Variations

{{example: bezier-as-tension}}

<!-- example-source: bezier-as-tension -->
```clojure
(def waypoints (path (f 20) (th 90) (f 20)))
(register tube (extrude (circle 4) (bezier-as waypoints :tension 0.6 :steps 48)))
```
<!-- /example-source -->

Higher `:tension` produces a more swoopy result; `:steps` controls the
per-bezier resolution.

{{example: bezier-as-control}}

<!-- example-source: bezier-as-control -->
```clojure
;; vertices as control points: the L's corner is rounded into a fillet
(def corner (path (f 30) (th 90) (f 30)))
(register wall (extrude (stroke-shape (bezier-as corner :control true) 3) (f 10)))
```
<!-- /example-source -->

`:control` rounds the control polygon (curve through the midpoints) rather
than passing through the vertices — handy for filleting corners or shaping
a profile by its control polygon. See `examples/spigolo-quattro-modi.clj`.

## Notes

- Inside an extrusion, `bezier-as` is typically the right call when the
  source path has sharp corners that the extrusion should smooth out.
- For one-off bezier segments to a target (rather than smoothing an
  existing path), use `bezier-to` / `bezier-to-anchor`.

## See also

- **Related:** `bezier-to`, `bezier-to-anchor`, `path`, `resolution`
