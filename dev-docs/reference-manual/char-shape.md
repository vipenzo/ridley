---
name: char-shape
category: text
since: ""
status: stable
---

# char-shape

## Signature

`(char-shape char font size & {:keys [curve-segments]})`

## Description

Convert a single character into a 2D shape using the glyph's largest
outer contour. Returns one shape, or `nil` if the glyph has no
contours. Pure function; does not modify turtle state.

Unlike `text-shape` and `text-shapes`, `char-shape` takes its font
**positionally** (not as a keyword option). Pass a registered font id
keyword (`:roboto`, `:roboto-mono`, or any custom id from Settings →
Fonts), or `nil` for the default `:roboto`.

`char-shape` is the lowest-level entry into glyph extraction. Most user
code wants the higher-level functions:

- `text-shape` — full string with composite/holes handling.
- `text-shapes` — vector, one per character (also outer-only).
- `extrude-text` — one-call shortcut to a 3D mesh.

Use `char-shape` directly when you build your own layout logic per
character (custom kerning, manual placement, animated typography).

## Parameters

- `char` — a single-character string, e.g. `"A"`.
- `font` — a registered font id keyword, or `nil` for `:roboto`.
- `size` — font size in units.
- `:curve-segments` (default `8`) — segments per Bezier curve in the
  glyph outline.

## Example

{{example: char-shape-extrude-single}}

<!-- example-source: char-shape-extrude-single -->
```clojure
;; Synchronous: pass the font id directly
(register letter (extrude (char-shape "R" :roboto 30) (f 5)))
```
<!-- /example-source -->

The character `R` becomes a 2D shape, then a 3D mesh. The same code path
that `text-shape` uses internally, exposed for direct control.

## Notes

- Returns only the **largest** outer contour — accents, dots on
  lowercase `i`/`j`, and counters inside `o`/`B` are dropped. For
  full-fidelity glyphs use `text-shape`.
- Spaces and other characters with no contours return `nil`.
- The shape is positioned with its baseline on `y = 0` and its left
  side near `x = 0`.

## See also

- **Related:** `text-shape`, `text-shapes`, `extrude-text`
