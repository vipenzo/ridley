---
name: cyl
category: 3d-primitives
since: ""
status: stable
---

# cyl

## Signature

`(cyl radius height)`
`(cyl radius height segments)`

## Description

Create a cylinder mesh at the current turtle position. The cylinder
axis runs along the turtle's **heading**; the circular section sits in
the right-up plane (the plane perpendicular to heading). The base sits
at the turtle position; the cylinder extends along `+heading`. Returns
a mesh; **does not modify turtle state**.

The segment count defaults to the current resolution setting; pass an
explicit value to override it. The primitive carries semantic face IDs
(`:top`, `:bottom`, `:side`) addressable via the face selection API.

## Parameters

- `radius` — radius of the circular section.
- `height` — length along the turtle's heading.
- `segments` — circumferential segments. Optional; defaults to the
  resolution setting.

## Example

{{example: cyl-basic}}

<!-- example-source: cyl-basic -->
```clojure
(register pipe (cyl 5 30))
```
<!-- /example-source -->

A cylinder of radius 5, length 30, oriented along the turtle's heading.

## Variations

{{example: cyl-explicit-segments}}

<!-- example-source: cyl-explicit-segments -->
```clojure
(register low-poly (cyl 5 30 8))   ;; 8-sided cylinder (a hexagonal prism)
```
<!-- /example-source -->

Forcing a low segment count yields a regular prism.

## Notes

- For a different orientation, rotate the turtle before the call (e.g.
  `(tv 90)` to make the cylinder vertical from a default-heading
  turtle).
- The axis convention matches `cone` (axis along heading) and `extrude`
  (extension along heading).

## See also

- **Guide:** placeholder → cap. 2 (Costruire con primitive 3D)
- **Related:** `cone`, `box`, `sphere`, `extrude`, `circle`
