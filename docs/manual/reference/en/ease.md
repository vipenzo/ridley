---
name: ease
category: live-interactive
since: ""
status: stable
---

# ease

## Signature

`(ease type t)`

## Description

Apply an easing function to a normalized fraction `t ∈ [0, 1]` and return the eased value, also in `[0, 1]`. The standalone form of the easings used by `span` inside `anim!`. Useful for previewing a curve numerically, for custom interpolations inside `anim-proc!` `gen-fn`s, or for tweak-style math that needs the same shaping the timeline applies.

Supported easing types are:

| Type            | Curve                                                              |
|-----------------|--------------------------------------------------------------------|
| `:linear`       | Identity. `t` passes through unchanged.                            |
| `:in`           | Quadratic ease-in. `t²` — slow start, fast end.                    |
| `:out`          | Quadratic ease-out. Mirror of `:in` — fast start, slow end.        |
| `:in-out`       | Quadratic ease-in-out. Symmetric S-curve.                          |
| `:in-cubic`     | Cubic ease-in. `t³` — sharper start than `:in`.                    |
| `:out-cubic`    | Cubic ease-out. Mirror of `:in-cubic`.                             |
| `:in-out-cubic` | Cubic ease-in-out. Sharper S-curve than `:in-out`.                 |
| `:spring`       | Damped oscillation. Overshoots, then settles at `1`.               |
| `:bounce`       | Multi-stage bounce. Lands at `1` after several decaying bounces.   |

Out-of-range inputs are clamped to `[0, 1]`. An unknown type falls back to `:linear`.

## Parameters

- `type` — easing keyword (see table above).
- `t` — fraction in `[0, 1]`. Clamped if out of range.

## Example

{{example: ease-preview}}

<!-- example-source: ease-preview -->
```clojure
;; Sanity-check a curve numerically before using it in an animation
(ease :in-out 0.0)    ;; => 0.0
(ease :in-out 0.25)   ;; => 0.125
(ease :in-out 0.5)    ;; => 0.5
(ease :in-out 0.75)   ;; => 0.875
(ease :in-out 1.0)    ;; => 1.0
```
<!-- /example-source -->

`:in-out` is a symmetric S-curve: it passes through `(0.5, 0.5)` and squashes the extremes. Run the same probe with `:spring` or `:bounce` to see why their endpoints behave differently.

## Variations

{{example: ease-custom-anim-proc}}

<!-- example-source: ease-custom-anim-proc -->
```clojure
(register blob (sphere 1))

;; Use ease inside anim-proc! to apply a non-default curve to a sub-component
;; of the generated mesh, independent of the animation's own easing.
(anim-proc! :grow 3.0 :blob :linear
  (fn [t]
    (let [size   (+ 1 (* 19 t))
          wobble (* 0.5 (ease :spring t))]
      (sphere (+ size wobble)))))
```
<!-- /example-source -->

The animation itself uses `:linear` pacing, but the wobble inside `gen-fn` rides a `:spring` curve. `ease` lets you mix multiple shaping curves in a single generator.

## Notes

- **Standalone use is the main reason `ease` exists.** Inside `span`, you pass the keyword directly; you only need `ease` when you want the eased value in your own code.
- **`:spring` and `:bounce` overshoot** during the curve but always return exactly `0` at `t = 0` and `1` at `t = 1`.
- **Unknown type → linear.** Typos in the keyword silently degrade to identity; double-check against the table above when a curve does not look right.

## See also

- **Related:** `span`, `anim!`
