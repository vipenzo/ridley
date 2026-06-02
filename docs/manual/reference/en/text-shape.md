---
name: text-shape
category: text
since: ""
status: stable
---

# text-shape

## Signature

`(text-shape text & {:keys [size font curve-segments center]})`

## Description

Convert a text string into a vector of 2D shapes ready for extrusion or
boolean operations. Returns a vector of shapes — **one entry per outer
contour**, not per character. Pure function; does not modify turtle
state.

The string is rendered with opentype.js using the default font
(`:roboto`) or any other registered id passed via `:font`. Composite
glyphs are decomposed into one shape per outer contour, with counters
(e.g. the hole inside `o` or `a`) attributed to their containing outer:

| Glyph | Outer contours | Shapes emitted |
|-------|----------------|----------------|
| `I`, `O`, `c`, `n`, … | 1 | 1 |
| `i`, `j` (stem + tittle) | 2 | 2 |
| `à`, `è`, `é`, `ì`, `ò`, `ù` (letter + accent) | 2 | 2 |
| `ä`, `ö`, `ü`, `ñ` (letter + diacritics) | 3 | 3 |

The vector can be passed directly to `extrude`, which combines all
shapes into a single mesh with proper hole handling.

For one shape per character without composite handling (no holes,
outer-only), use `text-shapes`. For a single character, use `char-shape`.
For a one-call shortcut that goes straight to a 3D mesh, use
`extrude-text`.

## Parameters

- `text` — the string to render.
- `:size` (default `10`) — font size in units.
- `:font` (default `:roboto`) — keyword id of a registered font.
  Built-in ids are `:roboto` and `:roboto-mono`; custom ids are added
  in Settings → Fonts (desktop). An unregistered id raises an error.
- `:curve-segments` (default `8`) — number of straight segments per
  Bezier curve in the glyph outlines. Increase for smoother letters at
  large sizes.
- `:center` (default `false`) — center the text on the turtle pose. By
  default the pose is a writing baseline: the text starts at the pose
  (origin at bottom-left) and grows up and forward, unlike `rect`/`circle`
  which spawn centered on the pose. With `:center true` the **combined ink
  bounding box** of all glyphs is centered on the pose on **both axes**, so
  the text behaves like a centered primitive — convenient to align it to
  another piece or rotate it about its own center. Centering uses the actual
  ink box (not the advance width), so leading/trailing spaces do not shift
  the center.

## Example

{{example: text-shape-extrude-title}}

<!-- example-source: text-shape-extrude-title -->
```clojure
;; Standard pattern: text-shape + extrude → single mesh
(register title (extrude (text-shape "RIDLEY" :size 40) (f 5)))
```
<!-- /example-source -->

`extrude` accepts the vector returned by `text-shape` and merges the
per-glyph extrusions into one mesh — counters become holes
automatically.

## Variations

{{example: text-shape-custom-font}}

<!-- example-source: text-shape-custom-font -->
```clojure
;; Pass a registered font id — synchronous lookup, no async wait
(register code
  (extrude (text-shape "fn ()" :size 20 :font :roboto-mono) (f 2)))
```
<!-- /example-source -->

`:font` swaps the default Roboto for any registered id. Custom ids are
registered in Settings → Fonts and persist across sessions.

{{example: text-shape-center}}

<!-- example-source: text-shape-center -->
```clojure
;; :center true makes the text sit centered on the turtle pose, so it
;; rotates about its own middle instead of swinging around the baseline
(register badge
  (extrude (text-shape "RIDLEY" :size 30 :center true) (f 4)))
```
<!-- /example-source -->

Without `:center`, the same call would extrude the text growing up and
forward from the pose (baseline at the origin).

## Notes

- The returned shapes are positioned in 2D with the string's baseline
  on `y = 0`, starting at `x = 0` (unless `:center true`, which recenters
  the whole string's ink box on the origin). The shapes carry positional
  metadata so `extrude` preserves the relative layout.
- For accurate width measurements before extrusion, use `text-width`.
- An unregistered `:font` id raises a deterministic error pointing at
  Settings → Fonts. Built-ins (`:roboto`, `:roboto-mono`) are always
  available; custom ids must be registered first.

## See also

- **Related:** `text-shapes`, `char-shape`, `extrude-text`,
  `text-on-path`, `text-width`, `extrude`
