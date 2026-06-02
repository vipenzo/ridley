---
name: sdf-revolve
category: sdf-modeling
since: ""
status: stable
---

# sdf-revolve

## Signature

`(sdf-revolve node-2d)`

## Description

Revolve a 2D SDF around the Z axis to produce a 3D SDF. The 2D input
is interpreted in **radial coordinates**: its X corresponds to the
radial distance from the Z axis, its Y to the height. Returns a 3D
SDF tree.

`sdf-revolve` is the SDF analogue of mesh `revolve`. It lets you build
shapes by drawing a profile in 2D — typically with `sdf-formula` — and
spinning it around. Common targets: vases, bowls, lathed parts, torus
variants with non-circular cross-sections.

> Desktop only: requires the libfive backend.

## Parameters

- `node-2d` — a 2D SDF (typically from `sdf-formula`). Its X axis
  becomes the radial direction; its Y axis becomes the height.

## Example

{{example: sdf-revolve-torus}}

<!-- example-source: sdf-revolve-torus -->
```clojure
;; A torus from an explicit profile: a circle of radius 3 centred at x=10
(register torus
  (sdf-revolve
    (sdf-formula
      '(- (sqrt (+ (* (- x 10) (- x 10)) (* y y))) 3))))
```
<!-- /example-source -->

The formula describes a circle in the XY plane (radius 3, centred at
`x = 10`); revolving it around Z produces a torus equivalent to
`(sdf-torus 10 3)`.

## Variations

{{example: sdf-revolve-vase}}

<!-- example-source: sdf-revolve-vase -->
```clojure
;; A cosine-fluted vase profile, shelled for printing
(register vase
  (sdf-shell
    (sdf-revolve
      (sdf-formula '(- x (+ 30 (* 10 (cos (* y 0.05)))))))
    2))
```
<!-- /example-source -->

The formula `x - (30 + 10·cos(0.05y))` describes a vertically rippled
profile; revolving gives the full vase silhouette; `sdf-shell` hollows
it for printing.

## Notes

- The auto-bounds of the resulting 3D SDF treat the input's X range as
  radial extent. Built-in primitives that use `sdf-revolve`
  internally (e.g. `sdf-torus`) annotate the right ranges; custom
  profiles inherit auto-bounds from the formula.
- To revolve around a different axis, build the profile to suit and
  rotate the result with `rotate`.

## See also

- **2D input building block:** `sdf-formula`
- **Related primitives:** `sdf-torus`, `sdf-cyl`
- **Mesh equivalent:** `revolve`
