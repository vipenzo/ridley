---
name: panel
category: registration-visibility
since: ""
status: stable
---

# panel

## Signature

`(panel width height)`
`(panel width height & {:keys [font-size bg fg padding line-height]})`

## Description

Create a 3D text billboard positioned at the current turtle pose. The
panel is rendered as a semi-transparent rectangle that displays text
content set with `out` / `append` / `clear`. Returns a panel value
without modifying turtle state.

Panels behave like meshes for `register`, `show` / `hide`, and `attach`
purposes, but are rendered as a flat billboard rather than as 3D
geometry. Use them for labels, debug output, or UI elements inside the
scene.

## Parameters

- `width`, `height` — panel size, in world units.
- `:font-size` — text size in world units (default depends on panel
  size).
- `:bg` — background colour. Accepts a hex integer including alpha
  (e.g. `0x333333cc`).
- `:fg` — foreground (text) colour. Hex integer.
- `:padding` — inset between the text and the panel edge, in world units.
- `:line-height` — multiplier on the font size for vertical spacing.

## Example

{{example: panel-basic}}

<!-- example-source: panel-basic -->
```clojure
(register label (panel 40 20))
(out :label "Hello World")
```
<!-- /example-source -->

Build a 40 × 20 panel at the current turtle pose, register it, and set
its content. `register` automatically wires the name so `out` /
`append` / `clear` can reach it.

## Variations

{{example: panel-styled}}

<!-- example-source: panel-styled -->
```clojure
(register status
  (panel 40 20
    :font-size 3
    :bg 0x333333cc
    :fg 0xffffff
    :padding 2
    :line-height 1.4))
(out :status "Iteration: 42\nState: running")
```
<!-- /example-source -->

Customise typography and colours. The background uses an `RGBA` hex
when transparency is needed.

## Notes

- Panels respond to `show` / `hide` like meshes.
- For text rendered as 3D geometry (rather than a billboard), see
  `text-shape` (a future batch).

## See also

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `out`, `append`, `clear`, `panel?`, `register`
