---
name: loft-n
category: generative-operations
since: ""
status: stable
---

# loft-n

## Signature

`(loft-n n shape-fn & path-commands)`
`(loft-n n shape transform-fn & path-commands)`
`(loft-n n start-shape end-shape & path-commands)`

## Description

`loft` with an explicit step count. Same semantics as `loft` — see the
parent page for the three modes (shape-fn, transform-fn, two-shape) and
all behavioural details. The only difference is that the walk along the
path uses `n` rings instead of the default 16.

Increase `n` when the profile changes rapidly along the path (a steep
taper, a high-frequency rugged profile) and 16 rings produce visible
facets. Does not modify turtle state.

## Parameters

- `n` — explicit number of steps along the path. The first ring is at
  `t = 0`, the last at `t = 1`.
- All other parameters: see `loft`.

## Example

{{example: loft-n-basic}}

<!-- example-source: loft-n-basic -->
```clojure
(register smooth-cone (loft-n 32 (tapered (circle 20) :to 0) (f 30)))
```
<!-- /example-source -->

A cone with 32 rings instead of the default 16, for a smoother taper.

## See also

- **Related:** `loft`, `extrude`
