---
name: import-obj
category: mesh-operations
since: ""
status: stable
---

# import-obj

## Signature

`(import-obj path)`
`(import-obj path :recenter true)`

## Description

Read a Wavefront OBJ file from disk and return a Ridley mesh.
**Desktop only** — the read goes through the desktop file server; in the
web build the call throws.

Only geometry is read. Vertex lines (`v`) and face lines (`f`) become
the mesh; texture coordinates (`vt`), normals (`vn`), materials
(`mtllib`, `usemtl`), groups (`o`, `g`) and smoothing (`s`) are all
skipped. This means an OBJ whose companion `.mtl` or texture files are
missing still imports cleanly — the materials are never consulted, so
their absence cannot be an error. That is the common case for
photogrammetry exports, which ship an OBJ plus an MTL and a JPG that
Ridley has no use for.

Faces with more than three vertices are fan-triangulated. Because OBJ
is already an indexed format, vertices are shared by index and no
welding pass is needed on load.

```clojure
(def scan (import-obj "/Users/me/Downloads/kiri-scan.obj" :recenter true))
```

## Parameters

- `path` — string. Absolute (or relative) filesystem path to an `.obj`
  file.
- `:recenter` — boolean, default `false`. When `true`, translate the
  mesh so its bounding-box center sits at the origin. When `false`, the
  mesh keeps the file's own coordinates and its creation-pose is
  anchored at the bounding-box center.

## Example

{{example: import-obj-basic}}

<!-- example-source: import-obj-basic -->
```clojure
;; Point at your local copy of a scan or downloaded model:
(def scan (import-obj "/Users/me/Downloads/scan.obj" :recenter true))

(register part scan)
```
<!-- /example-source -->

## Notes

- Desktop only: requires the Tauri desktop file server. In the browser
  build the call throws with a clear message.
- A missing or unreadable `.mtl` is never an error — materials are not
  read at all. Set appearance with `material` / `color` after import.
- The geometry is NOT embedded in the script; only the path is stored.
- Scans are dense. Expect large triangle counts, and consider
  `mesh-simplify` before heavy CSG work.

## See also

- **Related:** `import-mesh`, `import-stl`, `decode-mesh`, `mesh-simplify`, `mesh-diagnose`
