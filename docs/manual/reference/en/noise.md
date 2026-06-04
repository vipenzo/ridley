---
name: noise
category: generative-operations
since: ""
status: stable
---

# noise

## Signature

`(noise x y)`

## Description

2D deterministic continuous noise. Returns a value in approximately
`[-1, 1]`. Same inputs always produce the same output, and nearby inputs
produce nearby outputs (Hermite-smoothed for C1 continuity). Does not
modify turtle state.

`noise` is the building block for several procedural shape-fns
(`noisy`, `displaced` with custom callbacks, custom `shape-fn` bodies)
and pairs with `fbm` when more detail is needed.

## Parameters

- `x`, `y` — input coordinates. Any real numbers.

## Example

{{example: noise-basic}}

<!-- example-source: noise-basic -->
```clojure
(noise 0 0)        ; deterministic value
(noise 1.5 2.7)    ; nearby inputs produce nearby outputs
```
<!-- /example-source -->

Sample the noise field at two points. The same call always returns the
same value, useful for seeding reproducible procedural shapes.

## Variations

{{example: noise-in-shape-fn}}

<!-- example-source: noise-in-shape-fn -->
```clojure
(register noisy-tube
  (loft (displaced (circle 15 96)
                   (fn [p t] (* 1.5 (noise (* (angle p) 4) (* t 6)))))
        (f 40)))
```
<!-- /example-source -->

Drop `noise` into a custom `displaced` callback. Multiply the input
coordinates to control the frequency.

## Notes

- The implementation is a value-noise hash, not Perlin: it's cheap and
  deterministic. For fractal detail, use `fbm`.
- Use a different additive offset on `x` or `y` to "seed" different
  variations of the same pattern.

## See also

- **Related:** `fbm`, `noisy`, `displaced`
