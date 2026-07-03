---
name: noisy
category: generative-operations
since: ""
status: stable
---

# noisy

## Signature

`(noisy shape-or-fn & {:keys [amplitude scale scale-x scale-y octaves seed]})`
`(noisy & {:keys [amplitude scale scale-x scale-y octaves seed]})` — partial form (no profile)

## Description

Shape-fn that displaces vertices radially using procedural 2D noise.
Compared with the sinusoid-based displacements, `noisy` produces smooth,
organic, blobby surfaces (`fluted` gives single-frequency regular ridges;
`rugged` gives angular crystalline asperities via layered sines). With
`:octaves > 1` the noise is layered via fractal Brownian motion for
richer detail. Used with `loft` or `revolve`. Does not modify turtle
state.

The deterministic noise is built on `noise` / `fbm`, so the same seed
always produces the same surface.

## Parameters

- `shape-or-fn` — a 2D shape, or another shape-fn (composes).
- `:amplitude` — radial displacement amplitude (default `1.0`).
- `:scale` — noise frequency along both directions (default `3.0`).
- `:scale-x` — explicit angular frequency. Overrides `:scale` for X.
- `:scale-y` — explicit longitudinal frequency. Overrides `:scale` for Y.
- `:octaves` — number of noise layers; `1` = single octave, higher = fBm
  (default `1`).
- `:seed` — offset into the noise field for reproducible variations
  (default `0`).

## Example

{{example: noisy-basic}}

<!-- example-source: noisy-basic -->
```clojure
(register pebble (loft (noisy (circle 20 96) :amplitude 1.5 :scale 4) (f 25)))
```
<!-- /example-source -->

A pebble-like organic surface: 1.5-unit noise displacements on a 96-point
circle, with moderate frequency.

## Variations

{{example: noisy-fbm}}

<!-- example-source: noisy-fbm -->
```clojure
(register rocky (loft (noisy (circle 18 128) :amplitude 2 :scale 3 :octaves 4) (f 30)))
```
<!-- /example-source -->

With `:octaves 4` the displacement is the sum of four noise layers at
increasing frequency — rougher, more rocky-looking surface.

{{example: noisy-asymmetric}}

<!-- example-source: noisy-asymmetric -->
```clojure
(register bark (loft (noisy (circle 15 96) :amplitude 1.2 :scale-x 8 :scale-y 3) (f 40)))
```
<!-- /example-source -->

`:scale-x` and `:scale-y` decouple angular and longitudinal frequencies
— high X with low Y looks like vertical bark.

## Notes

- Same `:seed` always produces the same noise field.
- Shape-fns compose with `->` threading.
- For pure radial sine ripples, use `rugged`. For axis-aligned grooves,
  use `fluted`. For full custom displacement, use `displaced`.
- **Partial form:** `(noisy :amplitude 1.5 :scale 3)` (no profile) returns the
  bare transform for loft's legacy mode and `transform->`.

## See also

- **Related:** `displaced`, `rugged`, `fluted`, `noise`, `fbm`
