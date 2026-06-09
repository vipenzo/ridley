---
name: mid
category: faces
since: ""
status: stable
---

# mid

## Signature

`(mid a b)`
`(mid path i)`

## Description

Midpoint helper, returning a `[x y z]` point. Two forms:

- `(mid a b)` — midpoint of two points (`[x y]` or `[x y z]`; 2D inputs
  are padded to `z=0`).
- `(mid path i)` — midpoint of segment `i` (0-based) of a path: the i-th
  edge, between waypoints `i` and `i+1`.

It pairs with `ruler`/`distance` to measure to a specific point, and is
especially handy with the control-polygon `bezier-as :control`, whose
curve passes through the segment midpoints — so `(mid poly i)` is exactly
where the rounded curve touches.

## Parameters

- `(mid a b)` — `a`, `b`: two points.
- `(mid path i)` — `path`: a path; `i`: 0-based segment index.

## Example

<!-- example-source: mid-segment -->
```clojure
(def poly (path (f 30) (th 45) (f 25) (th 45) (f 30)))
;; iterate by eye until the 45° segment sits at the wanted distance:
(ruler [0 45] (mid poly 1))     ; → distance from a point to the bevel's midpoint
```
<!-- /example-source -->

## Notes

- Dispatch is by first-argument type: a path map → the `(mid path i)`
  form; otherwise the two-point form.
- For the explicit segment-midpoint name, see `seg-mid`.

## See also

- **Related:** `seg-mid`, `ruler`, `distance`, `bezier-as`
