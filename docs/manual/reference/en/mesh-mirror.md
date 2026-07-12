---
name: mesh-mirror
category: mesh-operations
since: ""
status: stable
---

# mesh-mirror

## Signature

`(mesh-mirror mesh)`

## Description

Reflect a mesh through the plane at the turtle's current pose — point =
`position`, normal = `heading`, the same plane convention as `mesh-split`
and `sdf-half-space`. Uses Manifold's native `.mirror`, which is
winding-correct: the result is a genuine reflection with positive
volume, not an inside-out mesh, with no manual face flip. For a plane
that does not pass through the origin the reflection is composed as
`translate(−p) ∘ mirror ∘ translate(+p)`.

The result inherits the source's creation-pose/material/anchors (the
usual single-source `carry-meta` policy). `mesh-mirror` accepts a mesh, a
keyword (registered mesh name), or an SDF node.

## Parameters

- `mesh` — the mesh (or name, or SDF node) to reflect.

## Example

{{example: mesh-mirror-basic}}

<!-- example-source: mesh-mirror-basic -->
```clojure
;; keep one half of a symmetric object and rebuild the whole
(register half (extrude (rect 20 10) (f 15)))
(register whole (mesh-union (get-mesh :half) (mesh-mirror (get-mesh :half))))
```
<!-- /example-source -->

## Notes

- Mirroring twice through the same plane is the identity.
- Companion to `mirror?` (is this symmetric here?) and `symmetry-planes`
  (where are its symmetry planes?).
- The mirror-based rebuild above is the structural compression behind
  the future STL converter / mesh-board: keep half, mirror the rest.

## See also

- **Related:** `mirror?`, `symmetry-planes`, `mesh-split`, `mesh-union`
