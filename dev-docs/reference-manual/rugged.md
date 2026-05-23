---
name: rugged
category: generative-operations
since: ""
status: stable
---

# rugged

## Signature

`(rugged shape-or-fn & {:keys [amplitude frequency]})`

## Description

Shape-fn that displaces vertices radially with a sine pattern, constant
along the path. The displacement depends only on the vertex angle, not
on `t`, so each ring carries the same ripple — useful for ridged or
gear-like surfaces. Used with `loft`, `bloft`, or `revolve`. Does not
modify turtle state.

Compared with `fluted` (cosine, axis-aligned), `rugged` uses sine, so the
ripples are phase-shifted relative to the shape's axes.

## Parameters

- `shape-or-fn` — a 2D shape, or another shape-fn (composes).
- `:amplitude` — radial displacement amplitude (default `1`).
- `:frequency` — number of full sine cycles around the contour
  (default `6`).

## Example

{{example: rugged-basic}}

<!-- example-source: rugged-basic -->
```clojure
(register gear (loft (rugged (circle 15 96) :amplitude 2 :frequency 16) (f 20)))
```
<!-- /example-source -->

A gear-like silhouette: 16 ripples around the circle, each 2 units high.

## Notes

- Choose `:frequency` together with the base circle's segment count.
  Points per ring should be at least `4 × frequency` for clean ripples.
- For random / organic surfaces use `noisy`; for axis-aligned grooves use
  `fluted`.
- Shape-fns compose with `->` threading.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `fluted`, `noisy`, `displaced`, `loft`, `shape-fn`
