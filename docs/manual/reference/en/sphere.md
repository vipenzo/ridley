---
name: sphere
category: 3d-primitives
since: ""
status: stable
---

# sphere

## Signature

`(sphere radius)`
`(sphere radius segments rings)`

## Description

Create a sphere mesh centred at the current turtle position. With one
argument, both circumferential segments and rings are derived from the
current resolution setting. With three arguments, segments and rings
are explicit. Returns a mesh; **does not modify turtle state**.

The sphere has no preferred orientation: it is rotationally symmetric.
The turtle's heading and up are honoured only for placement (`attach`
and similar transforms still work as expected).

## Parameters

- `radius` — sphere radius.
- `segments` — number of meridians (circumferential subdivisions).
  Optional.
- `rings` — number of parallels (latitude subdivisions). Optional.

When `segments` and `rings` are omitted, both are derived from the
turtle's resolution.

## Example

{{example: sphere-basic}}

<!-- example-source: sphere-basic -->
```clojure
(register ball (sphere 10))
```
<!-- /example-source -->

A sphere of radius 10 centred on the turtle.

## Variations

{{example: sphere-explicit-resolution}}

<!-- example-source: sphere-explicit-resolution -->
```clojure
(register smooth-ball (sphere 10 64 32))
;; 64 meridians, 32 rings
```
<!-- /example-source -->

Explicit segments and rings for higher fidelity than the global
resolution.

## Notes

- For deformed spheres (oblate, prolate), use `(loft (morphed …) …)` or
  pair a base sphere with `attach`.
- The sphere carries semantic face IDs only at its poles (`:top`,
  `:bottom`); the rest of the surface is a single connected mesh.

## See also

- **Related:** `box`, `cyl`, `cone`, `resolution`
