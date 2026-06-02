---
name: find-faces
category: faces
since: ""
status: stable
---

# find-faces

## Signature

`(find-faces mesh direction & {:keys [threshold where]})`

## Description

Return all faces of `mesh` whose normal aligns with a direction relative to
the mesh's creation-pose. The result is a vector of face info maps with
`:id`, `:normal`, `:heading`, `:center`, and `:area`. Pure function; does
not modify turtle state.

`find-faces` is the geometric counterpart to `get-face`. It lets you pick
faces by orientation rather than by id — essential on CSG-derived meshes
where ids are integers without semantic meaning. Internally calls
`ensure-face-groups`, so it works on raw CSG output without preparation.

Directions are interpreted in the mesh's local frame (its creation-pose),
not in world coordinates: `:top` always means "along the heading" even
after the mesh is rotated.

## Parameters

- `mesh` — a mesh value or a registered name.
- `direction` — `:top`, `:bottom`, `:up`, `:down`, `:left`, `:right`, or
  `:all`. `:top`/`:bottom` follow the heading axis; `:up`/`:down` follow
  the up axis; `:left`/`:right` follow heading × up.
- `:threshold` (option, default `0.7`) — cosine alignment cutoff. `0.7`
  accepts faces within ~45° of the direction; raise to `0.9`+ for stricter
  alignment, lower to widen the cone.
- `:where` (option) — predicate `(fn [face-info] -> boolean)` applied
  after direction filtering. Use it to filter by `:area`, position, etc.

## Example

{{example: find-faces-csg-top}}

<!-- example-source: find-faces-csg-top -->
```clojure
;; A CSG result has integer face ids, but we can still pick "top" faces
(register cut (difference (box 30) (translate (box 20) 0 8 0)))
(map :id (find-faces :cut :top))
;; => (3 7)   ; the two roughly-up-facing faces from the boolean
```
<!-- /example-source -->

CSG strips the semantic keywords, but `find-faces :top` still picks the
faces oriented along the original heading.

## Variations

{{example: find-faces-with-area}}

<!-- example-source: find-faces-with-area -->
```clojure
;; Only large up-facing faces (filter via :where)
(register cut (difference (box 30) (translate (box 20) 0 8 0)))
(map :id
     (find-faces :cut :top
                 :threshold 0.9
                 :where #(> (:area %) 100)))
```
<!-- /example-source -->

Tighten `:threshold` to demand near-perfect alignment, then narrow further
with `:where`. Common pattern: drop tiny chamfer faces left over from a
boolean cut.

## Notes

- The cosine threshold compares the face's normal against the direction
  vector. `1.0` is exact alignment; `0.0` is perpendicular. Default `0.7`
  ≈ 45° tolerance.
- For a single best face by direction, use `largest-face` with the same
  keyword — it picks the largest of the candidates `find-faces` would
  return.
- The returned info maps already include `:area`, so you do not need a
  separate `face-info` call inside the `:where` predicate.

## See also

- **Related:** `face-at`, `face-nearest`, `largest-face`, `face-info`,
  `list-faces`, `auto-face-groups`, `attach-face`, `clone-face`
