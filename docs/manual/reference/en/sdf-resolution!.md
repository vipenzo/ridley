---
name: sdf-resolution!
category: sdf-modeling
since: ""
status: stable
---

# sdf-resolution!

## Signature

`(sdf-resolution! n)`

## Description

Set the global meshing resolution for SDF auto-materialization, in
turtle-style units (the same scale used by `(resolution :n N)` for
curves). Default is `15`. Returns the new value. Side-effecting:
mutates the global `*sdf-resolution*`.

The global resolution governs **every** auto-materialization triggered
by an SDF→mesh boundary (`register`, boolean with a mesh, export). Set
it before the first such boundary in your code to control mesh quality
for the whole script. For per-call control, pass an explicit resolution
to `sdf->mesh` or `sdf-ensure-mesh`.

When the SDF tree contains thin features (`sdf-shell`, small
`sdf-offset`, narrow blends), the auto-resolver may further boost the
resolution beyond `n` to guarantee at least 3 voxels across the
thinnest part.

> Desktop only: requires the libfive backend.

## Parameters

- `n` — new global resolution in voxels per unit. Higher = finer
  mesh, but voxel count grows cubically.

## Example

{{example: sdf-resolution-bump}}

<!-- example-source: sdf-resolution-bump -->
```clojure
;; Bump the global resolution before building a detailed SDF
(sdf-resolution! 60)
(register fine (sdf-displace (sdf-sphere 10)
                             '(* 0.5 (sin (* x 6)))))
```
<!-- /example-source -->

The displaced sphere needs more triangles to render the high-frequency
displacement cleanly. `(sdf-resolution! 60)` quadruples the default,
applied to every subsequent auto-materialization in the session.

## Notes

- The setting persists for the JS runtime's lifetime — it survives
  REPL re-evaluations until explicitly changed again.
- Total voxel count is capped to keep meshes printable. A warning
  fires when the chosen resolution + bounds combination exceeds the
  cap.
- For per-call control (without changing the global), prefer the
  explicit-resolution arities of `sdf->mesh` and `sdf-ensure-mesh`.

## See also

- **Per-call control:** `sdf->mesh`, `sdf-ensure-mesh`
- **Predicate:** `sdf-node?`
- **Related:** `resolution`
