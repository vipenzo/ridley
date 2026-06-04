---
name: quick-path
category: path
since: ""
status: stable
---

# quick-path

## Signature

`(quick-path & numbers)`
`(qp & numbers)`

## Description

Build a path from a compact alternating sequence of forward distances
and turn angles. Odd-position numbers are forward distances; even-position
numbers are turns around the turtle's up axis (degrees, positive = left,
following `th`'s convention). The result is a path map, identical in
structure to one produced by `(path (f …) (th …) …)`.

`qp` is the documented short alias.

`quick-path` is the shortest way to write a 2D-feeling polyline path
when no marks or branches are involved. For paths with arcs, beziers,
marks, or side-trips, use `path`.

## Parameters

- `numbers` — alternating `distance` / `angle` values, starting with a
  forward distance. Distance is in turtle units; angle is in degrees.
  The sequence may end on either kind.

## Example

{{example: quick-path-basic}}

<!-- example-source: quick-path-basic -->
```clojure
(register zigzag
  (extrude (circle 1) (qp 20 90 30 -45 10)))
```
<!-- /example-source -->

Reads as: forward 20, left 90, forward 30, right 45, forward 10. The
turtle's heading at the end of the path is the sum of all turns.

## Variations

{{example: quick-path-equivalent}}

<!-- example-source: quick-path-equivalent -->
```clojure
;; (qp 20 90 30) is equivalent to
;; (path (f 20) (th 90) (f 30))
(register a (extrude (circle 1) (qp 20 90 30)))
(register b (translate (extrude (circle 1) (path (f 20) (th 90) (f 30))) [0 0 10]))
```
<!-- /example-source -->

`a` and `b` produce identical geometry; only the second is offset for
visual comparison.

## Notes

- `qp` is registered as an alias of `quick-path`. Both are valid in
  user code.
- Turns are always around the turtle's up axis (equivalent to `th`).
  For pitch (`tv`) or roll (`tr`), `quick-path` is not enough — use
  `path` with explicit commands.
- For paths driven by coordinates rather than incremental turtle
  commands, use `poly-path`.

## See also

- **Related:** `path`, `poly-path`, `f`, `th`
