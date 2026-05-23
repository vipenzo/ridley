---
name: sdf-displace
category: sdf-modeling
since: ""
status: stable
---

# sdf-displace

## Signature

`(sdf-displace node formula)`

## Description

Displace an SDF's surface by adding a spatial formula's value to the
distance field at every point. Returns a new SDF tree.

The formula is a **quoted** Clojure expression using `x`, `y`, `z`
(Cartesian coordinates) — and optionally `r`, `rho`, `theta`, `phi`
(synthetic spherical/cylindrical variables; see `sdf-formula` for the
full list). Positive values push the surface inward, negative values
push it outward.

`sdf-displace` is the canonical way to add bumps, ridges, fluting, or
noise to any SDF without modifying its core shape. Works on any input —
primitives, blends, booleans, even other displacements.

> Desktop only: requires the libfive backend.

## Parameters

- `node` — the SDF to displace.
- `formula` — a quoted Clojure expression. Available variables and
  operators are documented in `sdf-formula`.

## Example

{{example: sdf-displace-wavy-sphere}}

<!-- example-source: sdf-displace-wavy-sphere -->
```clojure
;; A sphere with a sinusoidal surface
(register wavy
  (sdf-displace (sdf-sphere 10)
                '(* 1.5 (sin (* x 2)) (sin (* y 2)))))
```
<!-- /example-source -->

The displacement amplitude is `1.5` units, modulated by the product of
two sines — a soft "egg-carton" texture wrapped onto the sphere.

## Variations

{{example: sdf-displace-spherical-vars}}

<!-- example-source: sdf-displace-spherical-vars -->
```clojure
;; Use the spherical variables theta and phi for surface-following bumps
(register bumpy
  (sdf-displace (sdf-sphere 10)
                '(* 1.5 (sin (* theta 6)) (sin (* phi 6)))))
```
<!-- /example-source -->

`theta` (azimuth) and `phi` (polar angle) follow the sphere's
curvature, so the bumps wrap around it cleanly without the distortion
you would get with raw `x` / `y`.

{{example: sdf-displace-of-blend}}

<!-- example-source: sdf-displace-of-blend -->
```clojure
;; Displace any SDF — booleans, blends, etc. all accept it
(register organic
  (sdf-displace
    (sdf-blend (sdf-sphere 10) (sdf-box 14 14 14) 2)
    '(* 0.5 (sin (* x 3)) (cos (* z 3)))))
```
<!-- /example-source -->

`sdf-displace` is uniform across the SDF tree: it sees the input as a
distance field and modulates it, regardless of how that field was
built.

## Notes

- The formula is quoted (a list, not evaluated Clojure). Build it
  inline with `'(…)` or via functions that return lists — see
  `sdf-formula` for composition.
- Beware the **`pow` with negative bases** gotcha: libfive computes
  `pow(a, b)` as `exp(b * log(a))`, which is NaN for `a < 0`. For
  squaring use `(* expr expr)` instead of `(pow expr 2)`. NaN
  propagates silently and produces broken meshes.
- Amplitude is unbounded — the surface can self-intersect if the
  displacement exceeds the local feature size. Keep amplitudes small
  relative to the source SDF.

## See also

- **Formula reference:** `sdf-formula`
- **Related:** `sdf-blend`, `sdf-morph`, `sdf-offset`
