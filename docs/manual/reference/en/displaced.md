---
name: displaced
category: generative-operations
since: ""
status: stable
---

# displaced

## Signature

`(displaced shape-or-fn displace-fn)`

## Description

Shape-fn that applies a custom radial displacement to each vertex. The
displacement function receives the vertex and the current path
fraction `t`; the returned scalar moves the vertex radially from the
shape's centroid. Used with `loft` or `revolve`. Does not modify
turtle state.

`displaced` is the most flexible profile displacement: pass any function
that fits, including ones that call `noise`, `fbm`, or `sample-heightmap`
directly. Built-in shape-fns like `rugged`, `noisy`, and `heightmap` are
all implemented in terms of `displaced`.

## Parameters

- `shape-or-fn` — a 2D shape, or another shape-fn (composes).
- `displace-fn` — function `(fn [point t] -> number)`. The returned
  number is the radial offset applied to that vertex at that ring.

## Example

{{example: displaced-basic}}

<!-- example-source: displaced-basic -->
```clojure
(register wave
  (loft (displaced (circle 15 64)
                   (fn [p t] (* 2 (sin (+ (* (angle p) 6) (* t 8))))))
        (f 40)))
```
<!-- /example-source -->

A custom helix-like ripple: amplitude 2, six waves around the contour,
and a per-`t` phase shift that twists the ripple along the path.

## Notes

- The displacement is always radial from the shape's centroid. For
  non-radial offsets (e.g. shears, normal-direction pushes), write a
  custom `shape-fn` instead.
- Compose with other shape-fns via `->` threading.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `noisy`, `rugged`, `heightmap`, `shape-fn`,
  `displace-radial`, `angle`
