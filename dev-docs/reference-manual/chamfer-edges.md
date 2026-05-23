---
name: chamfer-edges
category: mesh-operations
since: ""
status: stable
---

# chamfer-edges

## Signature

`(chamfer-edges mesh distance & {:keys [angle where]})`

## Description

Low-level chamfer primitive. Detects every interior edge of `mesh`
with dihedral angle above `:angle`, builds a triangular prism along
each, and subtracts the prisms from the mesh via CSG. Returns a new
mesh.

`chamfer-edges` is the value-level entry point used internally by
`(chamfer mesh direction distance)`. Use it when:

- You want to bevel every sharp edge without applying a direction
  filter — `chamfer` with `:all` does this too, but `chamfer-edges`
  skips the directional projection entirely.
- You need access to the raw edge filter via `:where`, without the
  `:min-radius` shorthand.
- You are scripting a custom pipeline and want to control the angle
  threshold directly.

| Option   | Default | Description                                                              |
|----------|---------|--------------------------------------------------------------------------|
| `:angle` | 80      | Minimum dihedral angle (degrees) for an edge to count as sharp.          |
| `:where` | `nil`   | Predicate `(fn [[x y z]] -> bool)` applied to BOTH endpoints of an edge. |

## Parameters

- `mesh` — a mesh value.
- `distance` — bevel width, in turtle units.

## Example

{{example: chamfer-edges-basic}}

<!-- example-source: chamfer-edges-basic -->
```clojure
(register cube
  (-> (box 20)
      (chamfer-edges 1.5)))
```
<!-- /example-source -->

Every edge of the cube is beveled with a 1.5-unit flat chamfer.
Equivalent to `(chamfer cube :all 1.5)` with the default angle
threshold.

## Variations

{{example: chamfer-edges-filtered}}

<!-- example-source: chamfer-edges-filtered -->
```clojure
;; Bevel edges only on the right side (X > 0)
(register half-beveled
  (-> (box 30 20 10)
      (chamfer-edges 1 :angle 70 :where #(> (first %) 0))))
```
<!-- /example-source -->

`:angle 70` picks up slightly shallower edges; `:where` keeps the
chamfer on the right half of the box.

## Notes

- Inputs do not need to be manifold; per-edge prisms handle
  non-manifold input gracefully.
- The `:where` predicate must accept BOTH endpoints. To keep edges
  that cross a boundary, write a looser predicate (e.g. `or` the
  inside test with a small tolerance).
- For direction-driven selection (`:top`, `:bottom`, etc.), use
  `chamfer` — it wraps `chamfer-edges` with the direction filter.
- To inspect the cutting prisms before applying them, use
  `chamfer-prisms`.

## See also

- **Guide:** placeholder → cap. 7 (Mesh operations)
- **Related:** `chamfer`, `fillet`, `chamfer-prisms`,
  `find-sharp-edges`
