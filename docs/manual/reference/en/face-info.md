---
name: face-info
category: faces
since: ""
status: stable
---

# face-info

## Signature

`(face-info mesh face-id)`

## Description

Return detailed metadata for one face of `mesh`, identified by `face-id`.
The result is a map with `:id`, `:normal`, `:heading`, `:center`,
`:vertices` (indices), `:vertex-positions` (resolved `[x y z]` points),
`:area`, `:edges` (unique `[v0 v1]` pairs into the vertex array), and
`:triangles`. Pure function; does not modify turtle state.

`face-info` is the heavy-data sibling of `get-face`: use it when you need
the face's area for sorting/filtering, its edges for building cutters, or
its vertex positions for geometric construction. For lighter queries
(centroid, normal), `get-face` is cheaper.

If `face-id` is not present in `:face-groups`, returns `nil`.

## Parameters

- `mesh` — a mesh value or a registered name.
- `face-id` — a keyword on primitives, or an integer on auto-grouped meshes.

## Example

{{example: face-info-area}}

<!-- example-source: face-info-area -->
```clojure
(register b (box 30))
(:area (face-info :b :top))
;; => 900.0   ; a 30×30 face has area 900
```
<!-- /example-source -->

`:area` is the sum of triangle areas in the face, useful for selecting the
largest face or scoring candidates in `find-faces` predicates.

## Variations

{{example: face-info-vertex-positions}}

<!-- example-source: face-info-vertex-positions -->
```clojure
;; Use vertex-positions to drive geometry from a face's actual corners
(register b (box 20))
(def corners (:vertex-positions (face-info :b :top)))
(count corners)
;; => 4
```
<!-- /example-source -->

`:vertex-positions` resolves the vertex indices to absolute points in mesh
coordinates — handy for anchoring secondary geometry without computing the
positions yourself.

## Notes

- `:edges` contains unique undirected edges as ordered index pairs
  `[v0 v1]` with `v0 < v1`. Each appears once, regardless of how many
  triangles share it.
- `:area` is always non-negative.
- `:heading` is the in-plane direction inherited from the mesh's
  creation-pose, projected perpendicular to the face normal. It is what
  `attach-face` / `clone-face` use as the local +X.

## See also

- **Related:** `get-face`, `list-faces`, `find-faces`, `largest-face`,
  `area`, `face-shape`, `attach-face`, `clone-face`
