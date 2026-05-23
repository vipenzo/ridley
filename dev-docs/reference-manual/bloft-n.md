---
name: bloft-n
category: generative-operations
since: ""
status: stable
---

# bloft-n

## Signature

`(bloft-n n shape-fn & path-commands)`
`(bloft-n n shape transform-fn & path-commands)`
`(bloft-n n start-shape end-shape & path-commands)`

## Description

`bloft` with an explicit step count. Same semantics as `bloft` — see the
parent page for the convex-hull intersection handling and full
behavioural details. The walk along the path uses `n` rings instead of
the default 16. Does not modify turtle state.

Increase `n` for tighter bezier curves where ring spacing must be small
enough to keep neighbouring sections compatible.

## Parameters

- `n` — explicit number of steps along the path.
- All other parameters: see `bloft`.

## Example

{{example: bloft-n-basic}}

<!-- example-source: bloft-n-basic -->
```clojure
(def curve (path (bezier-to [60 0 0] :tangent-in [25 40 0])))
(register smooth-pipe (bloft-n 64 (circle 4) curve))
```
<!-- /example-source -->

A bezier-safe loft with 64 rings — fine spacing for tight bends.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `bloft`, `loft-n`, `bezier-as`
