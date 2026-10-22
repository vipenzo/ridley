---
name: tapered
category: generative-operations
since: ""
status: stable
---

# tapered

## Signature

`(tapered shape-or-fn & {:keys [from to curve]})`
`(tapered & {:keys [from to curve]})` — partial form (no profile)

## Description

Shape-fn that scales the cross-section uniformly along the path. At `t = 0`
the profile is scaled by `:from`; at `t = 1` it is scaled by `:to`; in
between the factor follows `:curve` — a straight line unless you choose
otherwise. Used with `loft` or `revolve`. Does not modify turtle state.

The most common case is a cone (`:to 0`): the profile shrinks to zero
over the length of the path. `:from` defaults to `1`, so a single `:to`
argument tapers from full size to the chosen factor.

`:curve` decides *how* the scale travels from `:from` to `:to`. The scale
at `t` is `from + curve(t) × (to − from)`: the curve says what fraction of
the way has been covered — `0` means `:from`, `1` means `:to`.

| `:curve` | shape | `curve(t)` |
|---|---|---|
| `:linear` (default) | straight line | `t` |
| `:ease-in` | starts slow, speeds up | `1 − cos(πt/2)` |
| `:ease-out` | starts fast, slows down | `sin(πt/2)` |
| `:ease-in-out` | gentle at both ends | `(1 − cos πt) / 2` |
| `:bump` | there and back: `:to` at mid-path, `:from` at both ends | `sin(πt)` |
| `(fn [t] …)` | anything | your function |

## Parameters

- `shape-or-fn` — a 2D shape, or another shape-fn (composes).
- `:from` — scale factor at `t = 0` (default `1`).
- `:to` — scale factor at `t = 1` (default `0`); for `:bump`, the factor
  at mid-path.
- `:curve` — how the scale travels from `:from` to `:to` (default
  `:linear`). One of `:linear`, `:ease-in`, `:ease-out`, `:ease-in-out`,
  `:bump`, or a function `(fn [t] …)` returning the fraction of the way
  covered at `t`. The function's value may go below `0` or above `1`: the
  scale then goes past `:from` or `:to`, it is not clamped.

## Example

{{example: tapered-cone}}

<!-- example-source: tapered-cone -->
```clojure
(register cone (loft (tapered (circle 20) :to 0) (f 40)))
```
<!-- /example-source -->

A cone is the canonical tapered loft: a circle shrunk to zero along a
straight path.

## Variations

{{example: tapered-expand}}

<!-- example-source: tapered-expand -->
```clojure
(register horn (loft (tapered (circle 5) :from 1 :to 4) (f 30)))
```
<!-- /example-source -->

Expanding profile: scale factor goes from 1 at the base to 4 at the tip,
producing a flared horn.

### Named curves

{{example: tapered-ease-out}}

<!-- example-source: tapered-ease-out -->
```clojure
(register flare (loft (tapered (circle 20) :from 1 :to 2 :curve :ease-out) (f 40)))
```
<!-- /example-source -->

An eased flare: the profile widens quickly at first and levels off towards
the top, reaching double size with no kink at the end.

{{example: tapered-bump}}

<!-- example-source: tapered-bump -->
```clojure
(register waist (loft (tapered (circle 20) :to 0.35 :curve :bump) (f 40)))
```
<!-- /example-source -->

There and back: the profile narrows to 0.35 at mid-path and returns to
full size at the end. With `:to` above 1 the same curve bulges instead.

### Your own curve

Pass a function of `t` instead of a keyword. It returns how far along the
way from `:from` to `:to` the profile is at that point — the named curves
are just ready-made functions (`:ease-out` is `(fn [t] (sin (* t PI 0.5)))`).

{{example: tapered-curve-asymmetric-bump}}

<!-- example-source: tapered-curve-asymmetric-bump -->
```clojure
(register bulb
  (loft (tapered (circle 20) :to 1.6 :curve (fn [t] (sin (* PI (sqrt t)))))
    (f 40)))
```
<!-- /example-source -->

Asymmetric bump: rises fast and falls slowly, peaking a quarter of the way
along instead of at mid-path.

{{example: tapered-curve-trumpet}}

<!-- example-source: tapered-curve-trumpet -->
```clojure
(register trumpet (loft (tapered (circle 10) :to 4 :curve (fn [t] (pow t 4))) (f 40)))
```
<!-- /example-source -->

Trumpet: almost straight for most of its length, then opening sharply at
the end.

{{example: tapered-curve-neck}}

<!-- example-source: tapered-curve-neck -->
```clojure
(register neck (loft (tapered (circle 20) :to 0.5 :curve (fn [t] (min 1 (* 3 t)))) (f 40)))
```
<!-- /example-source -->

Narrow, then straight: the profile shrinks over the first third and stays
constant after that, like a bottle neck.

{{example: tapered-curve-two-bumps}}

<!-- example-source: tapered-curve-two-bumps -->
```clojure
(register beads (loft (tapered (circle 20) :to 1.3 :curve (fn [t] (abs (sin (* 2 PI t))))) (f 40)))
```
<!-- /example-source -->

Two bumps: back to full size at mid-path, then up again. Change the `2` to
get any number of bumps — `(abs (sin (* n PI t)))` gives `n`.

{{example: tapered-curve-ripples}}

<!-- example-source: tapered-curve-ripples -->
```clojure
(register ripples
  (loft-n 96 (tapered (circle 20) :to 0.2 :curve (fn [t] (+ t (* 0.05 (sin (* 12 PI t))))))
    (f 40)))
```
<!-- /example-source -->

A cone with ripples: the ordinary linear taper with small waves on top. The
curve leaves the 0–1 range here and there, which is allowed. The ripples
change quickly along the path, so `loft-n` raises the ring count from the
default 16 — with too few rings the waves would be skipped.

## Notes

- Shape-fns compose with `->` threading:
  `(-> (circle 15) (fluted :flutes 12) (tapered :to 0.5))` adds flutes
  first, then tapers the result.
- A taper that changes quickly along the path (`:bump` with a big
  difference, the ripples above) needs more rings than the default 16 to
  show its shape: use `loft-n`.
- For non-uniform tapering (e.g. only along Y), use a custom `shape-fn`
  with `scale-shape s sx sy` inside.
- **Partial form:** called without a profile, `(tapered :to 0.5)` returns the
  bare transform `(fn [shape t] -> shape)` — not a shape-fn — for loft's legacy
  mode and `transform->`: `(loft+ (tapered :to 1.3) (f 30))`. It equals the
  full form: `(loft (circle 20) (tapered :to 0.5) …)` == `(loft (tapered (circle 20) :to 0.5) …)`.
  `:curve` works in partial form too: `(loft+ (tapered :to 0.35 :curve :bump) (f 30))`.

## See also

- **Related:** `loft`, `revolve`, `shape-fn`, `profile`, `capped`
