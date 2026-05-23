---
name: bloft
category: generative-operations
since: ""
status: stable
---

# bloft

## Signature

`(bloft shape-fn & path-commands)`
`(bloft shape transform-fn & path-commands)`
`(bloft start-shape end-shape & path-commands)`
`(bloft-n n shape-fn & path-commands)`

## Description

Bezier-safe loft. For paths with tight curves (typically those processed
with `bezier-as`), regular `loft` can produce self-intersecting geometry
because consecutive rings overlap. `bloft` detects ring intersections and
bridges them with convex hulls, then unions all pieces into a manifold
mesh.

Same modes as `loft` (shape-fn, transform-fn, two-shape); same default
step count (16). Use `bloft-n` to override. Does not modify turtle state.

## Parameters

- Same as `loft` — see that page for shape-fn, transform-fn, and two-shape
  mode parameters.
- `n` (for `bloft-n`) — explicit number of steps along the path.

## Example

{{example: bloft-basic}}

<!-- example-source: bloft-basic -->
```clojure
(def curve (path (bezier-to [40 0 0] :tangent-in [20 30 0])))
(register pipe (bloft (circle 4) curve))
```
<!-- /example-source -->

A pipe along a tight bezier curve. `loft` would likely produce
self-intersections on the inside of the bend; `bloft` bridges them with
convex hulls so the result remains manifold.

## Variations

{{example: bloft-tapered}}

<!-- example-source: bloft-tapered -->
```clojure
(def curve (path (bezier-to [50 0 0] :tangent-in [25 30 0])))
(register tapered-pipe (bloft (tapered (circle 8) :to 0.5) curve))
```
<!-- /example-source -->

Shape-fns compose with `bloft` exactly as they do with `loft`: a
`tapered` cross-section is handled correctly even when consecutive rings
would otherwise overlap.

{{example: bloft-high-resolution}}

<!-- example-source: bloft-high-resolution -->
```clojure
(register smooth-pipe (bloft-n 64 (circle 4) curve))
```
<!-- /example-source -->

For very tight curves where ring spacing matters, increase the step count
with `bloft-n`.

## Notes

- `bloft` is **slower** than `loft`: each ring pair is intersection-tested
  and, when needed, replaced with a convex hull union step. Prefer `loft`
  when the path is straight or gently curved.
- The bezier sampling density is controlled by `(resolution :n …)`:
  low values produce fast draft previews with visual artifacts; high
  values produce smooth final renders at the cost of speed.
- For self-intersection between non-adjacent rings (long loops folding
  back on themselves), neither `loft` nor `bloft` produces a clean
  result — split the path into segments.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `loft`, `bloft-n`, `extrude`, `bezier-as`, `path`
