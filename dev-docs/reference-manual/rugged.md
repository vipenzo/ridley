---
name: rugged
category: generative-operations
since: ""
status: stable
---

# rugged

## Signature

`(rugged shape-or-fn & {:keys [amplitude frequency octaves gain seed]})`

## Description

Shape-fn that displaces vertices radially with **layered sinusoids**
(fBm-style), varying both around the profile and along the extrusion
path. Each octave doubles the frequency and scales amplitude by `:gain`,
producing irregular crystalline asperities.

Used with `loft`, `bloft`, or `revolve`. Does not modify turtle state.

Compared with siblings:

- **`fluted`** — single regular sinusoid, axis-aligned, constant along
  the path. Use for fluted columns and clean periodic ridges.
- **`noisy`** — smooth value noise (Perlin-like). Organic, blobby look.
- **`rugged`** — layered sinusoids, angular ridges at multiple scales.
  Rocky, bark, weathered surfaces.

## Parameters

- `shape-or-fn` — a 2D shape, or another shape-fn (composes).
- `:amplitude` — overall radial displacement amplitude (default `1`).
- `:frequency` — base frequency: cycles around the profile (and along
  the path) in the first octave (default `6`).
- `:octaves` — number of sinusoid layers (default `3`). Each octave
  doubles the previous frequency. `1` collapses to a single layer
  (similar to `fluted` but on both axes).
- `:gain` — amplitude multiplier per octave (default `0.5`). Standard
  fBm is `0.5`; higher values (e.g. `0.7`) give a harsher, more chaotic
  surface; lower values (e.g. `0.3`) give a smoother base shape with
  fine detail on top.
- `:seed` — phase offset (default `0`). Changes the pattern without
  changing its statistics.

## Example

{{example: rugged-basic}}

<!-- example-source: rugged-basic -->
```clojure
(register rock
  (loft (rugged (circle 15 256) :amplitude 2 :frequency 6 :octaves 3)
        (f 40)))
```
<!-- /example-source -->

A rocky tube: 6 main ridges around and along, with two octaves of finer
detail layered on top.

## Notes

- The profile and the loft must both be resolved enough to capture the
  highest-frequency octave. With `:frequency F` and `:octaves N`, the
  final octave has frequency `F · 2^(N-1)`. Aim for at least
  `4 × final-frequency` points per ring and as many `loft-n` steps.
- For axis-aligned ridges (no variation along the path) use `fluted`.
- For organic, smooth random surfaces use `noisy`.
- Composes with other shape-fns via `->` threading:
  `(-> (circle 15 256) (rugged :frequency 8) (tapered :to 0.3))`.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `fluted`, `noisy`, `displaced`, `loft`, `shape-fn`
