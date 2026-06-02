---
name: mark-pos
category: path
since: ""
status: stable
---

# mark-pos / mark-x / mark-y

## Signature

`(mark-pos path mark-name)`
`(mark-x path mark-name)`
`(mark-y path mark-name)`

## Description

Read the 2D position of a mark inside a path. `mark-pos` returns
`[x y]`; `mark-x` and `mark-y` are shorthand for the individual
components. All three trace the path's commands in the XY plane
starting at the origin (heading `+X`), and return the position at the
moment the mark was recorded.

These are inspection helpers: they let geometry depend on where marks
land — e.g. compute the height at which a profile should switch, or
size a hole to a mark's coordinate — without running the path on the
turtle.

Returns `nil` when the mark is not present in the path.

## Parameters

- `path` — a recorded path map.
- `mark-name` — the keyword identifying the mark.

## Example

{{example: mark-query-basic}}

<!-- example-source: mark-query-basic -->
```clojure
(def silhouette
  (path
    (f 10) (th 30)
    (f 20) (mark :ridge)
    (th -30) (f 10)))

(out (str ":ridge = " (mark-pos silhouette :ridge)))
(out (str ":ridge x = " (mark-x silhouette :ridge)))
(out (str ":ridge y = " (mark-y silhouette :ridge)))
```
<!-- /example-source -->

`mark-pos` reports the 2D position the turtle would reach if the path
were walked from the origin along `+X`. `mark-x` and `mark-y` are the
component extractors.

## Variations

{{example: mark-query-parametric}}

<!-- example-source: mark-query-parametric -->
```clojure
(def profile
  (path
    (f 0) (th 90)
    (f 30) (mark :top)
    (th -90) (f 10)))

;; Use the recorded :top height to size a feature
(def top-h (mark-y profile :top))
(register column (revolve (path-to-shape profile)))
(register cap (translate (cyl 12 4) [0 0 (- top-h 2)]))
```
<!-- /example-source -->

Path-driven geometry can react to mark positions computed from the
recorded path itself — useful when the same skeleton is the
single source of truth for several derived objects.

## Notes

- These helpers consider only the 2D projection (`f`, `th`,
  `set-heading`) — `tv`/`tr`/`u` are ignored. For a full 3D pose, use
  `anchors` (which resolves marks via the path replay machinery).
- A path with no matching mark yields `nil`; `mark-x` / `mark-y` then
  yield `nil` too. Guard against this when using the results in
  arithmetic.

## See also

- **Guide:** placeholder → cap. 5 (Paths and anchors)
- **Related:** `mark`, `anchors`, `bounds-2d`, `path`
