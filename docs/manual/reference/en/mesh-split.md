---
name: mesh-split
category: mesh-operations
since: ""
status: stable
---

# mesh-split

## Signature

`(mesh-split mesh)`

## Description

Split a mesh with the plane defined by the turtle's current pose —
point = position, normal = heading — into two halves. Returns
`{:behind <mesh> :ahead <mesh>}`.

`:behind` is the half **behind** the heading — the side the turtle
came from. This is the same convention as `sdf-half-space`, on
purpose: after `extrude` the turtle ends on the far face of the new
solid with the material behind it, so calling `mesh-split` at that
pose puts the material in `:behind`. `:ahead` is the opposite half.
One truth about which side is which, shared by `sdf-half-space` and
`mesh-split` across the whole system.

Either half may come back as an empty mesh (`:vertices []`,
`:faces []`) when the plane misses the piece entirely, or only grazes
it — that is a legitimate result, not an error.

Both halves inherit the source mesh's `:creation-pose`, `:material`
and `:anchors` — the same single-source policy `mesh-hull`/`solidify`
already use for a single-input operation.

`mesh-split` accepts a mesh map, a keyword (registered mesh name), or
an SDF node (auto-materialized).

## Parameters

- `mesh` — the mesh (or keyword name, or SDF node) to split. The cut
  plane itself has no argument — it is always the turtle's current
  pose.

## Example

{{example: mesh-split-basic}}

<!-- example-source: mesh-split-basic -->
```clojure
(register block (extrude (rect 20 20) (f 20)))
(f 10)
(def halves (mesh-split (get-mesh :block)))
(register :left (:behind halves))
```
<!-- /example-source -->

Moving the turtle halfway into the block (`(f 10)`) before calling
`mesh-split` cuts it in two at that plane; `:behind` is the half
between the origin and the cut.

## Variations

{{example: mesh-split-degenerate}}

<!-- example-source: mesh-split-degenerate -->
```clojure
(register block (extrude (rect 10 10) (f 10)))
(f 100)
(def halves (mesh-split (get-mesh :block)))
;; the plane is far past the block — :ahead is empty, :behind is
;; the whole block
```
<!-- /example-source -->

A cut plane that doesn't touch the mesh is a normal outcome, not an
error — one of the two halves comes back empty.

## Notes

- The `:behind`/`:ahead` mapping is decided in exactly one place
  (the underlying `split-by-plane` wrapper) and consumed as-is
  everywhere else — never re-derived.
- `mesh-split` results do not currently carry AI-describe/history
  provenance the way `mesh-union`/`mesh-hull` do (it returns a map of
  two meshes rather than a single mesh, which doesn't fit that
  single-mesh tracking).
- For a convexity check on either half (e.g. before treating a piece
  as final), see `convex?`.

## See also

- **Related:** `sdf-half-space`, `slice-mesh`, `mesh-diagnose`, `convex?`
