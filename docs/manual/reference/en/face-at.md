---
name: face-at
category: faces
since: ""
status: stable
---

# face-at

## Signature

`(face-at mesh point)`

## Description

Return the single face whose plane passes closest to `point`. The result is
a face info map with `:id`, `:normal`, `:heading`, `:center`, and `:area`,
or `nil` if the mesh has no faces. Pure function; does not modify turtle
state.

"Closest plane" is measured along the face normal: for each face the
signed distance from `point` to the face plane is taken, and the face with
the smallest absolute value wins. This is what you want when picking a
face based on a probe point that is *near* the surface — for example a
click in 3D, or an anchor placed manually.

For nearest-by-centroid (a different question — relevant when the point is
deep inside or far away), use `face-nearest`. For nearest by id rather
than by position, use `get-face`.

Internally calls `ensure-face-groups`, so it works on raw CSG output.

## Parameters

- `mesh` — a mesh value or a registered name.
- `point` — `[x y z]` in mesh coordinates.

## Example

{{example: face-at-pick-top}}

<!-- example-source: face-at-pick-top -->
```clojure
(register b (box 20))
;; Probe a point just above the top face
(:id (face-at :b [0 11 0]))
;; => :top
```
<!-- /example-source -->

The point `[0 11 0]` is just outside the top face (whose plane is at
`y = 10`). `face-at` returns the matching face.

## Notes

- `face-at` measures distance to the **plane** of each face, not to the
  face polygon itself. A point off-axis can still pick a face whose
  polygon does not contain its projection — if you need polygon-precise
  picking, project the point and check containment yourself.
- For deep-interior points or coarse pointing, `face-nearest` (centroid
  distance) is often what users actually want; `face-at` shines for
  probes close to the surface.

## See also

- **Related:** `face-nearest`, `find-faces`, `largest-face`, `face-info`,
  `attach-face`, `clone-face`
