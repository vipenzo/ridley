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
profile is the pointwise lerp. Used with `loft` or `revolve`. Does
not modify turtle state.

If point counts differ, both shapes are auto-resampled to the larger
count. `shape-b`'s vertex array is then angularly aligned to `shape-a`
so corresponding indices follow the shortest path: morphing `rect` to
`circle` produces a rounded square in the middle, not a self-intersecting
bowtie.

## Parameters

- `shape-a` — start shape (at `t = 0`).
- `shape-b` — end shape (at `t = 1`).

## Example

{{example: morphed-basic}}

<!-- example-source: morphed-basic -->
```clojure
(register transition
  (loft (morphed (star 5 18 9) (circle 15 32)) (f 40)))
```
<!-- /example-source -->

Smooth transition from a 5-point star to a circle along the loft. Both
shapes have different point counts but `morphed` auto-resamples and
aligns them, so the blend is well-defined.

## Notes

- Different from `morph-shape` (a one-shot blend at a fixed `t`):
  `morphed` is a shape-fn that produces the whole sequence of rings
  along a loft/revolve path.
- For interpolating multiple shapes in a sequence, chain morphs through
  successive lofts or build a custom `shape-fn` with table lookup.

## See also

- **Related:** `morph-shape`, `resample-shape`, `loft`, `shape-fn`
