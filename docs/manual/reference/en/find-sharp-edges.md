---
name: find-sharp-edges
category: mesh-operations
since: ""
status: stable
---

# find-sharp-edges

## Signature

`(find-sharp-edges mesh & {:keys [angle where]})`

## Description

Inspect a mesh and return every interior edge whose adjacent triangles
meet at a dihedral angle steeper than `:angle`. The output is a vector
of edge descriptors, one per qualifying edge:

```
{:edge      [v0 v1]
 :positions [p0 p1]
 :angle     <degrees>
 :midpoint  [x y z]
 :normals   [n1 n2]}
```

Useful for previewing where `chamfer` or `fillet` would act, for
building custom edge-driven operations, or as a feed for the
lower-level `chamfer-edges` / `chamfer-prisms` primitives — they
accept the same descriptor format.

| Option   | Default | Description                                                                |
|----------|---------|----------------------------------------------------------------------------|
| `:angle` | 30      | Minimum dihedral angle (degrees) to count as sharp.                        |
| `:where` | `nil`   | Predicate `(fn [[x y z]] -> bool)` applied to BOTH endpoints of an edge.   |

## Parameters

- `mesh` — a mesh (manifold or not — only triangle topology matters).
- options keyword args as in the table above.

## Example

{{example: find-sharp-edges-basic}}

<!-- example-source: find-sharp-edges-basic -->
```clojure
(register block (box 20))
(let [es (find-sharp-edges block)]
  (out (str "found " (count es) " sharp edges")))
```
<!-- /example-source -->

A cube has 12 sharp edges at 90°. With the default `:angle 30`, all
12 qualify.

## Variations

{{example: find-sharp-edges-filtered}}

<!-- example-source: find-sharp-edges-filtered -->
```clojure
(register slab (box 30 30 5))

;; Top edges only (Z > 2)
(let [top-edges (find-sharp-edges slab
                                  :angle 80
                                  :where #(> (nth % 2) 2))]
  (out (str "top edges: " (count top-edges))))
```
<!-- /example-source -->

The `:where` predicate filters edges by position; only edges whose
endpoints both satisfy it are returned. A typical use is restricting
a chamfer to one side of an object.

## Notes

- The dihedral angle is the angle between the two face normals,
  measured in degrees. 180° is a flat surface; 90° is a right angle;
  small angles are very sharp.
- Edges whose two endpoints are not BOTH within the `:where`
  predicate are excluded. To keep an edge that has one endpoint
  inside and one outside, write a predicate looser at the boundary.
- The output feeds directly into `chamfer-prisms` and `chamfer-edges`
  for custom chamfer pipelines that filter, transform, or visualise
  the prism set before subtracting.

## See also

- **Guide:** placeholder → cap. 7 (Mesh operations)
- **Related:** `chamfer-edges`, `chamfer-prisms`, `chamfer`, `fillet`,
  `mesh-diagnose`
