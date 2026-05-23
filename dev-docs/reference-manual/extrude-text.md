---
name: extrude-text
category: text
since: ""
status: stable
---

# extrude-text

## Signature

`(extrude-text text & {:keys [size depth font]})`

## Description

Convert a text string directly into 3D meshes, one per character, at the
current turtle pose. Glyphs flow along the turtle's heading and extrude
along its up axis. Returns **a vector of meshes** (not a single mesh).
Modifies turtle state: each emitted mesh is added to the current
turtle's mesh list as it would be with any other constructor.

`extrude-text` is the one-call shortcut for the most common pattern:
`text-shape` + `extrude`. The main difference is that the result is a
vector of meshes (one per character) rather than a single fused mesh —
pass it through `concat-meshes` or use it as input to a boolean
operation if you need a single solid.

For full glyph fidelity (counters as holes, accents preserved) when you
do want a single mesh, prefer `(extrude (text-shape …) …)`. For text
along a curved path, see `text-on-path`.

## Parameters

- `text` — the string to render.
- `:size` (default `10`) — font size in units.
- `:depth` (default `5`) — extrusion depth along the turtle's up axis.
- `:font` (default the loaded Roboto) — opentype.js font object.

## Example

{{example: extrude-text-default}}

<!-- example-source: extrude-text-default -->
```clojure
;; Defaults — :size 10 :depth 5, axes follow the current turtle pose
(register label (extrude-text "RIDLEY"))
```
<!-- /example-source -->

The string is laid out along the turtle's heading and extruded along its
up axis. The result is a vector of six meshes (one per character),
all written to the turtle's mesh list.

## Variations

{{example: extrude-text-sized-thin}}

<!-- example-source: extrude-text-sized-thin -->
```clojure
;; Larger, thinner glyphs combined into a single solid for a boolean cut
(register plate
  (difference (box 200 60 8)
              (concat-meshes (extrude-text "RIDLEY" :size 40 :depth 10))))
```
<!-- /example-source -->

Wrap the result in `concat-meshes` to fuse the per-character meshes
before using it as a boolean cutter — common pattern for engraved
labels.

## Notes

- The return value is a **vector of meshes**. If you need a single
  combined mesh, pass it through `concat-meshes` or feed it directly
  to a downstream boolean operation that accepts a sequence.
- Counters inside single-outline glyphs (the holes in `O`, `R`, `B`,
  `D`, `0`, …) **are** preserved: each glyph is extruded with its
  largest outer plus the remaining contours as holes.
- Composite glyphs with **multiple outer contours** (lowercase `i`/`j`,
  accented vowels like `à`/`ä`, characters with diacritics) are
  imperfect: only the largest outer is used as the silhouette, and the
  secondary outers (tittle, accent, umlaut dots) become spurious
  hole-style cuts on the main shape. For full composite-glyph fidelity
  use `(extrude (text-shape …) …)`, which decomposes composites into
  one shape per outer before extruding.
- Each emitted mesh is also pushed onto the active turtle state, so
  the call mutates the current turtle.

## See also

- **Related:** `text-shape`, `text-shapes`, `text-on-path`,
  `concat-meshes`, `extrude`, `load-font!`, `font-loaded?`
