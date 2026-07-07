---
name: import-stl
category: mesh-operations
since: ""
status: stable
---

# import-stl

## Signature

`(import-stl path)`
`(import-stl path :recenter true)`

## Description

Read an STL file from disk and return a Ridley mesh. Both binary and
ASCII STL are supported (auto-detected), and vertices are welded
(deduplicated) on load. **Desktop only** — the read goes through the
desktop file server; in the web build the call throws.

Unlike `decode-mesh` (which embeds the geometry as base64 blobs in the
generated code), `import-stl` keeps the geometry **external**: the
script only references the file path. This makes a `.clj` model shareable
even when the STL itself may not be redistributed — the recipient simply
re-downloads the STL from its original source and points `import-stl`
at their local copy.

```clojure
(def mount (import-stl "/Users/me/Downloads/multiboard-mount.stl"))
```

## Parameters

- `path` — string. Absolute (or relative) filesystem path to a `.stl`
  file.
- `:recenter` — boolean, default `false`. When `true`, translate the
  mesh so its bounding-box center sits at the origin (the same
  recentering the panel importer applies via `mesh-translate`). When
  `false`, the mesh keeps the STL's own coordinates and its
  creation-pose is anchored at the bounding-box center.

## Example

{{example: import-stl-basic}}

<!-- example-source: import-stl-basic -->
```clojure
;; Point at your local copy of a freely-downloadable STL:
(def mount (import-stl "/Users/me/Downloads/mount.stl" :recenter true))

(register part mount)
```
<!-- /example-source -->

## Notes

- Desktop only: requires the Tauri desktop file server. In the browser
  build the call throws with a clear message.
- The geometry is NOT embedded in the script. If you need a fully
  self-contained model (no external file), import via the library
  panel instead, which emits a base64-inlined `decode-mesh` form.
- Material/colour metadata is not read from the STL — set it via
  `material` / `color` after import.

## See also

- **Related:** `decode-mesh`, `mesh-translate`, `material`, `color`
