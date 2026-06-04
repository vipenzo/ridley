---
name: subpath-y
category: path
since: ""
status: stable
---

# subpath-y

## Signature

`(subpath-y path from-h to-h)`

## Description

Extract the portion of a path that lies within a height band, where
height is measured as signed distance from the path's starting Y. The
returned path is clipped at the band boundaries and re-anchored so its
Y starts at 0 — useful for sampling a section of a `revolve` profile
without dragging along its tail.

If the path travels downward (end Y less than start Y), heights are
measured downward; in both cases `from-h` and `to-h` are positive
distances along the path's "natural" Y direction. The output keeps the
ordering of the input.

Returns `nil` when the resulting subpath has fewer than two points.

## Parameters

- `path` — a recorded path map.
- `from-h` — start of the height band (a positive distance from path
  start).
- `to-h` — end of the height band (a positive distance, > `from-h`).

## Example

{{example: subpath-y-basic}}

<!-- example-source: subpath-y-basic -->
```clojure
(def profile
  (path
    (f 5) (th 80) (f 15) (arc-h 5 -160) (f 15)))

;; Use only the band 6..18 of the profile (output starts at Y=0)
(def band (subpath-y profile 6 18))
(register slice (revolve (path-to-shape band)))
```
<!-- /example-source -->

A band of the profile is extracted and revolved, producing a slice of
the original axisymmetric surface.

## Variations

{{example: subpath-y-stacked}}

<!-- example-source: subpath-y-stacked -->
```clojure
(def col (path (f 30) (th 120) (f 60))) ; a cone: radius 30 at the base, apex on the axis, ~52 tall
(stamp col)
(register lower (revolve (path-to-shape (subpath-y col 0 25))))
(register upper (translate
                  (revolve (path-to-shape (subpath-y col 25 52)))
                  0 0 50))

```
<!-- /example-source -->

Slicing the same source profile into vertical bands lets two revolutions
share a single source while being treated as separate objects.

## Notes

- Heights are measured from the path's starting Y, signed by the path's
  overall direction (upward vs downward). Reverse the path first if
  the input convention does not match — `subpath-y` does not introduce
  its own orientation.
- Boundary crossings are clipped by linear interpolation between the
  two adjacent waypoints. Curves (arcs, beziers) are sampled to
  waypoints first, then clipped; for very low-resolution paths the
  cut may show small piecewise-linear segments at the boundary.

## See also

- **Related:** `offset-x`, `path-to-shape`, `revolve`, `path`
