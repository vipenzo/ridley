---
name: morphed
category: generative-operations
since: ""
status: stable
---

# morphed

## Signature

`(morphed shape-a shape-b)`

## Description

Shape-fn that interpolates between two shapes along the path. At `t = 0`
the result is `shape-a`; at `t = 1` it is `shape-b`; in between, the
profile is the pointwise lerp. Used with `loft`, `bloft`, or `revolve`.
Does not modify turtle state.

Both shapes must have the same point count. Use `resample-shape` to match
counts when they differ — without that, the morph silently falls back to
returning `shape-a`.

## Parameters

- `shape-a` — start shape (at `t = 0`).
- `shape-b` — end shape (at `t = 1`).

## Example

{{example: morphed-basic}}

<!-- example-source: morphed-basic -->
```clojure
(def s (resample-shape (star 5 18 9) 32))
(def c (circle 15 32))
(register transition (loft (morphed s c) (f 40)))
```
<!-- /example-source -->

Smooth transition from a 5-point star to a circle along the loft. Both
shapes are resampled to 32 points so the pointwise blend is well-defined.

## Notes

- Different from `morph-shape` (a one-shot blend at a fixed `t`):
  `morphed` is a shape-fn that produces the whole sequence of rings
  along a loft/revolve path.
- For interpolating multiple shapes in a sequence, chain morphs through
  successive lofts or build a custom `shape-fn` with table lookup.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `morph-shape`, `resample-shape`, `loft`, `shape-fn`
