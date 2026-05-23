---
name: stamp
category: 2d-shapes
since: ""
status: stable
---

# stamp

## Signature

`(stamp shape)`
`(stamp shape & {:keys [color]})`

## Description

Visualise a 2D shape at the current turtle position and orientation as a
semi-transparent surface. Shows exactly where the initial face of an
`extrude` or `revolve` would appear. Useful for debugging shape placement
before committing to an operation.

Stamps do not modify turtle position or heading; they exist purely as
preview overlays in the viewport.

A path can also be passed instead of a shape — it is auto-converted via
`path-to-shape` before stamping.

## Parameters

- `shape` — a 2D shape, or a path to be auto-converted.
- `:color` — hex integer (e.g. `0xff0000`); defaults to a translucent
  orange.

## Example

{{example: stamp-basic}}

<!-- example-source: stamp-basic -->
```clojure
(stamp (circle 20))
(f 30)
(stamp (rect 30 10) :color 0x44aaff)
```
<!-- /example-source -->

Place a circle at the start of the turtle's path, walk forward 30, then
stamp a blue rectangle. Inspect both stamps in the viewport before
deciding which profile to extrude.

## Notes

- Stamps are rendered as semi-transparent surfaces (default orange,
  visible from both sides). Shapes with holes are correctly triangulated.
- Toggle the global visibility with `show-stamps` / `hide-stamps`, or via
  the "Stamps" toggle in the viewport toolbar (see `stamp-visibility`).

## See also

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** `stamp-visibility` (`show-stamps` / `hide-stamps` /
  `stamps-visible?`), `extrude`, `revolve`, `path-to-shape`
