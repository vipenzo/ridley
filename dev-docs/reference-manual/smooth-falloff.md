---
name: smooth-falloff
category: spatial-deformation
since: ""
status: stable
---

# smooth-falloff

## Signature

`(smooth-falloff dist)`

## Description

Return the Hermite smooth-step `3t² − 2t³` evaluated at `t = 1 − dist`,
clamped so `dist` ranges over `[0, 1]`. At the volume centre
(`dist = 0`) the result is `1`; at the boundary (`dist = 1`) the result
is `0`; the transition is smooth (zero derivative at both endpoints).

This is the falloff curve used internally by every preset deform-fn
(`inflate`, `dent`, `attract`, `twist`, `squash`, `roughen`). It is
exported as a standalone function so custom deform-fns can match the
preset feel.

## Parameters

- `dist` — normalized distance from the volume centre. `0` at the
  centre, `1` at the boundary, clamped if out of range.

## Example

{{example: smooth-falloff-custom-deform}}

<!-- example-source: smooth-falloff-custom-deform -->
```clojure
;; Custom deform-fn: pull vertices toward a fixed world point, with
;; the preset smooth falloff so it matches the look of `attract`.
(register m
  (warp (box 30) (sphere 18)
        (fn [pos _lp dist _n _vol]
          (let [w (smooth-falloff dist)
                target [0 0 0]]
            (mapv (fn [a b] (+ a (* w 0.5 (- b a)))) pos target)))))
```
<!-- /example-source -->

The custom deform-fn pulls every vertex 50% of the way toward the
origin, weighted by `smooth-falloff` so the effect tapers to zero at
the sphere boundary — same falloff shape as the built-in `attract`
preset, but with an explicit world target rather than the volume
centre.

## Notes

- `smooth-falloff` is monotonically non-increasing in `dist`. Combine
  with custom weights by multiplication, not subtraction.
- The curve is C¹-continuous at the endpoints; that smoothness is what
  prevents visible seams at the volume boundary.

## See also

- **Mother card:** `warp`
- **Preset deform-fns that use it:** `inflate`, `dent`, `attract`,
  `twist`, `squash`, `roughen`
