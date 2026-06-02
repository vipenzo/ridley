---
name: inflate
category: spatial-deformation
since: ""
status: stable
---

# inflate

## Signature

`(inflate amount)`

## Description

Return a deform-fn that pushes vertices outward along their normals. The
displacement at the centre of the volume is `amount`; it falls off
smoothly to zero at the boundary via `smooth-falloff`. Use with `warp`.

`inflate` is the canonical preset for organic bumps: a sphere volume
plus `(inflate k)` is the quickest way to add a localised swelling to a
flat face. For the opposite effect (pull inward), use `dent`.

## Parameters

- `amount` — maximum outward displacement in world units, at the
  volume's centre. Negative values pull inward, equivalent to
  `(dent (- amount))`.

## Example

{{example: inflate-bump-on-box}}

<!-- example-source: inflate-bump-on-box -->
```clojure
;; Bump on top of a box
(register b
  (warp (box 40) (attach (sphere 15) (u 20)) (inflate 4) :subdivide 2))
```
<!-- /example-source -->

The sphere is positioned 20 units up; `inflate 4` pushes the top-face
vertices inside that sphere up to 4 units along their normals.
`:subdivide 2` ensures the box has enough vertices to deform smoothly.

## Notes

- Vertex normals are estimated by the warp engine — they may differ
  slightly from analytic normals on primitives, but the effect is
  smooth for typical use.
- For very low-poly inputs, increase `:subdivide` on the `warp` call,
  not the `amount`.

## See also

- **Mother card:** `warp`
- **Related presets:** `dent`, `attract`, `roughen`, `squash`, `twist`
- **Helpers:** `smooth-falloff`
