---
name: cone
category: 3d-primitives
since: ""
status: stable
---

# cone

## Signature

`(cone r1 r2 height)`
`(cone r1 r2 height segments)`

## Description

Create a frustum (truncated cone) mesh centered on the current turtle
position. The frustum axis runs along the turtle's **heading**, with the
turtle at the center: the two circular faces sit `height/2` to either
side along the heading, so the turtle is equidistant from both. `r1` is
the base radius and `r2` the top radius; the base (`r1`) is the face on
the heading side (forward), the top (`r2`) the face behind. Set `r2 = 0`
for a proper cone. Returns a mesh; **does not modify turtle state**.

The segment count defaults to the current resolution; pass an explicit
value to override it. The primitive carries semantic face IDs
(`:top`, `:bottom`, `:side`) addressable via the face selection API.

## Parameters

- `r1` — base radius; the base is the face on the heading side (forward).
- `r2` — top radius; the top is the face behind. Set to 0 for a sharp cone.
- `height` — total length along the turtle's heading; the two faces sit
  `height/2` to either side of the turtle.
- `segments` — circumferential segments. Optional; defaults to the
  resolution setting.

## Example

{{example: cone-frustum}}

<!-- example-source: cone-frustum -->
```clojure
(register cup (cone 10 6 20))      ;; truncated cone, wider at the base
```
<!-- /example-source -->

A frustum with base radius 10, top radius 6, height 20.

## Variations

{{example: cone-pointed}}

<!-- example-source: cone-pointed -->
```clojure
(register spike (cone 8 0 25))     ;; sharp cone (r2 = 0)
```
<!-- /example-source -->

`r2 = 0` makes a proper cone, useful for spikes and points.

## Notes

- The axis convention matches `cyl` (axis along heading).
- `cone` is shape-equivalent to `(loft (circle r2) (circle r1) (f height))`,
  except that `cone` is centered on the turtle while the `loft` form is
  anchored at its start. Use `cone` as the cheap primitive for this case.

## See also

- **Related:** `cyl`, `box`, `sphere`, `loft`, `tapered`
