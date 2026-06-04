---
name: mesh?
category: mesh-operations
since: ""
status: stable
---

# mesh?

## Signature

`(mesh? x)`

## Description

Return `true` if `x` is a mesh value — a map containing the
`:vertices` and `:faces` keys typical of a Ridley mesh — and `false`
otherwise. Useful for helper functions that accept either a mesh, a
shape, an SDF, or a name reference and need to dispatch on type.

## Parameters

- `x` — any value.

## Example

{{example: mesh-p-basic}}

<!-- example-source: mesh-p-basic -->
```clojure
(def m (box 10))
(out (str "(mesh? m)         = " (mesh? m)))
(out (str "(mesh? (circle 5)) = " (mesh? (circle 5))))
(out (str "(mesh? :name)     = " (mesh? :name)))
```
<!-- /example-source -->

A simple type test. `circle` returns a 2D shape, not a mesh; a
keyword is not a mesh either (it can REFER to one through the
registry, but the value itself is just a keyword).

## Notes

- `mesh?` checks the value's shape only. It does NOT validate
  manifoldness — for that, see `mesh-diagnose`.
- A registered mesh keyword (e.g. `:tube`) is NOT a mesh value;
  resolve it via the registry first if needed.

## See also

- **Related:** `shape?`, `path?`, `mesh-diagnose`, `info`
