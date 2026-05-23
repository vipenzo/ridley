---
name: text-shapes
category: text
since: ""
status: stable
---

# text-shapes

## Signature

`(text-shapes text & {:keys [size font curve-segments]})`

## Description

Convert a text string into a vector of 2D shapes — **one shape per
character**, using only the largest outer contour of each glyph. Pure
function; does not modify turtle state.

`text-shapes` is the per-character variant of `text-shape`. It is useful
when you want to animate, distribute, or transform letters
independently. The trade-off is fidelity: composite glyphs (lowercase
`i`, accented vowels, umlauts) lose their secondary contours, and
counters (holes inside `o`, `B`, …) are dropped.

For full glyph fidelity with holes and composite parts, use
`text-shape`. For a single character, use `char-shape`.

Requires the default font to be loaded (see `font-loaded?`,
`load-font!`).

## Parameters

- `text` — the string to render.
- `:size` (default `10`) — font size in units.
- `:font` (default the loaded Roboto) — opentype.js font object.
- `:curve-segments` (default `8`) — segments per Bezier curve.

## Example

{{example: text-shapes-per-letter}}

<!-- example-source: text-shapes-per-letter -->
```clojure
;; One shape per character — extrude each independently
(def letters (text-shapes "ABC" :size 20))
(count letters)
;; => 3
```
<!-- /example-source -->

Each character becomes its own shape with its own position; iterate to
extrude, transform, or animate them per-letter.

## Variations

{{example: text-shapes-staggered-depth}}

<!-- example-source: text-shapes-staggered-depth -->
```clojure
;; Per-letter extrusion depth (3D word with stepped letters)
(register staggered
  (concat-meshes
    (map-indexed
      (fn [i s] (extrude s (f (+ 2 (* 2 i)))))
      (text-shapes "ABC" :size 20))))
```
<!-- /example-source -->

Each letter is extruded with a different depth, then concatenated. Not
possible with `text-shape`, which fuses the glyph layout into a single
extrusion call.

## Notes

- Holes (counters inside `o`, `B`, `D`, `0`, …) are not preserved —
  each letter is rendered solid. If you need accurate counters, switch
  to `text-shape`.
- Composite glyphs lose accents and diacritics. `text-shapes "à"`
  produces one shape for the `a` body; the grave accent is dropped.
- Spaces in the input produce no shape (skipped), so the result vector
  may be shorter than the input string.

## See also

- **Related:** `text-shape`, `char-shape`, `extrude-text`,
  `text-width`, `load-font!`, `font-loaded?`
