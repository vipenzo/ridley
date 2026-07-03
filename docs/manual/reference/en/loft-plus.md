---
name: loft+
category: generative-operations
since: ""
status: stable
---

# loft+

## Signature

`(loft+ shape-fn & path-commands)`
`(loft+ shape transform-fn & path-commands)`
`(loft+ shape target-shape & path-commands)`

## Description

Chainable variant of `loft`. Same dispatch as `loft` (a shape-fn, a
legacy transform-fn, or a two-shape morph), but instead of a bare mesh it
returns a map `{:mesh <mesh> :start-face {…} :end-face {:shape <shape> :pose {…}}}`.

The `:end-face` `:shape` is the 2D cross-section **actually stamped on the
loft's last ring** — never a re-evaluation of the shape-fn at nominal
`t=1`. On paths with a corner and short segments the last ring's `t` can
clamp below 1, so re-deriving at `t=1` would mismatch the real end face
and crack the seam of the next segment. Threading the stamped section out
avoids that. Does not modify turtle state.

For sequencing multiple chainable calls without manual unpacking, see the
`transform->` macro.

## Parameters

- `shape-fn` — a shape-fn profile that varies along the path (`tapered`,
  `twisted`, …). 2-arg form.
- `shape` + `transform-fn` — a plain profile and a `(fn [s t] …)` transform.
- `shape` + `target-shape` — morph from `shape` to `target-shape`; the end
  section carries the resampled point count.
- `path-commands` — turtle movement forms or a recorded path.

Not supported: `shell` / `embroid` profiles — a perforated swept wall has
no single end cross-section to continue from, so `loft+` rejects them with
an explanatory error.

## Example

{{example: loft-plus-basic}}

<!-- example-source: loft-plus-basic -->
```clojure
(def taper (loft+ (tapered (circle 20) :to 0.5) (f 30)))
(:mesh taper)        ;; the tapered mesh
(:end-face taper)    ;; {:shape <end section> :pose {:position … :heading … :up …}}
```
<!-- /example-source -->

The end-face's `:shape` is the real last-ring section, so a follow-up op
starts flush against it.

## Variations

{{example: loft-plus-transform}}

<!-- example-source: loft-plus-transform -->
```clojure
(register spout
  (transform-> (circle 20)
    (loft+ (tapered :to 0.6) (f 30))   ;; tapered run (partial form — reads clean)
    (revolve+ 45 :pivot :left)         ;; corner bend
    (extrude+ (f 20))))                ;; straight tail
```
<!-- /example-source -->

Inside `transform->` the incoming shape becomes the loft's profile
automatically, so the step is written with just the transform and the
movements. The transform can be a **partial combinator** like
`(tapered :to 0.6)` (the readable choice), a `(fn [shape t] …)`, or a target
shape.

## Notes

- The map shape is `{:mesh :start-face :end-face}`; each face is a map
  `{:shape :pose}` whose pose is `{:pos :heading :up}`.
- The end-face `:up` is derived from the end heading (like `extrude+`); a
  twisting rail's realized roll is not threaded (a v1 limit shared with
  the extrude/revolve chain).
- Standard `loft` is unchanged: it still returns just a mesh.
- Pair with `extrude+` / `revolve+` and `transform->` for compact chains.

## See also

- **Related:** `extrude+`, `revolve+`, `transform->`, `loft`, `mesh-union`
