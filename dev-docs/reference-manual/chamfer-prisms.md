---
name: chamfer-prisms
category: mesh-operations
since: ""
status: stable
---

# chamfer-prisms

## Signature

`(chamfer-prisms mesh distance & {:keys [angle where]})`

## Description

Return the vector of triangular prism meshes that `chamfer-edges`
would subtract — one prism per qualifying sharp edge, in mesh form —
without actually cutting them out. Returns `nil` if no edges qualify.

Useful for:

- Previewing which edges a chamfer would cut. Register the prisms
  and visualize them alongside the source mesh.
- Asymmetric chamfers built by hand from a subset of the prisms
  (e.g. keep only those facing a certain way).
- Custom pipelines that transform the prism set before subtraction.

The option set is identical to `chamfer-edges`.

| Option   | Default | Description                                                              |
|----------|---------|--------------------------------------------------------------------------|
| `:angle` | 80      | Minimum dihedral angle (degrees) for an edge to count as sharp.          |
| `:where` | `nil`   | Predicate `(fn [[x y z]] -> bool)` applied to both endpoints of an edge. |

## Parameters

- `mesh` — a mesh value.
- `distance` — prism width (matches what `chamfer-edges` would use).

## Example

{{example: chamfer-prisms-preview}}

<!-- example-source: chamfer-prisms-preview -->
```clojure
(register block (box 20))

;; Visualize the chamfer tools without applying them
(register tools
  (concat-meshes (chamfer-prisms block 1.5)))
```
<!-- /example-source -->

The vector of prisms is stitched into a single mesh for inspection.
Each prism marks where `chamfer-edges` would cut.

## Variations

{{example: chamfer-prisms-asymmetric}}

<!-- example-source: chamfer-prisms-asymmetric -->
```clojure
;; Subtract only a subset of prisms: those whose midpoint has X > 0
(let [block (box 30 20 10)
      prisms (chamfer-prisms block 2 :angle 70)
      right (filter (fn [p]
                      (let [c (or (:centroid p)
                                  (first (:vertices p)))]
                        (> (first c) 0)))
                    prisms)]
  (register asymmetric (mesh-difference block (concat-meshes right))))
```
<!-- /example-source -->

The prism set is filtered programmatically before being subtracted.
This is the escape hatch when no built-in selector (direction,
`:where`, `:min-radius`) matches the desired pattern.

## Notes

- The returned prisms carry vertex data but no edge metadata; if you
  need both the prism and the originating edge information,
  `find-sharp-edges` gives the descriptor format and you can build
  prisms from those descriptors manually.
- Output of `chamfer-prisms` is a vector of meshes, not a single
  mesh. Use `concat-meshes` to view or subtract them in one go.

## See also

- **Guide:** placeholder → cap. 7 (Mesh operations)
- **Related:** `chamfer`, `chamfer-edges`, `find-sharp-edges`,
  `concat-meshes`
