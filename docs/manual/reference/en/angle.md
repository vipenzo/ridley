---
name: angle
category: generative-operations
since: ""
status: stable
---

# angle

## Signature

`(angle p)`

## Description

Return the angle (radians) of a 2D point relative to the origin. Defined
as `(atan2 y x)` over the point's components, so the result is in
`(-π, π]` with `+x` mapping to `0` and `+y` mapping to `π/2`. Does not
modify turtle state.

Used heavily inside custom displacement functions for shape-fns —
particularly with `displaced` and `displace-radial` — to drive angular
patterns around the contour.

## Parameters

- `p` — a 2D point `[x y]`.

## Example

{{example: angle-basic}}

<!-- example-source: angle-basic -->
```clojure
(angle [1 0])      ;; => 0
(angle [0 1])      ;; => π/2 (≈ 1.5708)
(angle [-1 0])     ;; => π
```
<!-- /example-source -->

The angle of each cardinal direction. `+x` is zero, anticlockwise from
there.

## Variations

{{example: angle-in-shape-fn}}

<!-- example-source: angle-in-shape-fn -->
```clojure
(register petals
  (loft (displaced (circle 18 96)
                   (fn [p t]
                     (* 2 (sin (* (angle p) 8)))))
        (f 20)))
```
<!-- /example-source -->

Use `angle` inside a `displaced` callback to drive an angular pattern —
here, an 8-petal modulation around the contour.

## See also

- **Related:** `displaced`, `displace-radial`, `shape-fn`
