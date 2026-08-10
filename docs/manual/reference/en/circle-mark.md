---
name: circle-mark
category: acquisition
since: ""
status: stable
---

# circle-mark

## Signature

`(circle-mark circle-map)`

## Description

A **measured circle** of an acquisition — the curved sibling of `edge-mark`,
written when a curve you measured turns out to bend as a circle. It **returns
the map unchanged**.

```clojure
:edges {:foro (circle-mark {:position [2.31 1.94 8.59]
                            :heading  [-0.01 0.02 0.99]
                            :up       [0 1 0]
                            :radius   6.2})}
```

A pose first, like every mark, and the pose is the useful part: the turtle
stands at the **centre** with its nose along the axis, so the circle is
something to build with rather than a number to read.

```clojure
;; bore it, or raise it — exactly where the photographs found it
(turtle (:foro (:edges A)) (extrude (circle 6.2) (f 20)))
```

It is gentle but not silent: it checks the expected keys and that the radius is
a positive number, and reports without touching the data.

## Parameters

- `circle-map` — `{:position <centre> :heading <axis> :up … :radius r}`, plus
  the display keys `:show` (`false` hides it) and `:label` (`false`, or a string
  to rename it over the photograph).

## Notes

- To measure one: arm `(edit-edge-mark)` (or `(edit-curve-mark)`), paint the arc
  on two photographs, and press **`c`** instead of Enter. `c` is a
  *declaration* — "this curve is a circle" — not a different measurement, and
  the panel tells you the diameter it found before you commit to it.
- It is refused when too little of the arc is visible: a short arc fits a circle
  of almost any size, and a fitted diameter of 390 mm for a 130 mm rim is the
  kind of answer that passes every other test. Keep the curve instead and use it
  for a plane.
- The measured diameter is the number you came for — read it straight off the
  source, no tooling needed.

## See also

- `edit-curve-mark` — measure the arc first
- `curve-mark` — when the curve is not a circle
- `edge-mark` — the straight sibling
- `acquire` — the form whose `:edges` this lives in
