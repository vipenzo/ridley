---
name: list-faces
category: faces
since: ""
status: stable
---

# list-faces

## Signature

`(list-faces mesh)`

## Description

Return every face of `mesh` as a sequence of info maps. Each map has at least
`:id` (the face identifier — a keyword on primitives, an integer on
CSG-derived meshes), `:normal`, `:heading`, and `:center` — all `[x y z]`
vectors. Pure function; does not modify turtle state.

`list-faces` reads the mesh's `:face-groups`. Primitives ship with semantic
groups (`:top`, `:bottom`, `:side`, …); CSG and constructed meshes get them
on demand via `auto-face-groups` / `ensure-face-groups`. If `:face-groups`
is absent and you do not coerce first, `list-faces` returns `nil`.

For just the keys, use `face-ids`. For a single face by id, use `get-face`
or `face-info`.

## Parameters

- `mesh` — a mesh value or a registered name.

## Example

{{example: list-faces-box}}

<!-- example-source: list-faces-box -->
```clojure
(register b (box 20))
(map :id (list-faces :b))
;; => (:top :bottom :front :back :left :right)
```
<!-- /example-source -->

A box has six semantic faces. The returned maps also carry `:normal`,
`:heading`, and `:center` for each one.

## Notes

- The maps returned here are intentionally lightweight. For `:area`,
  `:edges`, and per-vertex positions, call `face-info` on the id.
- On CSG-derived meshes, run `(ensure-face-groups mesh)` first if you want
  numeric face ids; otherwise `list-faces` returns `nil` because there are
  no groups to enumerate.

## See also

- **Related:** `face-ids`, `get-face`, `face-info`, `find-faces`,
  `auto-face-groups`, `ensure-face-groups`, `attach-face`, `clone-face`
