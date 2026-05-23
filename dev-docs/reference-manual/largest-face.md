---
name: largest-face
category: faces
since: ""
status: stable
---

# largest-face

## Signature

`(largest-face mesh)`
`(largest-face mesh direction)`

## Description

Return the face with the largest area, optionally restricted to a
direction relative to the mesh's creation-pose. The result is a face info
map with `:id`, `:normal`, `:heading`, `:center`, and `:area`, or `nil` if
the mesh has no faces. Pure function; does not modify turtle state.

`largest-face` is `find-faces` + `max-key :area` in one call — the
shortcut for the common pattern "pick the dominant face on side X".
Useful on CSG meshes where you want the obvious "top" without worrying
about chamfer offcuts that may also point upward.

Internally calls `ensure-face-groups`, so it works on raw CSG output.

## Parameters

- `mesh` — a mesh value or a registered name.
- `direction` (optional) — `:top`, `:bottom`, `:up`, `:down`, `:left`,
  `:right`, or `:all`. Default `:all` returns the largest face overall.

## Example

{{example: largest-face-default}}

<!-- example-source: largest-face-default -->
```clojure
;; Pick the dominant top face after a CSG cut
(register cut (difference (box 30) (translate (box 10) 0 8 0)))
(:area (largest-face :cut :top))
;; => 900.0   ; the main top face, not the tiny rim around the hole
```
<!-- /example-source -->

Even with a hole cutting into the top, the surviving face (the L-shaped
rim) is the largest in the `:top` direction and wins the selection.

## Notes

- Direction filtering uses the same `:threshold` 0.7 default as
  `find-faces`. For tighter or looser cones, fall back to
  `(apply max-key :area (find-faces mesh dir :threshold 0.9))`.
- Returns `nil` if no face meets the direction filter — for example,
  `(largest-face plane-mesh :up)` on a vertical wall.

## See also

- **Related:** `find-faces`, `face-info`, `area`, `face-at`,
  `face-nearest`, `attach-face`, `clone-face`
