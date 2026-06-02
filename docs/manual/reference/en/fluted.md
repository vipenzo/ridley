---
name: fluted
category: generative-operations
since: ""
status: stable
---

# fluted

## Signature

`(fluted shape-or-fn & {:keys [flutes depth]})`

## Description

Shape-fn that adds longitudinal grooves to the profile by displacing
points radially with a cosine pattern aligned to the shape's axes. The
displacement is constant along the path, so each ring is identical —
useful for fluted columns and ridged surfaces. Used with `loft` or
`revolve`. Does not modify turtle state.

## Parameters

- `shape-or-fn` — a 2D shape, or another shape-fn (composes).
- `:flutes` — number of longitudinal grooves (default `6`).
- `:depth` — radial amplitude of the grooves (default `1`).

## Example

{{example: fluted-column}}

<!-- example-source: fluted-column -->
```clojure
(register column (loft (fluted (circle 15 64) :flutes 12 :depth 1.5) (f 60)))
```
<!-- /example-source -->

A classical fluted column: 12 grooves, each 1.5 units deep, on a 64-point
circle. Higher point counts produce smoother grooves.

## Variations

{{example: fluted-tapered}}

<!-- example-source: fluted-tapered -->
```clojure
(register column
  (loft (-> (circle 15 48) (fluted :flutes 20 :depth 1.5) (tapered :to 0.85))
        (f 80)))
```
<!-- /example-source -->

Compose with `tapered` for a tapering column. The flutes shrink with the
profile.

## Notes

- For best results, give the base circle enough segments (`(circle r n)`
  with `n >= 4 * flutes`). Too few segments hides the cosine modulation
  between vertices.
- Shape-fns compose with `->` threading.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `rugged`, `noisy`, `tapered`, `loft`, `shape-fn`
