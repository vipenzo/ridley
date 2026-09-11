---
name: current-path-length
category: generative-operations
since: ""
status: stable
---

# current-path-length

## Signature

`(current-path-length)`

## Description

The length, in world units, of the sweep a shape-fn is being evaluated
for — readable **only while** `loft` or `revolve` is calling your
transform; anywhere else it returns `nil`.

For a `loft` it is the path's length. For a `revolve` it is the arc the
`t = 0` profile's centroid travels: `|angle| · r`, where `r` is the
centroid's distance from the axis.

This is the same value the built-in shape-fns size themselves by:
`capped` derives its auto transition fraction from it, `heightmap`
`:fit :physical` takes it as the surface height, `embroid` as its
sweep depth.

## Parameters

None.

## Example

{{example: current-path-length-basic}}

<!-- example-source: current-path-length-basic -->
```clojure
;; A bulge whose width is a fixed 10 mm whatever the loft's length
(defn bulge [shape t]
  (let [L (or (current-path-length) 1)
        w (/ 10 L)                               ; 10 mm as a fraction of the sweep
        s (+ 1 (* 0.3 (smoothstep 0 w (- 0.5 (abs (- t 0.5))))))]
    (scale-shape shape s)))

(register short-tube (loft-n 32 (shape-fn (circle 8 48) bulge) (f 30)))
(register long-tube  (turtle (rt 30) (loft-n 64 (shape-fn (circle 8 48) bulge) (f 90))))
```
<!-- /example-source -->

## Notes

- **Not a variable.** It is a function call, evaluated at the moment your
  transform runs; capture it inside the transform, not outside.
- Outside a sweep it is `nil` — guard with `(or (current-path-length) …)`
  if the same shape-fn is also called by hand.
- On a full-turn `revolve` the profile at `t = 1` is never built (it would
  coincide with `t = 0`); the length is still the whole turn.

## See also

- **Related:** `shape-fn`, `capped`, `heightmap`, `path-length`,
  `smoothstep`
