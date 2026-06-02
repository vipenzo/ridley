---
name: text-width
category: text
since: ""
status: stable
---

# text-width

## Signature

`(text-width text font size)`

## Description

Return the horizontal extent (in the same units as `size`) that a string
would occupy when rendered with the given font. Pure function; does not
modify turtle state.

`text-width` is the layout primitive for typography: use it to centre
text, size a backing plate to fit a label, or compute manual tracking
between glyphs. The width is the sum of per-glyph **advance widths** at
the requested size — what the text engine itself would use to advance
the cursor — so the result matches the actual rendered layout, not just
the visible glyph extents.

The font argument is positional (not a keyword option), like
`char-shape`. Pass a registered font id (e.g. `:roboto`, `:roboto-mono`,
or any id added in Settings → Fonts), or `nil` for the default
`:roboto`.

## Parameters

- `text` — the string to measure.
- `font` — a registered font id keyword, or `nil` for `:roboto`.
- `size` — font size in units; the result is in the same units.

## Example

{{example: text-width-centre-label}}

<!-- example-source: text-width-centre-label -->
```clojure
;; Centre a label on a backing plate
(let [w (text-width "Hello" :roboto 20)
      plate-w (+ w 20)]
  (register card
    (concat-meshes
      [(box plate-w 30 4)
       (attach (extrude (text-shape "Hello" :size 20) (f 5))
               (lt (* w -0.5)))])))
```
<!-- /example-source -->

`text-width` returns the actual cursor-advance width; offsetting by
half of it places the text centred over the plate.

## Notes

- The width includes trailing advance for the last glyph as the font
  defines it — so two adjacent strings laid out using `text-width`
  spacing will not overlap.
- Spaces contribute their advance width but no visible glyph.

## See also

- **Related:** `text-shape`, `text-shapes`, `extrude-text`, `text-on-path`
