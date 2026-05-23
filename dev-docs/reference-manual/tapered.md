---
name: tapered
category: generative-operations
since: ""
status: stable
---

# tapered

## Signature

`(tapered shape-or-fn & {:keys [from to]})`

## Description

Shape-fn that scales the cross-section uniformly along the path. At `t = 0`
the profile is scaled by `:from`; at `t = 1` it is scaled by `:to`; in
between the factor is linearly interpolated. Used with `loft`, `bloft`,
or `revolve`. Does not modify turtle state.

The most common case is a cone (`:to 0`): the profile shrinks to zero
over the length of the path. `:from` defaults to `1`, so a single `:to`
argument tapers from full size to the chosen factor.

## Parameters

- `shape-or-fn` — a 2D shape, or another shape-fn (composes).
- `:from` — scale factor at `t = 0` (default `1`).
- `:to` — scale factor at `t = 1` (default `0`).

## Example

{{example: tapered-cone}}

<!-- example-source: tapered-cone -->
```clojure
(register cone (loft (tapered (circle 20) :to 0) (f 40)))
```
<!-- /example-source -->

A cone is the canonical tapered loft: a circle shrunk to zero along a
straight path.

## Variations

{{example: tapered-expand}}

<!-- example-source: tapered-expand -->
```clojure
(register horn (loft (tapered (circle 5) :from 1 :to 4) (f 30)))
```
<!-- /example-source -->

Expanding profile: scale factor goes from 1 at the base to 4 at the tip,
producing a flared horn.

## Notes

- Shape-fns compose with `->` threading:
  `(-> (circle 15) (fluted :flutes 12) (tapered :to 0.5))` adds flutes
  first, then tapers the result.
- For non-uniform tapering (e.g. only along Y), use a custom `shape-fn`
  with `scale-shape s sx sy` inside.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `loft`, `revolve`, `shape-fn`, `profile`, `capped`
