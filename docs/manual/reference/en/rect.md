---
name: rect
category: 2d-shapes
since: ""
status: stable
---

# rect

## Signature

`(rect r u)`

## Description

Construct a rectangular 2D shape centered at the origin. The first argument
is the extent along the section plane's right axis, the second the extent
along the up axis (the section plane is the plane orthogonal to the
turtle's heading). The shape is centered (`:centered? true`). Does not
modify turtle state.

## Parameters

- `r` — total width along the right axis.
- `u` — total height along the up axis.

## Example

{{example: rect-basic}}

<!-- example-source: rect-basic -->
```clojure
(register slab (extrude (rect 40 10) (f 20)))
```
<!-- /example-source -->

A flat slab 40 wide, 10 tall, extruded 20 units forward.

## Notes

- `r` and `u` are total extents, not half-extents. `(rect 40 10)` spans
  from `-20` to `+20` on the right axis.

## See also

- **Related:** `circle`, `polygon`, `fillet-shape`, `chamfer-shape`,
  `box` (3D analogue)
