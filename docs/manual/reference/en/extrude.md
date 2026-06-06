---
name: extrude
category: generative-operations
since: ""
status: stable
---

# extrude

## Signature

`(extrude shape & path-commands)`
`(extrude shape recorded-path)`

## Description

Sweep a 2D profile along a path, producing a mesh. Returns a single mesh;
does not modify turtle state. The mesh starts at the current turtle
position and orientation.

`extrude` is the basic generative operation: the profile is constant along
the path. For a profile that varies with progress, use `loft`. For a
closed loop with no end caps, use `extrude-closed`.

The path can be passed as one or more turtle movement commands (auto-wrapped
in a `path`) or as a previously recorded path. Corner geometry is governed
by the current `joint-mode`.

## Parameters

- `shape` — a 2D shape, or a vector of shapes (e.g. from `text-shape`,
  `shape-xor`, or `svg-shapes`). Vector-of-shapes input is extruded
  independently along the same path and merged into a single mesh, so
  downstream booleans need no manual `concat-meshes`.
- `path-commands` — one or more turtle movement forms (`(f 30)`,
  `(th 45)`, `(arc-h 20 90)`, `(bezier-to …)`, …), or a single recorded
  path.

## Example

{{example: extrude-basic}}

<!-- example-source: extrude-basic -->
```clojure
(register tube (extrude (circle 15) (f 30)))
```
<!-- /example-source -->

A straight circular tube: a circle of radius 15 swept 30 units forward.

## Variations

{{example: extrude-multi-segment}}

<!-- example-source: extrude-multi-segment -->
```clojure
(register bent-tube
  (extrude (circle 15) (f 20) (th 45) (f 20)))
```
<!-- /example-source -->

Multiple movements are wrapped in a `path` automatically. The current
`joint-mode` controls how the corner at the `(th 45)` is rendered.

{{example: extrude-recorded-path}}

<!-- example-source: extrude-recorded-path -->
```clojure
(def trail (path (f 20) (th 60) (arc-h 15 90) (f 25)))
(register channel (extrude (rect 8 3) trail))
```
<!-- /example-source -->

Pass a previously recorded path instead of inline movements when the same
trajectory is reused across multiple operations.

## Notes

- The current `joint-mode` setting (`:flat`, `:round`, `:tapered`)
  determines corner geometry on path turns.
- For shapes with holes (e.g. from `shape-difference` or `voronoi-shell`),
  the holes are extruded through the entire path correctly.
- For the lower-level world-axis form, see `extrude-axis`
  (`extrude-z` / `extrude-y`): sweep a 2D path (list of `[x y]` pairs)
  along a world axis, bypassing the turtle's heading.
- **Profile marks become mesh anchors.** If the profile's source path
  seeded `(mark …)`s (via `path-to-shape`/`stroke-shape`/`embroid`), the
  extruded mesh carries them as `:anchors` on the base section, reachable
  with `(move-to mesh :at :mark …)`; compose with a position along the
  sweep using `:on`. See `path-to-shape` and `move-to`.

## See also

- **Related:** `loft`, `extrude-closed`, `extrude-axis`, `revolve`,
  `extrude+`, `joint-mode`
