---
name: text-shape
category: text
since: ""
status: stable
---

# text-shape

## Signature

`(text-shape text & {:keys [size font curve-segments]})`

## Description

Convert a text string into a vector of 2D shapes ready for extrusion or
boolean operations. Returns a vector of shapes — **one entry per outer
contour**, not per character. Pure function; does not modify turtle
state.

The string is rendered with opentype.js using the default Roboto font (or
a custom one passed via `:font`). Composite glyphs are decomposed into
one shape per outer contour, with counters (e.g. the hole inside `o` or
`a`) attributed to their containing outer:

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

Requires the default font to be loaded. Check with `font-loaded?` and
pre-load with `load-font!` if not.

## Parameters

- `text` — the string to render.
- `:size` (default `10`) — font size in units.
- `:font` (default the loaded Roboto) — opentype.js font object, as
  returned by `load-font!`.
- `:curve-segments` (default `8`) — number of straight segments per
  Bezier curve in the glyph outlines. Increase for smoother letters at
  large sizes.

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
;; Pass a custom font (load-font! returns a promise; resolve before use)
(-> (load-font! :roboto-mono)
    (.then (fn [mono]
             (register code
               (extrude (text-shape "fn ()" :size 20 :font mono) (f 2))))))
```
<!-- /example-source -->

`:font` lets you swap the built-in Roboto for any opentype font loaded
via `load-font!` — built-in keys (`:roboto`, `:roboto-mono`) or a URL.

## Notes

- The returned shapes are positioned in 2D with the string's baseline
  on `y = 0`, starting at `x = 0`. The shapes carry positional metadata
  so `extrude` preserves the relative layout.
- For accurate width measurements before extrusion, use `text-width`.
- If the default font has not loaded yet (e.g. before `init-default-font!`
  completes), `text-shape` returns `nil`. Gate calls with
  `(when (font-loaded?) …)` in startup code.

## See also

- **Related:** `text-shapes`, `char-shape`, `extrude-text`,
  `text-on-path`, `text-width`, `load-font!`, `font-loaded?`, `extrude`
