---
name: save-stl
category: export
since: ""
status: stable
---

# save-stl

## Signature

`(save-stl mesh)`
`(save-stl mesh "suggested-name")`

## Description

Convenience wrapper around `save-mesh` that pins the suggested extension to `.stl`. See `save-mesh` for the full picker semantics, default filenames, and notes on how the typed extension overrides the suggestion.

## Example

{{example: save-stl-basic}}

<!-- example-source: save-stl-basic -->
```clojure
(save-stl (mesh-difference (box 30) (cyl 6 35)) "bracket.stl")
;; Native picker pre-fills "bracket.stl".
```
<!-- /example-source -->

## See also

- **Related:** `save-mesh`, `export`
