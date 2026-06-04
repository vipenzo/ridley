---
name: mesh-difference
category: mesh-operations
since: ""
status: stable
---

# mesh-difference

## Signature

`(mesh-difference base tool)`
`(mesh-difference base tool1 tool2 …)`
`(mesh-difference [base tool1 tool2 …])`

## Description

Subtract one or more tool meshes from a base mesh. Returns a new
manifold mesh covering the region in `base` that is NOT in any tool.
Inputs are unchanged.

The variadic form subtracts every following argument from the first;
the vector form takes a single sequence whose first element is the
base. Subtracting multiple tools is one CSG call, not N — pass them
all in a single invocation when possible.

## Parameters

- `base` — the manifold mesh to be cut.
- `tool`, `tool1 tool2 …` — manifold meshes to subtract from the base.
- `[base tool1 tool2 …]` — alternatively, a single vector.

## Example

{{example: mesh-difference-basic}}

<!-- example-source: mesh-difference-basic -->
```clojure
(register block (box 40))
(f 10)
(register hole-tool (cyl 6 60))

(register punched (mesh-difference block hole-tool))
(hide :block) (hide :hole-tool)
```
<!-- /example-source -->

A cylindrical hole is cut through a box. The original meshes are
hidden so only the punched result is visible.

## Variations

{{example: mesh-difference-ring-of-holes}}

<!-- example-source: mesh-difference-ring-of-holes -->
```clojure
;; Drill a ring of 12 holes through a disk in a single CSG call
(register plate
  (mesh-difference
    (cyl 30 5)
    (concat-meshes
      (for [i (range 12)]
        (attach (cyl 2 8) (th (* i 30)) (f 20))))))
```
<!-- /example-source -->

`concat-meshes` packs every hole tool into one mesh; `mesh-difference`
subtracts the lot in a single Manifold operation. Compared to a
sequential `(reduce mesh-difference plate holes)`, this saves N-1
intermediate CSG passes.

## Notes

- Both `base` and tools must be manifold. If a tool only partially
  protrudes through the base, the cut is still valid — only the
  intersected volume is removed.
- Tools that do not intersect the base have no effect; Manifold
  simply returns the base unchanged.
- When the cut would split the base into disconnected pieces, the
  result is a multi-component but still manifold mesh.
- For surface cuts (think: removing thin pieces from a sheet), use
  `mesh-difference` with thin extruded tools; surface-level booleans
  on infinitely thin geometry are not supported.

## See also

- **Related:** `mesh-union`, `mesh-intersection`, `mesh-hull`,
  `concat-meshes`, `mesh-diagnose`
