---
name: sdf-formula
category: sdf-modeling
since: ""
status: stable
---

# sdf-formula

## Signature

`(sdf-formula form)`

## Description

Compile a quoted Clojure math expression into an SDF tree node. The
expression's value at each point in space defines the **distance
field** of the resulting implicit surface: where the formula evaluates
to zero is the surface; negative values are interior; positive values
are exterior.

`sdf-formula` is the lowest-level entry into SDF construction. Use it
when no built-in primitive matches the shape you want, or when you
want to combine algebraic surfaces (`x² + y² + z² − r² = 0` for a
sphere, more elaborate expressions for ripples, helicoids, gyroid-like
surfaces, etc.).

Because `sdf-formula` is a regular function (not a macro), expressions
are composable: build them from helper functions, store them in vars,
splice them with `list` / `concat`, etc.

> Desktop only: requires the libfive backend.

## Parameters

- `form` — a Clojure list/value describing the formula. Quote it
  inline (`'(…)`) or return it from a helper function. The expression
  uses the variables and operators listed below.

**Available operations:**

- Arithmetic: `+`, `-`, `*`, `/`.
- Trig: `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`.
- Math: `sqrt`, `abs`, `exp`, `log`, `pow`, `mod`, `square`, `neg`.
- Comparison: `min`, `max`.

**Coordinate variables:**

| Variable | Meaning |
|----------|---------|
| `x`, `y`, `z` | Cartesian coordinates |
| `r`           | Distance from origin, `sqrt(x² + y² + z²)` |
| `rho`         | Cylindrical radius, `sqrt(x² + y²)` |
| `theta`       | Azimuthal angle around Z, `atan2(y, x)` |
| `phi`         | Polar angle from Z, `atan2(sqrt(x²+y²), z)` |

The spherical/cylindrical variables are synthetic — they expand to
sub-trees of `x`, `y`, `z` at compile time.

## Example

{{example: sdf-formula-wave-surface}}

<!-- example-source: sdf-formula-wave-surface -->
```clojure
;; A wave surface, bounded by a box
(register wave
  (sdf-intersection
    (sdf-formula '(- z (* 2 (sin (* x 0.5)) (cos (* y 0.5)))))
    (sdf-box 30 30 10)))
```
<!-- /example-source -->

The formula `z − 2·sin(x/2)·cos(y/2)` is the SDF of a wavy plane;
intersecting with a box gives a finite, printable solid.

## Variations

{{example: sdf-formula-composed}}

<!-- example-source: sdf-formula-composed -->
```clojure
;; Build the formula with a helper — it's just data manipulation
(defn mk-wave [freq amp]
  (list '- 'z (list '* amp (list 'sin (list '* 'x freq))
                              (list 'cos (list '* 'y freq)))))

(register wave2
  (sdf-intersection
    (sdf-formula (mk-wave 0.3 4))
    (sdf-box 40 40 10)))
```
<!-- /example-source -->

Since `sdf-formula` accepts a runtime value, the formula can be built
by ordinary function calls. Convenient for parameterised surfaces.

## Notes

- **`pow` with negative bases gotcha.** libfive computes `(pow a b)`
  as `(exp (* b (log a)))`, which returns NaN for `a < 0`. For
  squaring an expression that may be negative (e.g. `(- (mod x p) hp)`),
  use `(* expr expr)` instead of `(pow expr 2)`. NaN propagates
  silently and produces hollow or broken meshes — symptom: parts of
  the surface disappear without an error.
- Formulas produce **infinite** implicit surfaces. Bound them with
  `sdf-intersection` against a finite shape before meshing.
- For surface displacement of an existing SDF (rather than building
  one from scratch), use `sdf-displace`.

## See also

- **Surface displacement:** `sdf-displace`
- **Revolved profiles:** `sdf-revolve`
- **Bound infinite surfaces:** `sdf-intersection`, `sdf-difference`
- **Periodic patterns built on formulas:** `sdf-slats`, `sdf-bars`,
  `sdf-gyroid`, `sdf-schwarz-p`, `sdf-diamond`
