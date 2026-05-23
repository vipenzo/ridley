---
name: vec3
category: math
since: ""
status: stable
---

# vec3

## Signature

See the table below. All `vec3-*` operators take 3-element Clojure vectors `[x y z]` and return either a vector or a scalar.

## Description

Vector operators on `[x y z]` triples. Useful when computing positions, headings, or alignments outside the turtle — for example inside a `displaced` shape-fn, a custom warp deform-fn, or when post-processing values returned by `turtle-position` / `turtle-heading`.

| Symbol | Signature | Returns | Description |
|--------|-----------|---------|-------------|
| `vec3+` | `(vec3+ a b)` | vector | Component-wise addition |
| `vec3-` | `(vec3- a b)` | vector | Component-wise subtraction |
| `vec3*` | `(vec3* v s)` | vector | Multiply vector by scalar `s` |
| `vec3-dot` | `(vec3-dot a b)` | scalar | Dot product |
| `vec3-cross` | `(vec3-cross a b)` | vector | Cross product (perpendicular to `a` and `b`) |
| `vec3-normalize` | `(vec3-normalize v)` | vector | Unit vector along `v`; the zero vector is returned unchanged |

Inputs are plain Clojure vectors of three numbers. Outputs follow the same convention.

## Parameters

- `a`, `b`, `v` — three-element vectors `[x y z]`.
- `s` — scalar multiplier (for `vec3*`).

## Example

{{example: vec3-triangle-normal}}

<!-- example-source: vec3-triangle-normal -->
```clojure
;; Compute the unit normal of a triangle defined by three points
(let [a [0 0 0]
      b [10 0 0]
      c [0 10 5]
      edge1 (vec3- b a)
      edge2 (vec3- c a)
      normal (vec3-normalize (vec3-cross edge1 edge2))]
  (println "normal:" normal))
```
<!-- /example-source -->

Two edge vectors are built with `vec3-`, their cross product gives a vector perpendicular to the triangle, and `vec3-normalize` reduces it to unit length.

## Notes

- A few common patterns:

  ```clojure
  ;; Midpoint of two points
  (vec3* (vec3+ p1 p2) 0.5)

  ;; Direction from a to b
  (vec3-normalize (vec3- b a))

  ;; Right-hand perpendicular to heading in the XY plane
  (vec3-cross heading [0 0 1])
  ```

- `vec3-normalize` returns the input unchanged when its magnitude is zero — it never throws on a zero vector.
- There is no built-in `vec3-magnitude` / `vec3-length` binding. Derive it with `(sqrt (vec3-dot v v))` when needed.

## See also

- **Related:** `math`, `turtle-state`
