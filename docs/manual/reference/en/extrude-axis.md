---
name: extrude-axis
category: generative-operations
since: ""
status: stable
---

# extrude-axis

## Signature

`(extrude-z path distance)`
`(extrude-y path distance)`

## Description

Sweep a 2D path along a fixed world axis, bypassing the turtle's heading
entirely. `extrude-z` extrudes along world Z (vertical), `extrude-y` along
world Y. Both are thin wrappers over the lower-level
`(extrude path axis distance)` form with a fixed axis vector
(`[0 0 1]` / `[0 1 0]`). Returns a mesh; does not modify turtle state.

Useful when the input is a flat 2D outline expressed as a list of
`[x y]` pairs and the goal is a simple prismatic extrusion regardless of
where the turtle currently sits.

## Parameters

- `path` — a 2D path (or a list of `[x y]` pairs).
- `distance` — extrusion length along the chosen axis.

## Example

{{example: extrude-z-basic}}

<!-- example-source: extrude-z-basic -->
```clojure
(def silhouette (path (f 20) (th 90) (f 10) (th 90) (f 20)))
(register pillar (extrude-z silhouette 30))
```
<!-- /example-source -->

Extrude a 2D silhouette path straight up 30 units along world Z.

## Variations

{{example: extrude-y-basic}}

<!-- example-source: extrude-y-basic -->
```clojure
(register wall (extrude-y (path (f 50) (th 90) (f 10) (th 90) (f 50)) 20))
```
<!-- /example-source -->

`extrude-y` does the same along world Y — useful for horizontal walls
when starting from a top-down floor plan.

## Notes

- The turtle's current heading and position are ignored. To sweep a shape
  along the turtle's heading instead, use the main `extrude` form.

## See also

- **Related:** `extrude`, `extrude-closed`, `loft`
