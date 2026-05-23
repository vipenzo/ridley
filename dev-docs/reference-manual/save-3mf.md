---
name: save-3mf
category: export
since: ""
status: stable
---

# save-3mf

## Signature

`(save-3mf mesh)`
`(save-3mf mesh "suggested-name")`

## Description

Convenience wrapper around `save-mesh` that pins the suggested extension to `.3mf`. See `save-mesh` for the full picker semantics; see `export` for multi-material 3MF behaviour when meshes carry a `color`.

## Example

{{example: save-3mf-basic}}

<!-- example-source: save-3mf-basic -->
```clojure
(save-3mf (sphere 15) "ball.3mf")
;; Native picker pre-fills "ball.3mf".
```
<!-- /example-source -->

## See also

- **Related:** `save-mesh`, `export`, `color`
