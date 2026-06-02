---
name: line-visibility
category: registration-visibility
since: ""
status: stable
---

# show-lines / hide-lines / lines-visible?

## Signature

`(show-lines)`
`(hide-lines)`
`(lines-visible?)`

## Description

Toggle the global visibility of construction lines (the turtle's pen
traces and path previews). All three are nullary. `show-lines` and
`hide-lines` affect the viewport state; `lines-visible?` returns the
current visibility as a boolean. Does not modify turtle state.

The desktop toolbar also exposes a button for the same setting.

## Parameters

None.

## Example

{{example: line-visibility-basic}}

<!-- example-source: line-visibility-basic -->
```clojure
(f 20) (th 45) (f 20)    ;; draws construction lines
(hide-lines)             ;; remove them from view
(lines-visible?)         ;; => false
(show-lines)             ;; bring them back
```
<!-- /example-source -->

Useful when rendering a clean final scene without the turtle's pen
traces.

## Notes

- The setting is global and affects every line in the viewport at once.
- Re-evaluating the script keeps the current visibility state.

## See also

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `pen`, `show`, `hide`, `stamp-visibility`
