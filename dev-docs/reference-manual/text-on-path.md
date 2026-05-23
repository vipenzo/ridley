---
name: text-on-path
category: text
since: ""
status: stable
---

# text-on-path

## Signature

`(text-on-path text path & {:keys [size depth font spacing align overflow]})`

## Description

Place 3D text along a curved path: each glyph is positioned at its
advance-width offset along the path and rotated to align with the local
tangent. Returns a **vector of meshes**, one per glyph. Mutates the
turtle state (each emitted mesh is added to the current turtle's mesh
list), like other constructors.

Use `text-on-path` for engraved labels along arcs, names along a ring,
or any layout where glyphs need to follow a non-straight axis. For
straight text, `extrude-text` is simpler and cheaper.

## Parameters

- `text` — the string to render.
- `path` — a recorded path (from `path` or any path-builder).
- `:size` (default `10`) — font size in units.
- `:depth` (default `5`) — extrusion depth perpendicular to the path.
- `:font` (default the loaded Roboto) — opentype.js font object.
- `:spacing` (default `0`) — extra letter spacing on top of the
  font's advance widths. Can be negative for tight tracking.
- `:align` (default `:start`) — `:start`, `:center`, or `:end`.
  Positions the text block within the available path length.
- `:overflow` (default `:truncate`) — behaviour when the text is
  longer than the path: `:truncate` stops placing, `:wrap` continues
  from the start (closed paths), `:scale` rescales each glyph's offset
  so the text fits exactly.

## Example

{{example: text-on-path-arc}}

<!-- example-source: text-on-path-arc -->
```clojure
;; Text following a gentle arc
(def arc (path (dotimes [_ 40] (f 2) (th 3))))
(register curved (text-on-path "RIDLEY" arc :size 12 :depth 3))
```
<!-- /example-source -->

The path turns slightly with each step; each glyph picks up the local
tangent so the baseline follows the curve.

## Variations

{{example: text-on-path-centered}}

<!-- example-source: text-on-path-centered -->
```clojure
;; Centred text on a closed ring
(def ring (path (dotimes [_ 120] (f 1.5) (th 3))))
(register label
  (text-on-path "HELLO WORLD" ring
                :size 10 :depth 2
                :align :center :overflow :wrap :spacing 0.5))
```
<!-- /example-source -->

A closed ring path with `:align :center` centres the string on the
available length; `:overflow :wrap` continues placement from the start
of the path if the string is longer than one revolution.

## Notes

- `:overflow :wrap` only makes sense on closed paths. On an open path
  it behaves like `:truncate` once the end is reached.
- `:overflow :scale` rescales positions, not glyph sizes — the letters
  themselves keep their `:size`, but the spacing between them is
  adjusted so the whole string fits the path.
- Each glyph is oriented by sampling the path at the glyph **centre**
  rather than its start; this gives better-looking orientation on
  curves at the cost of a small offset relative to the path
  parameter.
- The result is a vector of meshes. Use `concat-meshes` or feed it
  directly to a boolean operation if you need a single solid.

## See also

- **Related:** `text-shape`, `extrude-text`, `text-width`,
  `concat-meshes`, `path`, `load-font!`, `font-loaded?`
