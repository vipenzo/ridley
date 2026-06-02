---
name: revolve+
category: generative-operations
since: ""
status: stable
---

# revolve+

## Signature

`(revolve+ shape)`
`(revolve+ shape angle & opts)`

## Description

Chainable variant of `revolve`. Like `extrude+`, returns
`{:mesh <mesh> :end-face {:shape <shape> :pose {…}}}` instead of a bare
mesh. The `:end-face` carries the shape and pose at the end of the
revolution, ready to be passed to a follow-up `extrude+` or `revolve+`
segment. Does not modify turtle state.

Typically used with `:pivot` to model corner bends: a partial revolution
that connects two straight segments at an angle.

## Parameters

- `shape` — a 2D profile.
- `angle` — revolution angle in degrees. Optional; defaults to 360.
- `opts` — additional keyword arguments forwarded to `revolve`, notably
  `:pivot` (`:left`, `:right`, `:up`, `:down`).

## Example

{{example: revolve-plus-bend}}

<!-- example-source: revolve-plus-bend -->
```clojure
(def seg1 (extrude+ (circle 5) (f 20)))
(def bend (turtle (:pose (:end-face seg1))
            (revolve+ (:shape (:end-face seg1)) 30 :pivot :left)))
(register elbow (mesh-union (:mesh seg1) (:mesh bend)))
```
<!-- /example-source -->

A 30° elbow built by chaining a straight extrude with a pivoted partial
revolve. The pose carried by `:end-face` aligns the bend with the
straight segment.

## Notes

- Returns `{:mesh :end-face}` with the same map shape as `extrude+`.
- `:pivot` is the recommended way to chain corner bends: it keeps any
  holes intact by avoiding axis clipping.
- For long chains, `transform->` automates the pose-passing pattern.

## See also

- **Guide:** placeholder → cap. 4 (Estrusione)
- **Related:** `revolve`, `extrude+`, `transform->`, `mesh-union`
