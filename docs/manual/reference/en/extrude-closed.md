---
name: extrude-closed
category: generative-operations
since: ""
status: stable
---

# extrude-closed

## Signature

`(extrude-closed shape & path-commands)`
`(extrude-closed shape recorded-path)`

## Description

Sweep a 2D profile along a closed loop and connect the last ring back to
the first. Returns a manifold mesh with no end caps — useful for tori,
pipe loops, frames, and any closed-loop geometry. Does not modify turtle
state.

The path should return to its starting point. If it does not, the last
ring is still connected to the first, but the geometry may be skewed.

## Parameters

- `shape` — a 2D shape, or a vector of shapes (merged into a single mesh
  like `extrude`).
- `path-commands` — turtle movement forms forming a closed loop, or a
  recorded path that closes on itself.

## Example

{{example: extrude-closed-torus}}

<!-- example-source: extrude-closed-torus -->
```clojure
(def square-path (path (dotimes [_ 4] (f 20) (th 90))))
(register torus (extrude-closed (circle 5) square-path))
```
<!-- /example-source -->

A square torus: the small circle traces around four straight segments
joined by 90° turns, and the last ring closes back onto the first.

## Notes

- The mesh has no caps; both ends of the path are stitched together.
- The current `joint-mode` setting controls corner geometry on path turns.
- For an open extrusion that keeps both end caps, use `extrude`.

## See also

- **Guide:** placeholder → cap. 4 (Estrusione) e cap. 6 (Da funzioni
  matematiche a forme)
- **Related:** `extrude`, `revolve`, `joint-mode`
