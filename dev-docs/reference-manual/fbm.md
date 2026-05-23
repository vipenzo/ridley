---
name: fbm
category: generative-operations
since: ""
status: stable
---

# fbm

## Signature

`(fbm x y)`
`(fbm x y octaves)`
`(fbm x y octaves lacunarity gain)`

## Description

Fractal Brownian Motion — layered noise built by summing `noise` at
multiple frequencies with decaying amplitude. Produces natural-looking
surfaces with detail at multiple scales. Does not modify turtle state.

The output is the normalised sum of `octaves` noise layers, each at a
higher frequency (multiplied by `lacunarity`) and lower amplitude
(multiplied by `gain`) than the previous one.

## Parameters

- `x`, `y` — input coordinates.
- `octaves` — number of noise layers (default `4`). More layers = more
  detail.
- `lacunarity` — frequency multiplier per octave (default `2.0`).
- `gain` — amplitude multiplier per octave (default `0.5`).

## Example

{{example: fbm-basic}}

<!-- example-source: fbm-basic -->
```clojure
(fbm 0.3 0.7)            ; 4-octave fbm with default lacunarity/gain
(fbm 0.3 0.7 6)          ; 6 octaves for more detail
(fbm 0.3 0.7 4 2.5 0.4)  ; tweaked roughness
```
<!-- /example-source -->

Sample the fBm field at one point with different parameters. More
octaves and higher gain produce rougher output.

## Variations

{{example: fbm-in-shape-fn}}

<!-- example-source: fbm-in-shape-fn -->
```clojure
(register rocky
  (loft (displaced (circle 15 96)
                   (fn [p t] (* 2 (fbm (* (angle p) 4) (* t 6) 4))))
        (f 50)))
```
<!-- /example-source -->

Use `fbm` inside a custom `displaced` callback for rocky / organic
surfaces. The built-in `noisy` shape-fn with `:octaves > 1` does the same
thing internally.

## Notes

- The output is normalised by the cumulative amplitude so it stays close
  to `[-1, 1]` regardless of `octaves`.
- Pair with a multiplicative `amplitude` outside the call to control the
  absolute displacement.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `noise`, `noisy`, `displaced`
