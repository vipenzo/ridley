---
name: mirror?
category: mesh-operations
since: ""
status: stable
---

# mirror?

## Signature

`(mirror? mesh)`
`(mirror? mesh epsilon)`

## Description

Test whether a mesh is mirror-symmetric about the plane at the turtle's
current pose (point = `position`, normal = `heading`). A two-step
cascade:

1. **Free volumetric gate** — the two halves of the split at the plane
   must have equal volume within tolerance (this volume is already what
   the semaphore computes, so the gate is nearly free and rejects the
   vast majority of non-mirrors immediately).
2. **Symmetric-difference confirmation** — reflect the near half through
   the plane and measure `vol(union) − vol(intersection)` over a half's
   volume. ≈ 0 means the reflected half coincides with the far half: a
   true mirror.

The test is deliberately **volumetric**, not a triangle-by-triangle
comparison: the two halves may legitimately tessellate differently even
on a perfectly symmetric object.

`epsilon` (optional, default `0.02`) is the tolerance on that ratio — a
symmetric object with a small off-axis feature that removes a fraction
`f` of a half's volume shows a ratio ≈ `2f`, so the default rejects an
asymmetry above ~1% of a half. An empty mesh is symmetric by definition
(`true`).

## Parameters

- `mesh` — the mesh (or name, or SDF node) to test.
- `epsilon` (optional) — tolerance on the symmetric-difference ratio.

## Example

{{example: mirror-p-basic}}

<!-- example-source: mirror-p-basic -->
```clojure
(register block (box 10 20 30))
(out (str "symmetric here? " (mirror? (get-mesh :block))))  ; true at the origin plane
```
<!-- /example-source -->

## Notes

- Cost is 77–148 ms on a real mesh — **on-demand**, never per-keystroke.
  `edit-mesh-split` runs the free gate live and defers the confirmation
  to a debounced background check.
- To find symmetry planes rather than test a known one, use
  `symmetry-planes`.

## See also

- **Related:** `symmetry-planes`, `mesh-mirror`, `mesh-split`, `convex?`
