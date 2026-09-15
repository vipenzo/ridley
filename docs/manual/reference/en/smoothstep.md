---
name: smoothstep
category: math
since: ""
status: stable
---

# smoothstep

## Signature

`(smoothstep e0 e1 x)`

## Description

The Hermite ramp: `0` for `x` at or below `e0`, `1` for `x` at or above
`e1`, and a smooth (C1) cubic in between — `t² (3 - 2t)` with
`t = (x - e0) / (e1 - e0)` clamped to `[0, 1]`. The same function GLSL
calls `smoothstep`.

It is the edge every built-in `:softness` draws: `shell`'s and
`embroid`'s styles turn a hard wall/opening boundary into a short ramp
with it, so the isocontour cut lands on a continuous field. A custom
thickness-fn or displacement can use it for the same purpose.

If `e1 <= e0` the ramp degenerates to a hard step at `e0`.

## Parameters

- `e0` — where the ramp starts (output 0).
- `e1` — where the ramp ends (output 1).
- `x` — the input.

## Example

{{example: smoothstep-basic}}

<!-- example-source: smoothstep-basic -->
```clojure
;; Six soft-edged windows around a shell: the field is a cosine, the
;; ramp turns its zero crossing into a 0.3-wide bevel
(register lantern
  (loft-n 64
    (shell (circle 20 96) :thickness 2 :softness 0.5
           :fn (fn [a t]
                 (let [v (cos (* 6 a))]
                   (smoothstep -0.15 0.15 (- v 0.3)))))
    (f 50)))
```
<!-- /example-source -->

## Notes

- A `:fn` thickness function gets the isocontour cut only when you pass
  `:softness` > 0 — the ramp alone does not switch it on.
- For a symmetric bump use `(smoothstep e0 e1 (- w (abs (- x c))))`
  around a centre `c` of half-width `w`.

## See also

- **Related:** `shell`, `embroid`, `shape-fn`, `displaced`
