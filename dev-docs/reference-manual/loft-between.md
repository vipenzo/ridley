---
name: loft-between
category: generative-operations
since: ""
status: stable
---

# loft-between

## Signature

`(loft start-shape end-shape & path-commands)`

## Description

Two-shape mode of `loft`: pass a start shape and an end shape with the
same point count; the loft linearly interpolates between them at each
ring. This is the same as calling `loft` with two shapes; the
`loft-between` name is the alias documented in Spec §6, retained here for
discoverability. See `loft` for full behavioural details. Does not modify
turtle state.

For shapes with different point counts, use `resample-shape` first.

## Parameters

- `start-shape`, `end-shape` — 2D shapes with the same point count.
- `path-commands` — turtle movement forms or a recorded path.

## Example

{{example: loft-between-basic}}

<!-- example-source: loft-between-basic -->
```clojure
(register taper (loft (circle 20) (circle 10) (f 40)))
```
<!-- /example-source -->

A taper from radius 20 to radius 10 over 40 units forward. Both circles
share point count (default resolution), so they're morph-compatible.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `loft`, `loft-n`, `morph-shape`, `resample-shape`
