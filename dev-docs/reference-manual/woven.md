---
name: woven
category: generative-operations
since: ""
status: stable
---

# woven

## Signature

`(woven shape-or-fn & {:keys [warp weft amplitude thread]})`

## Description

Shape-fn that displaces the profile radially with an interlocking
over/under woven-fabric pattern. Threads run in two directions — warp
(along the path) and weft (around the contour); at each cell of the
resulting grid one thread sits "over" the other, alternating in
checkerboard. Used with `loft`, `bloft`, or `revolve`. Does not modify
turtle state.

`woven` is the radial-displacement-only variant. For a true 3D over/under
look where threads also shift in and out radially at crossings, see
`woven-shell`.

## Parameters

- `shape-or-fn` — a 2D shape, or another shape-fn (composes).
- `:warp` — number of warp threads per tile around the contour
  (default `6`).
- `:weft` — number of weft threads per tile along the path (default `4`).
- `:amplitude` — radial amplitude of the thread height (default `1.0`).
- `:thread` — thread width as a fraction of the cell, in `[0, 0.5]`
  (default `0.42`).

## Example

{{example: woven-basic}}

<!-- example-source: woven-basic -->
```clojure
(register fabric (loft (woven (circle 18 96) :warp 8 :weft 6 :amplitude 1.2) (f 40)))
```
<!-- /example-source -->

A woven cylinder with 8 warp threads and 6 weft threads, each lifted by
1.2 units.

## Notes

- The base shape needs enough points to resolve the threads
  (`(circle r n)` with `n` ≥ several `:warp`/`:weft` cycles).
- Shape-fns compose with `->` threading.
- For radial offsets at crossings (true 3D weave), use `woven-shell`.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `woven-shell`, `displaced`, `noisy`, `shell`
