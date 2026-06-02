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

Create a frustum (truncated cone) mesh at the current turtle position.
The frustum axis runs along the turtle's **heading**. `r1` is the
radius at the base (turtle position); `r2` is the radius at the far end
(at distance `height` along heading). Set `r2 = 0` for a proper cone.
Returns a mesh; **does not modify turtle state**.

The segment count defaults to the current resolution; pass an explicit
value to override it. The primitive carries semantic face IDs
(`:top`, `:bottom`, `:side`) addressable via the face selection API.

## Parameters

- `r1` — base radius (at the turtle position).
- `r2` — top radius (`height` units along the heading from the turtle).
- `height` — length along the turtle's heading.
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
- For a generative cone with varying cross-section, use
  `(loft (tapered (circle r) :to 0) (f h))` — `cone` is the cheap
  primitive equivalent of that case.

## See also

- **Guide:** placeholder → cap. 2 (Costruire con primitive 3D)
- **Related:** `cyl`, `box`, `sphere`, `loft`, `tapered`
