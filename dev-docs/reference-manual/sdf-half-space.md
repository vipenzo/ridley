---
name: sdf-half-space
category: sdf-modeling
since: ""
status: stable
---

# sdf-half-space

## Signature

`(sdf-half-space)`
`(sdf-half-space :cut-ahead)`

## Description

Return an SDF representing one half of 3-space, separated by a plane
through the current turtle position with normal equal to the heading.

By default the function keeps the side **behind** the heading — the
side the turtle came from. This matches the `extrude` convention: after
extruding a solid along the heading the turtle ends on the far face,
with the material behind it, so `(sdf-half-space)` at that pose returns
the half containing the material. The variant `:cut-ahead` returns the
opposite half (the one ahead of the heading).

Intersect with another SDF to clip it against the plane. The one-call
shortcut for the common `(sdf-intersection shape (sdf-half-space))`
pattern is `sdf-clip`.

> Desktop only: requires the libfive backend.

## Parameters

- Optional flag `:cut-ahead`. Without it, the function keeps the
  half-space behind the heading; with it, the half-space ahead.

## Example

{{example: sdf-half-space-clip-cylinder}}

<!-- example-source: sdf-half-space-clip-cylinder -->
```clojure
;; Cut a cylinder in half along the turtle plane
(turtle (tv 90)
  (register half
    (sdf-intersection (rotate (sdf-cyl 8 30) :y 90)
                      (sdf-half-space))))
```
<!-- /example-source -->

`(tv 90)` reorients the turtle so the heading points up; the half-space
keeps the lower half (behind the new heading). The cylinder is clipped
to its bottom half.

## Variations

{{example: sdf-half-space-cut-ahead}}

<!-- example-source: sdf-half-space-cut-ahead -->
```clojure
;; Keep the half AHEAD of the heading instead
(turtle (tv 90)
  (register top
    (sdf-intersection (rotate (sdf-cyl 8 30) :y 90)
                      (sdf-half-space :cut-ahead))))
```
<!-- /example-source -->

`:cut-ahead` flips the kept side. Useful when you want to slice off
the part *in front of* the turtle rather than behind it.

## Notes

- The cut plane is determined by the turtle pose **at construction
  time**. To clip relative to a different frame, wrap in `turtle (…)`
  or use `attach` to position before the call.
- For a single-step clip without writing the `sdf-intersection`
  yourself, use `sdf-clip`.

## See also

- **Convenience:** `sdf-clip`
- **Related:** `sdf-intersection`, `sdf-difference`, `turtle`, `attach`
