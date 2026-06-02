---
name: circle
category: 2d-shapes
since: ""
status: stable
---

# circle

## Signature

`(circle radius)`
`(circle radius segments)`

## Description

Construct a circular 2D shape centered at the origin. The shape is centered
(`:centered? true`) so the centroid coincides with the turtle when projected.
Does not modify turtle state.

With one argument, the segment count is read from the current turtle
resolution. With two arguments, the count is taken verbatim.

## Parameters

- `radius` — circle radius.
- `segments` — number of edges around the circumference. Optional; defaults
  to the current resolution.

## Example

{{example: circle-basic}}

<!-- example-source: circle-basic -->
```clojure
(register disc (extrude (circle 20) (f 5)))
```
<!-- /example-source -->

A flat disc: a 20-unit circle extruded forward by 5. The segment count is
inherited from the turtle's resolution setting.

## Variations

{{example: circle-explicit-segments}}

<!-- example-source: circle-explicit-segments -->
```clojure
(register hex-tube (extrude (circle 15 6) (f 30)))
```
<!-- /example-source -->

Forcing a low segment count produces a regular polygon. Equivalent to
`(polygon 6 15)`.

## Notes

- For best results with `loft` and shape-fns, prefer a high segment count
  (e.g. `(circle r 64)` or `128`) — circumferential detail is fixed at
  construction time.

## See also

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** `polygon`, `rect`, `star`, `extrude`, `loft`, `revolve`
