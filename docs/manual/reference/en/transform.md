---
name: transform
category: positioning-assembly
since: ""
status: stable
---

# transform

## Signature

`(transform mesh path)`
`(transform [m1 m2 …] path)`

## Description

Replay a recorded path on a virtual turtle attached to a mesh (or a
sequence of meshes) and bake the resulting rigid-body transform into
the mesh's vertices. Returns a new mesh (or a new vector of meshes).

`transform` is the path-driven counterpart of `attach`: where
`attach` takes inline turtle commands as a body, `transform` takes
an already-recorded path. The virtual turtle starts at the mesh's
`:creation-pose`; after walking the path, the new position / heading
/ up are baked into the result. The original is unchanged.

When given a vector of meshes, the SAME rigid transform is applied
to each mesh. Their relative arrangement is preserved — useful for
transporting a sub-assembly around a scene without re-building it.

## Parameters

- First argument — a mesh, or a vector of meshes.
- `path` — a recorded path. Its commands describe the rigid-body
  motion (translates + rotates) to apply.

## Example

{{example: transform-single-mesh}}

<!-- example-source: transform-single-mesh -->
```clojure
(register cube (box 12))
(def trip (path (f 30) (th 45) (tv 20) (f 15)))

(register moved (transform cube trip))
(hide :cube)
```
<!-- /example-source -->

A pre-recorded path moves the cube to a new pose: forward 30,
yaw 45°, pitch 20°, forward 15. The vertices land at exactly the
pose the virtual turtle reaches.

## Variations

{{example: transform-group}}

<!-- example-source: transform-group -->
```clojure
;; Build a sub-assembly out of separate meshes, then move it as a group
(register hub  (sphere 5))
(register arm  (translate (cyl 2 18) 9 0 0))

(def carry (path (th 30) (f 25) (tv -20)))

(let [moved (transform [hub arm] carry)]
  (register hub2 (nth moved 0))
  (register arm2 (nth moved 1)))
(hide :hub) (hide :arm)
```
<!-- /example-source -->

A two-piece assembly is rigid-transformed together. The two pieces
move identically, so the joint between them stays where it was.

## Notes

- `transform` always applies a pure rigid-body motion (translate +
  rotate). Path commands that imply non-rigid effects (`stretch-*`,
  `inset`) are not supported in this context.
- The virtual turtle's starting pose is the mesh's
  `:creation-pose`, not the live turtle's pose. To start from the
  live turtle, build the path on the live turtle first and then
  call `transform`.
- For a vector of meshes, the transform is the same for each — so
  the operation preserves relative arrangement but does not allow
  per-mesh variation. For per-mesh transforms, fan out manually:
  `(mapv #(transform % path-i) meshes)`.

## See also

- **Related:** `attach`, `translate`, `rotate`, `path`, `follow-path`
