---
name: sdf-clip
category: sdf-modeling
since: ""
status: stable
---

# sdf-clip

## Signature

`(sdf-clip shape)`

## Description

Clip `shape` against the plane defined by the current turtle pose,
keeping the half behind the heading. Equivalent to
`(sdf-intersection shape (sdf-half-space))`.

`sdf-clip` is the shortcut for the most common half-space pattern: slice
an SDF in half at the turtle's position. For the rare case where you
want the half *ahead* of the heading, use the explicit form with
`(sdf-half-space :cut-ahead)`.

> Desktop only: requires the libfive backend.

## Parameters

- `shape` — an SDF node to clip.

## Example

{{example: sdf-clip-cylinder}}

<!-- example-source: sdf-clip-cylinder -->
```clojure
;; Keep the lower half of a horizontal cylinder
(turtle (tv 90)
  (register half (sdf-clip (rotate (sdf-cyl 6 30) :y 90))))
```
<!-- /example-source -->

The turtle points up after `(tv 90)`; `sdf-clip` keeps the half *behind*
the new heading — i.e. the lower half of the cylinder.

## Notes

- For the opposite kept-side, use
  `(sdf-intersection shape (sdf-half-space :cut-ahead))` instead — the
  shortcut covers only the behind-the-heading case.
- The cut plane is captured at the moment `sdf-clip` is called. Wrap
  in `turtle (…)` to avoid leaking turtle state changes outside the
  clip.

## See also

- **Underlying call:** `sdf-half-space`, `sdf-intersection`
- **Related:** `turtle`, `attach`
