---
name: shell-custom-fn
category: generative-operations
since: ""
status: stable
---

# shell-custom-fn

## Signature

`(shell shape-or-fn :fn (fn [a t] -> 0..1) & {:keys [threshold softness]})`

## Description

Designing your own wall pattern for `shell`. Part of the
[shell](shell.md) documentation.

Instead of a `:style`, pass `:fn`: a function that, for each point of the
wall, says how much wall there is — `1` for full thickness, `0` for an
opening, anything in between for a thinner wall. It receives two numbers:

- `a` — the point's angle around the profile, in radians, from `-π` to
  `π`. `0` is the profile's +X side.
- `t` — how far along the path the point is, from `0` at the start to `1`
  at the end.

When `:fn` is given, `:style` and its options are ignored.

## Parameters

| Option | Default | Meaning |
|---|---|---|
| `:fn` | — | `(fn [a t] …)` returning the wall amount at that point. Values above 1 count as 1, below 0 as 0. |
| `:threshold` | `0.05` | Values below this become a clean opening, so tiny leftovers do not leave paper-thin skins. |
| `:softness` | `0` | Edge smoothing, off by default for `:fn`. Any value above 0 cuts the openings smoothly instead of along the mesh grid (see [shell-styles](shell-styles.md)). |
| `:invert?` | `false` | Swap wall and openings, as with the styles. |

## Example

{{example: shell-custom-fn}}

<!-- example-source: shell-custom-fn -->
```clojure
(register spiral
  (loft (shell (circle 20 64) :thickness 3
               :fn (fn [a t] (max 0 (sin (+ (* a 8) (* t PI 6))))))
        (f 50)))
```
<!-- /example-source -->

Spiral ribs: the wall follows a sine wave that turns around the tube as
it climbs.

## Variations

{{example: shell-custom-fn-window}}

<!-- example-source: shell-custom-fn-window -->
```clojure
(register window-tube
  (loft (shell (circle 20 64) :thickness 2
               :fn (fn [a t] (if (and (< (abs a) 0.6) (< 0.25 t 0.75)) 0 1)))
        (f 50)))
```
<!-- /example-source -->

A single window: no wall where the angle is near 0 and the point is in
the middle half of the path, full wall everywhere else.

{{example: shell-custom-fn-band}}

<!-- example-source: shell-custom-fn-band -->
```clojure
(register band
  (loft (shell (circle 20 64) :thickness 2 :softness 0.6
               :fn (fn [a t] (if (< 0.3 t 0.7) (max 0 (cos (* 6 a))) 1)))
        (f 50)))
```
<!-- /example-source -->

A perforated band between two solid rings: six openings around the
middle of the tube, with smoothed edges.

## Notes

- The function runs once for every point of every ring, so keep it
  simple and fast.
- Use the angle `a` for patterns *around* the tube, and `t` for patterns
  *along* it; combine both for spirals and diagonals.
- `(* a n)` with a whole number `n` repeats a pattern `n` times around and
  closes seamlessly.

## See also

- **Shell pages:** [shell](shell.md), [shell-styles](shell-styles.md), [shell-caps](shell-caps.md)
- **Related:** `shape-fn`, `displaced`
