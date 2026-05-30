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

Convert a text string directly into a 3D mesh at the current turtle
pose. Glyphs flow along the turtle's heading and extrude along its up
axis. Returns a single mesh combining all glyphs — or `nil` if the
text is empty. The mesh is also added to the current turtle's mesh
list, like any other constructor.

`extrude-text` is the one-call shortcut for the most common pattern:
`text-shape` + `extrude`. The two differ in glyph fidelity: for full
fidelity on composite glyphs (`i`, `j`, accented vowels with diacritics)
prefer `(extrude (text-shape …) …)`. For text along a curved path,
see `text-on-path`.

## Parameters

- `text` — the string to render.
- `:size` (default `10`) — font size in units.
- `:depth` (default `5`) — extrusion depth along the turtle's up axis.
- `:font` (default `:roboto`) — keyword id of a registered font.
  Built-in ids are `:roboto` and `:roboto-mono`; custom ids are added
  in Settings → Fonts (desktop). Unregistered ids raise an error.

## Example

{{example: extrude-text-default}}

<!-- example-source: extrude-text-default -->
```clojure
;; Defaults — :size 10 :depth 5, axes follow the current turtle pose
(register label (extrude-text "RIDLEY"))
```
<!-- /example-source -->

The string is laid out along the turtle's heading and extruded along
its up axis. The result is a single mesh containing all glyphs.

## Variations

{{example: extrude-text-sized-thin}}

<!-- example-source: extrude-text-sized-thin -->
```clojure
;; Larger, thinner glyphs used directly as a boolean cutter
(register plate
  (difference (box 200 60 8)
              (extrude-text "RIDLEY" :size 40 :depth 10)))
```
<!-- /example-source -->

The mesh can be fed directly to boolean ops — common pattern for
engraved labels.

## Notes

- Returns a single fused mesh. Internally each glyph is extruded
  independently with the correct outer + holes, then the per-glyph
  meshes are concatenated (via `concat-meshes`) before being returned.
- Counters inside single-outline glyphs (the holes in `O`, `R`, `B`,
  `D`, `0`, …) **are** preserved.
- Composite glyphs with **multiple outer contours** (lowercase `i`/`j`,
  accented vowels like `à`/`ä`, characters with diacritics) are
  imperfect: only the largest outer is used as the silhouette, and the
  secondary outers (tittle, accent, umlaut dots) become spurious
  hole-style cuts on the main shape. For full composite-glyph fidelity
  use `(extrude (text-shape …) …)`, which decomposes composites into
  one shape per outer before extruding.
- The returned mesh is also pushed onto the active turtle state, so
  the call mutates the current turtle.

## See also

- **Related:** `text-shape`, `text-shapes`, `text-on-path`,
  `concat-meshes`, `extrude`
