---
name: shape-fn
category: generative-operations
since: ""
status: stable
---

# shape-fn

## Signature

`(shape-fn base transform)`

## Description

Constructor for custom shape-fns. Returns a function `(fn [t] -> shape)`
tagged with metadata `{:type :shape-fn}` so `loft`, `bloft`, and `revolve`
recognise it as a varying profile. `base` may be a plain shape or another
shape-fn (in which case the new transform is composed on top of the
existing one). Does not modify turtle state.

A shape-fn is the protocol used by built-in profile transformations
(`tapered`, `twisted`, `noisy`, …). Reach for `shape-fn` when none of
those fits — for instance when the transformation depends on external
state, samples a texture, or mixes per-ring logic that is too specific to
package as a reusable shape-fn.

## Parameters

- `base` — a plain shape or another shape-fn. When a shape-fn, the new
  transform is applied on top of the base evaluation.
- `transform` — function `(fn [shape t] -> shape)` where `t ∈ [0, 1]`.
  Called once per ring during loft/bloft/revolve.

## Example

{{example: shape-fn-basic}}

<!-- example-source: shape-fn-basic -->
```clojure
(def pulsing
  (shape-fn (circle 20)
            (fn [s t]
              (scale-shape s (+ 0.6 (* 0.4 (Math/sin (* t Math/PI))))))))

(register pulse (loft pulsing (f 40)))
```
<!-- /example-source -->

A custom shape-fn that scales the cross-section with a sine — wide at the
ends, narrow in the middle.

## Variations

{{example: shape-fn-composed}}

<!-- example-source: shape-fn-composed -->
```clojure
(def wobble
  (shape-fn (tapered (circle 20) :to 0.4)
            (fn [s t]
              (rotate-shape s (* 30 (Math/sin (* t 4 Math/PI)))))))

(register wobbly-cone (loft wobble (f 40)))
```
<!-- /example-source -->

Layering custom transforms on top of a built-in shape-fn. The first
argument can be either a plain shape or another shape-fn — `shape-fn`
composes them.

## Notes

- Shape-fns compose with `->` threading:
  `(-> (circle 15) (fluted :flutes 8) (tapered :to 0.5))`.
- During a loft, the dynamic var `*path-length*` is bound to the absolute
  path length — useful when the transform depends on physical distance
  rather than the normalised `t`.
- Use `shape-fn?` to check whether a value is a shape-fn at runtime.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `shape-fn?`, `loft`, `bloft`, `revolve`, `displaced`
