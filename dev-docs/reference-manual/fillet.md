---
name: fillet
category: mesh-operations
since: ""
status: stable
---

# fillet

## Signature

`(fillet mesh direction radius & {:keys [angle min-radius segments where blend-vertices]})`

## Description

Round every sharp edge of `mesh` that points in the given `direction`
with a fillet of the given `radius`. The API mirrors `chamfer`: same
edge selection, same direction keywords, but each qualifying edge is
cut by a concave cylindrical cutter (a quarter-pipe minus its sharp
inside) instead of a flat prism.

Returns a new mesh.

| Option            | Default | Description                                                                |
|-------------------|---------|----------------------------------------------------------------------------|
| `:angle`          | 80      | Minimum dihedral angle (degrees) to count as sharp.                        |
| `:min-radius`     | `nil`   | Exclude edges closer than `r` to the mesh's heading axis.                  |
| `:segments`       | 8       | Arc resolution. 8 is sufficient for `radius` < ~5; bump up for larger.     |
| `:where`          | `nil`   | Extra predicate `(fn [[x y z]] -> bool)` on edge endpoints.                |
| `:blend-vertices` | `false` | When true, add a spherical blend at corners where 3+ filleted edges meet. |

## Parameters

- `mesh` — a mesh value or a registered name.
- `direction` — `:top` / `:bottom` / `:up` / `:down` / `:left` /
  `:right` / `:all`.
- `radius` — fillet radius, in turtle units.

## Example

{{example: fillet-basic}}

<!-- example-source: fillet-basic -->
```clojure
(register card
  (-> (box 30 20 4)
      (fillet :top 1.5 :segments 8)))
```
<!-- /example-source -->

The four top edges of a card-shaped block are rounded with a 1.5-unit
fillet. `:segments 8` gives a smooth arc.

## Variations

{{example: fillet-blend-vertices}}

<!-- example-source: fillet-blend-vertices -->
```clojure
;; Fillet every edge of a cube, including spherical corner blends
(register stone
  (-> (box 20)
      (fillet :all 2 :segments 8 :blend-vertices true)))
```
<!-- /example-source -->

A box with every edge rounded becomes a bar of soap. `:blend-vertices`
adds a spherical cap at each corner so three meeting fillets join
smoothly rather than tangentially.

{{example: fillet-min-radius}}

<!-- example-source: fillet-min-radius -->
```clojure
;; Round only the outer edges of a flange — leave the central bore sharp
(register flange
  (-> (mesh-difference (cyl 25 6) (cyl 10 20))
      (fillet :all 1 :min-radius 12)))
```
<!-- /example-source -->

`:min-radius` excludes the inner bore edges (closer than 12 units to
the mesh's axis), so only the outer rim is filleted.

## Notes

- Direction is relative to `:creation-pose`, just like `chamfer`.
- `fillet` works on non-manifold input. Internally it subtracts a
  concave cutter per edge, so a single broken edge does not poison
  the whole result.
- `:blend-vertices` is the path to "no flat spots at corners" — at
  the cost of one extra sphere-cut per multi-edge vertex.
- For wholesale rounding of every shallow crease at once (with the
  manifold constraint and quadratic densification), use
  `mesh-smooth`. For flat bevels, use `chamfer`.

## See also

- **Guide:** placeholder → cap. 7 (Mesh operations)
- **Related:** `chamfer`, `mesh-smooth`, `chamfer-edges`,
  `chamfer-prisms`, `find-sharp-edges`
