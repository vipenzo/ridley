---
name: math
category: math
since: ""
status: stable
---

# math

## Signature

See the tables below. All math functions are top-level bindings and wrap the host `js/Math.*` API.

## Description

Standard scalar math functions and the constant `PI`. Trigonometric functions operate in **radians**, while turtle rotation commands (`th`, `tv`, `tr`, `arc-h`, `arc-v`, `rotate-shape`, …) take **degrees** — convert with `to-radians` / `to-degrees` (or `(* deg (/ PI 180))`) when mixing the two.

Beyond the bindings listed here, the full `clojure.core` numerics are available without any binding declaration: `+`, `-`, `*`, `/`, `mod`, `quot`, `rem`, `inc`, `dec`, `zero?`, `pos?`, `neg?`, `even?`, `odd?`, `compare`, `==`, `<`, `<=`, `>`, `>=`, plus `map`, `reduce`, `for`, `range`, etc.

### Trigonometric

All trig functions operate in **radians**.

| Symbol | Signature | Description |
|--------|-----------|-------------|
| `sin` | `(sin x)` | Sine of angle in radians |
| `cos` | `(cos x)` | Cosine of angle in radians |
| `tan` | `(tan x)` | Tangent of angle in radians |
| `asin` | `(asin x)` | Inverse sine, returns radians in `[-PI/2, PI/2]` |
| `acos` | `(acos x)` | Inverse cosine, returns radians in `[0, PI]` |
| `atan` | `(atan x)` | Inverse tangent, returns radians in `(-PI/2, PI/2)` |
| `atan2` | `(atan2 y x)` | Two-argument arctangent, returns radians in `(-PI, PI]` |

### Exponentials and logarithms

| Symbol | Signature | Description |
|--------|-----------|-------------|
| `exp` | `(exp x)` | `e^x` |
| `math-log` | `(math-log x)` | Natural logarithm (`Math.log`). Note: bare `log` is the debug printer, not the math function — see Notes. |
| `pow` | `(pow base exp)` | `base^exp` |
| `sqrt` | `(sqrt x)` | Square root |

### Rounding

| Symbol | Signature | Description |
|--------|-----------|-------------|
| `floor` | `(floor x)` | Largest integer not greater than `x` |
| `ceil` | `(ceil x)` | Smallest integer not less than `x` |
| `round` | `(round x)` | Nearest integer (ties round to `+inf`, JavaScript convention) |
| `abs` | `(abs x)` | Absolute value |

### Comparison

| Symbol | Signature | Description |
|--------|-----------|-------------|
| `min` | `(min x y …)` | Smallest of the arguments (variadic) |
| `max` | `(max x y …)` | Largest of the arguments (variadic) |

`min` / `max` wrap `Math.min` / `Math.max`. Any non-numeric argument propagates `NaN`.

### Angle conversion

| Symbol | Signature | Description |
|--------|-----------|-------------|
| `to-radians` | `(to-radians deg)` | Convert degrees to radians |
| `to-degrees` | `(to-degrees rad)` | Convert radians to degrees |

### Constants

| Name | Value | Description |
|------|-------|-------------|
| `PI` | `3.141592…` | Ratio of circumference to diameter |

No `TWO-PI`, `HALF-PI`, or `E` constants are predefined. Build them with `(* 2 PI)`, `(/ PI 2)`, `(exp 1)`, or define your own with `def`.

## Example

{{example: math-arrange-around-circle}}

<!-- example-source: math-arrange-around-circle -->
```clojure
;; Arrange 8 spheres around a circle of radius 30 using trig
(let [n 8 r 30]
  (doseq [i (range n)]
    (reset)
    (let [a (* i (/ (* 2 PI) n))]                  ;; angle in radians
      (register (keyword (str "ball-" i))
                (translate (sphere 4) [(* r (cos a)) (* r (sin a)) 0])))))
```
<!-- /example-source -->

Eight spheres are placed evenly around a circle by stepping through angles in radians and using `cos` / `sin` to derive `(x, y)`.

## Notes

- **`math-log` vs `log` — the common trap.** `math-log` is the natural logarithm (`Math.log`). Bare `log` is a *debug printer* bound to `js/console.log` and writes to the browser devtools console. The two are distinct bindings: see [console-print.md](console-print.md). If you mean `ln(x)` in a formula, write `math-log`.
- **Radians vs degrees.** Trig functions take radians; turtle rotations take degrees. Mixing them silently produces nonsense — always convert at the boundary.
- **No built-in `lerp`, `clamp`, `map-range`.** Define them yourself when needed:

  ```clojure
  (defn lerp [a b t] (+ a (* (- b a) t)))
  (defn clamp [x lo hi] (max lo (min hi x)))
  ```

## See also

- **Related:** `vec3`, `console-print`
