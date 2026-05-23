---
name: get-face
category: faces
since: ""
status: stable
---

# get-face

## Signature

`(get-face mesh face-id)`

## Description

Return basic info for one face of `mesh`, identified by `face-id`. The
result is a map with `:id`, `:normal`, `:heading`, `:center`, and
`:vertices` (vertex indices into the mesh's vertex array). Pure function;
does not modify turtle state.

`get-face` is the per-id counterpart of `list-faces`. Use it when you
already know which face you want and need its position or orientation —
for example, to anchor a transform or place a marker. For richer data
(area, edges, vertex positions), call `face-info`.

If `face-id` is not present in the mesh's `:face-groups`, returns `nil`.

## Parameters

- `mesh` — a mesh value or a registered name.
- `face-id` — a keyword (`:top`, `:bottom`, `:side`, …) on primitives, or
  an integer on auto-grouped CSG results.

## Example

{{example: get-face-top-center}}

<!-- example-source: get-face-top-center -->
```clojure
(register b (box 20))
(:center (get-face :b :top))
;; => [0 10 0]   ; box has its top face centered 10 units up
```
<!-- /example-source -->

The `:center` field is the centroid of the face in mesh coordinates,
suitable for placing markers or anchoring further geometry.

## Notes

- The face id space depends on how `:face-groups` were assigned. Primitives
  use semantic keywords; `auto-face-groups` produces integer ids.
- For full metadata including `:area` and `:edges`, use `face-info`.

## See also

- **Related:** `list-faces`, `face-ids`, `face-info`, `find-faces`,
  `face-at`, `attach-face`, `clone-face`
