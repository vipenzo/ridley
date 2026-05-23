---
name: dent
category: spatial-deformation
since: ""
status: stable
---

# dent

## Signature

`(dent amount)`

## Description

Return a deform-fn that pushes vertices inward along their normals — the
opposite of `inflate`. The inward displacement at the centre of the
volume is `amount`; it falls off smoothly to zero at the boundary via
`smooth-falloff`. Use with `warp`.

`dent` is a convenience alias: `(dent k)` is identical to
`(inflate (- k))`. It exists for readability when the intent is "press
in" rather than "push out".

## Parameters

- `amount` — maximum inward displacement in world units, at the
  volume's centre. Negative values push outward.

## Example

{{example: dent-on-sphere}}

<!-- example-source: dent-on-sphere -->
```clojure
;; A localised dent on a sphere
(register s
  (warp (sphere 30 32 16) (attach (sphere 10) (f 25)) (dent 4)))
```
<!-- /example-source -->

The volume — a small sphere placed 25 units forward — defines where the
dent acts. Vertices inside it are pushed up to 4 units along the
inward direction of their normals.

## Notes

- See `inflate` for the underlying mechanic; `dent` only flips the sign.

## See also

- **Mother card:** `warp`
- **Related presets:** `inflate`, `attract`, `roughen`, `squash`, `twist`
