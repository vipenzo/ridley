---
name: bounds
category: registration-visibility
since: ""
status: stable
---

# bounds

## Signature

`(bounds name-or-ref)`

## Description

Return the 3D axis-aligned bounding box for a registered object or a
mesh value. The result is a map with `:min`, `:max`, `:center`, and
`:size`, each a `[x y z]` vector. Polymorphic on the argument: accepts
a keyword / string / symbol (registry lookup) or a mesh value directly.
Does not modify turtle state.

For dimension-by-dimension extraction without parsing the map, see the
helpers (`height`, `width`, `depth`, `top`, `bottom`, `center-x`,
`center-y`, `center-z`).

## Parameters

- `name-or-ref` — registered name or mesh value.

## Example

{{example: bounds-basic}}

<!-- example-source: bounds-basic -->
```clojure
(register frame (extrude (rect 40 20) (f 30)))
(bounds :frame)
;; => {:min [-20 -10 0] :max [20 10 30] :center [0 0 15] :size [40 20 30]}
```
<!-- /example-source -->

The bounds of a 40 × 20 rectangle extruded forward 30 units.

## Notes

- For 2D paths or shapes, use `bounds-2d` (path utilities).
- The bounds are computed from raw mesh vertices — they account for any
  `attach`/`attach!` transforms baked into the registered mesh.

## See also

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `info`, `dimensions` (`height` / `width` / `depth` /
  `top` / `bottom` / `center-*`), `bounds-2d`
