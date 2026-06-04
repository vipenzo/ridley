---
name: mesh
category: registration-visibility
since: ""
status: stable
---

# mesh

## Signature

`(mesh name-or-ref)`

## Description

Return the raw mesh value associated with a registered name. When given
a mesh value already, returns it unchanged (identity-like behaviour).
Useful inside boolean / transform pipelines that need the underlying
geometry rather than the registry key. Does not modify turtle state.

## Parameters

- `name-or-ref` — a registered name (keyword / string / symbol) or a
  mesh value.

## Example

{{example: mesh-basic}}

<!-- example-source: mesh-basic -->
```clojure
(register a (box 20))
(register b (do (f 15) (sphere 12)))
(register fused (mesh-union (mesh :a) (mesh :b)))
```
<!-- /example-source -->

Use `mesh` to pull two registered values into a boolean operation
without re-evaluating the original expressions.

## Notes

- `(mesh x)` where `x` is already a mesh map returns `x` unchanged —
  convenient when writing helpers that accept either a name or a value.
- For abstract registry entries (paths, shapes), use them directly by
  symbol; `mesh` is for mesh-typed entries.

## See also

- **Related:** `register`, `info`, `bounds`, `mesh-union`,
  `mesh-difference`
