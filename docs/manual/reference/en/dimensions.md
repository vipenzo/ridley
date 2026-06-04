---
name: height
category: registration-visibility
since: ""
status: stable
---

# height / width / depth / top / bottom / center-x / center-y / center-z

## Signature

`(height name-or-mesh)`
`(width name-or-mesh)`
`(depth name-or-mesh)`
`(top name-or-mesh)`
`(bottom name-or-mesh)`
`(center-x name-or-mesh)`
`(center-y name-or-mesh)`
`(center-z name-or-mesh)`

## Description

Single-value accessors derived from a mesh's bounding box. Each function
is unary, accepts either a registered name or a mesh value, and returns
a number.

| Function | Returns | Equivalent |
|---|---|---|
| `height` | Z extent | `(get-in (bounds …) [:size 2])` |
| `width`  | X extent | `(get-in (bounds …) [:size 0])` |
| `depth`  | Y extent | `(get-in (bounds …) [:size 1])` |
| `top`    | maximum Z | `(get-in (bounds …) [:max 2])` |
| `bottom` | minimum Z | `(get-in (bounds …) [:min 2])` |
| `center-x` | centroid X | `(get-in (bounds …) [:center 0])` |
| `center-y` | centroid Y | `(get-in (bounds …) [:center 1])` |
| `center-z` | centroid Z | `(get-in (bounds …) [:center 2])` |

Useful for quick measurements, alignment, or driving derived geometry
from existing meshes.

## Parameters

- `name-or-mesh` — registered name (keyword / string / symbol) or mesh
  value.

## Example

{{example: dimensions-basic}}

<!-- example-source: dimensions-basic -->
```clojure
(register frame (extrude (rect 40 20) (f 30)))
(width  :frame)   ;; => 40
(depth  :frame)   ;; => 20
(height :frame)   ;; => 30
(top    :frame)   ;; => 30
(center-z :frame) ;; => 15
```
<!-- /example-source -->

Width / depth / height map to X / Y / Z size respectively. `top`,
`bottom`, and the `center-*` family expose individual bounding-box
coordinates.

## See also

- **Related:** `bounds`, `info`, `mesh`
