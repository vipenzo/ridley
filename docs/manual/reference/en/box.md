---
name: box
category: 3d-primitives
since: ""
status: stable
---

# box

## Signature

`(box size)`
`(box r u f)`

## Description

Create a rectangular box mesh centered at the current turtle position
and oriented along the turtle's local frame. With one argument, all
three extents are equal (a cube). With three arguments, the extents are
independent: `r` along the right axis (`heading × up`), `u` along the
up axis, `f` along the heading. Returns a mesh; **does not modify
turtle state**.

The box is anchored at its centre on the turtle position; this differs
from `extrude`, which anchors at the base of the swept path.

The primitive carries semantic face IDs (`:top`, `:bottom`, `:left`,
`:right`, `:front`, `:back`) that can be addressed via the face
selection API (see Spec §8 *Faces*).

## Parameters

- `size` — uniform edge length (one-arity form).
- `r` — extent along the turtle's right axis.
- `u` — extent along the turtle's up axis.
- `f` — extent along the turtle's heading.

## Example

{{example: box-cube}}

<!-- example-source: box-cube -->
```clojure
(register cube (box 20))
```
<!-- /example-source -->

A 20×20×20 cube centred on the turtle.

## Variations

{{example: box-rectangular}}

<!-- example-source: box-rectangular -->
```clojure
(register slab (box 40 5 30))
;; 40 right, 5 up, 30 forward (along heading)
```
<!-- /example-source -->

Independent extents per axis. The first number always goes along the
local right axis; the third along the heading.

## Notes

- All three primitives with an extension axis (`box`, `cyl`, `cone`)
  extend along the turtle's heading. `box` is the only one anchored at
  its centre — `cyl` and `cone` anchor at the base.
- Semantic face IDs survive subsequent transforms (`attach`, …) and can
  be addressed by name in face selection.

## See also

- **Related:** `sphere`, `cyl`, `cone`, `extrude`, `rect`
