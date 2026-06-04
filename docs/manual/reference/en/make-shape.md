---
name: make-shape
category: 2d-shapes
since: ""
status: stable
---

# make-shape

## Signature

`(make-shape points)`
`(make-shape points opts)`

## Description

Low-level constructor: wrap a sequence of `[x y]` points (and optional
holes) into a shape map. Outer contour should be CCW; holes should be CW.
Does not modify turtle state.

Used directly when none of the higher-level constructors (`circle`, `rect`,
`polygon`, `poly`, `star`, `shape`, `stroke-shape`, `svg-shapes`) fits — for
instance when building a shape from computed point arrays or when explicit
control over the anchoring flags is required.

## Parameters

- `points` — vector of `[x y]` points forming a closed outer contour.
- `opts` — optional map with any of:
  - `:centered?` — `true` to make the shape's 2D origin coincide with the
    turtle (default `false`).
  - `:holes` — vector of hole contours, each a vector of `[x y]` points
    in CW winding.
  - `:preserve-position?` — keep raw 2D coordinates with no re-anchoring
    (see Spec §4 *Anchoring flags*).
  - `:align-to-heading?` — swap plane axes so 2D x maps to heading
    direction (used internally by `text-on-path`).
  - `:flip-plane-x?` — negate the plane-x axis (equivalent to `(tr 180)`
    before stamping).

## Example

{{example: make-shape-basic}}

<!-- example-source: make-shape-basic -->
```clojure
(def pts (vec (for [i (range 12)]
                (let [a (* i (/ (* 2 PI) 12))]
                  [(* 18 (cos a)) (* 12 (sin a))]))))
(register oval (extrude (make-shape pts {:centered? true}) (f 6)))
```
<!-- /example-source -->

Build a 12-point ellipse from computed coordinates and mark it as centered
so the centroid lands on the turtle when extruded.

## Variations

{{example: make-shape-with-hole}}

<!-- example-source: make-shape-with-hole -->
```clojure
(def outer [[-20 -10] [20 -10] [20 10] [-20 10]])
(def hole  [[-5 -3] [-5 3] [5 3] [5 -3]])
(register washer (extrude (make-shape outer {:centered? true :holes [hole]}) (f 3)))
```
<!-- /example-source -->

A rectangular plate with a rectangular hole. The hole contour is given in
CW winding (opposite of the outer).

## Notes

- For most cases, prefer the higher-level constructors — they set the
  correct anchoring flags by default.
- `:centered?` and `:preserve-position?` resolve to the same offset
  (`[0 0]`); the distinction is documentary intent.

## See also

- **Related:** `poly`, `shape`, `circle`, `rect`, `polygon`
