---
name: out
category: registration-visibility
since: ""
status: stable
---

# out / append / clear

## Signature

`(out panel-or-name content)`
`(append panel-or-name content)`
`(clear panel-or-name)`

## Description

Manipulate the text content of a registered panel.

- **`(out … content)`** — replace the panel's content with `content`.
- **`(append … content)`** — append `content` to the existing text.
- **`(clear …)`** — empty the panel.

The first argument may be a registered keyword name or the panel value
itself. The newline character `\n` is honoured for multi-line text.

None of these calls modify turtle state.

## Parameters

- `panel-or-name` — registered keyword (e.g. `:label`) or a panel value.
- `content` — string. May contain newlines.

## Example

{{example: panel-io-basic}}

<!-- example-source: panel-io-basic -->
```clojure
(register label (panel 40 20))
(out :label "Hello World")
(append :label "\nLine 2")
(clear :label)
```
<!-- /example-source -->

Set, extend, and clear a panel's content by registered name.

## Notes

- These functions only work on panels — they have no effect on meshes
  or other registered values.
- `out` is the most common form; `append` is useful for running logs;
  `clear` is convenient before a new render pass.

## See also

- **Related:** `panel`, `panel?`, `register`
