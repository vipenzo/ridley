---
name: shape-bridge
category: 2d-shapes
since: ""
status: stable
---

# shape-bridge

## Signature

`(shape-bridge a b & {:keys [radius join-type]})`
`(shape-bridge a b c & more+opts)`

## Description

Connect N shapes with smooth bridges by offsetting each shape outward by
`:radius`, unioning the expansions, then contracting the union by the same
radius. The result is a single outline where nearby shapes are joined by
rounded fillets. Implemented with Clipper2. Does not modify turtle state.

Useful for fairing disconnected blobs into one organic outline, or for
blending feature shapes into a base contour before extrusion.

## Parameters

- `a b c …` — two or more 2D shapes.
- `:radius` — outward offset distance, controlling both the bridge width
  and the corner radius (default `1`).
- `:join-type` — `:round` (default), `:square`, or `:miter`. Same semantics
  as `shape-offset`.

## Example

{{example: shape-bridge-basic}}

<!-- example-source: shape-bridge-basic -->
```clojure
(def fused (shape-bridge (circle 10) (translate (circle 8) 25 0) :radius 5))
(register dumbbell (extrude fused (f 4)))
```
<!-- /example-source -->

Two separate circles fused into a single dumbbell-like outline by a
5-unit bridge. Unlike `shape-hull`, the bridge follows the original
silhouette rather than the convex envelope.

## Variations

{{example: shape-bridge-variadic}}

<!-- example-source: shape-bridge-variadic -->
```clojure
(def cluster (shape-bridge (circle 8)
                           (translate (circle 8) 20 0)
                           (translate (circle 8) 10 18)
                           :radius 4 :join-type :round))
(register triplet (extrude cluster (f 3)))
```
<!-- /example-source -->

Three circles bridged into a single rounded outline. Variadic in the
number of input shapes.

## Notes

- Compared with `shape-hull`, `shape-bridge` preserves concavities of the
  input shapes — it only fills gaps within reach of `:radius`. Use
  `shape-hull` when you want the convex envelope.
- The bridge radius is symmetric: expanding then contracting by the same
  amount leaves isolated portions of each shape unchanged.

## See also

- **Related:** `shape-hull`, `shape-offset`, `shape-union`
