---
name: chamfer
category: mesh-operations
since: ""
status: stable
---

# chamfer

## Signature

`(chamfer mesh direction distance & {:keys [angle min-radius where]})`

## Description

Cut a flat bevel of width `distance` along every sharp edge of `mesh`
that points in the given `direction`. Returns a new mesh; the input
is unchanged.

Edge selection works in two passes:

1. Find every interior edge whose dihedral angle exceeds `:angle`
   (default 80°). This isolates the design-significant edges.
2. Keep only those whose adjacent face normals point in the same
   half-space as `direction` (within ~30°). This restricts the
   chamfer to one side of the mesh.

`direction` is a turtle-oriented keyword: `:top`, `:bottom`, `:up`,
`:down`, `:left`, `:right`, or `:all` for no directional filter.
Directions are relative to the mesh's creation-pose (the turtle's
heading/up at the moment the mesh was registered), so a mesh that was
built "up" remains "up" even after later translations or rotations.

| Option        | Default | Description                                                                     |
|---------------|---------|---------------------------------------------------------------------------------|
| `:angle`      | 80      | Minimum dihedral angle (degrees) for an edge to count as sharp.                 |
| `:min-radius` | `nil`   | Exclude edges closer than `r` to the mesh's heading axis (origin-distance test).|
| `:where`      | `nil`   | Extra predicate `(fn [[x y z]] -> bool)` applied to both edge endpoints.        |

## Parameters

- `mesh` — a mesh value or a registered name (keyword).
- `direction` — `:top` / `:bottom` / `:up` / `:down` / `:left` /
  `:right` / `:all`.
- `distance` — bevel width, in turtle units.

## Example

{{example: chamfer-top}}

<!-- example-source: chamfer-top -->
```clojure
(register block
  (-> (box 30 30 15)
      (chamfer :top 2)))
```
<!-- /example-source -->

The four top edges of a block are cut with a 2-unit flat bevel.
Bottom edges and verticals are untouched.

## Variations

{{example: chamfer-all}}

<!-- example-source: chamfer-all -->
```clojure
;; Bevel every sharp edge on a CSG block in one pass
(register beveled
  (-> (mesh-difference (box 40 40 20) (cyl 10 30))
      (chamfer :all 1.5 :angle 60)))
```
<!-- /example-source -->

Lowering `:angle` to 60° picks up shallower CSG creases. `:all` skips
the directional filter, so every qualifying edge gets the bevel.

{{example: chamfer-where}}

<!-- example-source: chamfer-where -->
```clojure
;; Chamfer only the right half of a slab
(register slab
  (-> (box 60 20 8)
      (chamfer :top 1.5
               :where #(> (first %) 0))))
```
<!-- /example-source -->

The `:where` predicate keeps only edges whose vertices both have
positive X — the right-hand side of the slab.

## Notes

- Direction is interpreted relative to the mesh's `:creation-pose`,
  not to world axes. A mesh built sideways (e.g. `(tv 90)` before
  registration) still has `:top` pointing along its construction
  heading.
- `chamfer` works by subtracting a triangular prism along each
  qualifying edge via CSG. Inputs do not need to be manifold — the
  per-edge tools handle non-manifold input gracefully and fall back
  to a sequential cut if the batched strip fails.
- For rounded edges instead of flat bevels, use `fillet`.
- For finer control over which edges to bevel, drop down to
  `find-sharp-edges` + `chamfer-prisms` + `chamfer-edges` and build
  the chamfer pipeline by hand.

## See also

- **Guide:** placeholder → cap. 7 (Mesh operations)
- **Related:** `fillet`, `chamfer-edges`, `chamfer-prisms`,
  `find-sharp-edges`, `mesh-smooth`
