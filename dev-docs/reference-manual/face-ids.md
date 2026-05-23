---
name: face-ids
category: faces
since: ""
status: stable
---

# face-ids

## Signature

`(face-ids mesh)`

## Description

Return the sequence of face identifiers carried by `mesh`. On primitives the
ids are the semantic keywords (`:top`, `:bottom`, `:side`, …); on meshes
that have gone through `auto-face-groups` the ids are integers, one per
coplanar group. Pure function; does not modify turtle state.

`face-ids` is the cheap variant of `list-faces` — useful when you only need
to iterate or check membership and do not care about normals, centers, or
areas.

If the mesh has no `:face-groups` (raw CSG result, custom mesh), `face-ids`
returns `nil`. Run `ensure-face-groups` first if you need groups computed
on demand.

## Parameters

- `mesh` — a mesh value or a registered name.

## Example

{{example: face-ids-box}}

<!-- example-source: face-ids-box -->
```clojure
(register b (box 20))
(face-ids :b)
;; => (:top :bottom :front :back :left :right)
```
<!-- /example-source -->

A box exposes the six standard semantic face ids.

## Notes

- For complete face metadata (normal, center, area, edges), use
  `face-info`; for a single basic entry, use `get-face`.
- The order of the returned ids follows the mesh's `:face-groups` map and
  is implementation-defined; do not rely on it for sorted output.

## See also

- **Related:** `list-faces`, `get-face`, `face-info`,
  `auto-face-groups`, `ensure-face-groups`
