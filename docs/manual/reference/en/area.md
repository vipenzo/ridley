---
name: area
category: faces
since: ""
status: stable
---

# area

## Signature

`(area mesh-or-name face-id)`

## Description

Return the area of one face of a mesh. Pure function; does not modify
turtle state. Returns `nil` if the face cannot be resolved.

`area` is a thin convenience over `(:area (face-info mesh face-id))` —
when you only need the number, it is more direct. Accepts either a
registered mesh name or a mesh value directly.

For ranking faces by size, see `largest-face` (which returns the winning
face's full info map).

## Parameters

- `mesh-or-name` — a mesh value or a registered name.
- `face-id` — a keyword on primitives, or an integer on auto-grouped
  meshes.

## Example

{{example: area-top}}

<!-- example-source: area-top -->
```clojure
(register b (box 30))
(area :b :top)
;; => 900.0
```
<!-- /example-source -->

The top face of a 30-unit box has area 900.

## Notes

- The area is the sum of triangle areas in the face group, computed from
  the actual vertex positions. CSG-derived faces report the area of the
  surviving region.
- For 2D shape area (path / shape, not a mesh face), use the shape-side
  utilities instead.

## See also

- **Related:** `face-info`, `largest-face`, `find-faces`, `distance`,
  `ruler`
