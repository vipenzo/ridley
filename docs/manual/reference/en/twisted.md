---
name: twisted
category: generative-operations
since: ""
status: stable
---

# twisted

## Signature

`(twisted shape-or-fn & {:keys [angle]})`

## Description

Shape-fn that rotates the cross-section progressively along the path. At
`t = 0` the rotation is `0°`; at `t = 1` it is `:angle` degrees, linearly
interpolated in between. Used with `loft` or `revolve`. Does not
modify turtle state.

## Parameters

- `shape-or-fn` — a 2D shape, or another shape-fn (composes).
- `:angle` — total rotation in degrees from start to end (default `360`).

## Example

{{example: twisted-rectangle}}

<!-- example-source: twisted-rectangle -->
```clojure
(register twist (loft (twisted (rect 20 10) :angle 90) (f 40)))
```
<!-- /example-source -->

A rectangle that rotates 90° as it extrudes — a classic twisted bar.

## Variations

{{example: twisted-and-tapered}}

<!-- example-source: twisted-and-tapered -->
```clojure
(register screw (loft (-> (rect 30 10) (twisted :angle 360) (tapered :to 0.5))
                      (f 60)))
```
<!-- /example-source -->

Compose with `tapered` for a tapered twist — `->` threads the
shape-fns and they apply in order.

## Notes

- Default angle is `360°` (a full turn). Use a smaller value (e.g. `90`)
  for gentle twists.
- The rotation is around the shape's centroid in 2D, not around the
  turtle's heading axis. The 3D twist comes from the rotated 2D ring
  being placed along the path.

## See also

- **Related:** `loft`, `tapered`, `fluted`, `shape-fn`
