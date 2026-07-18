---
name: import-mesh
category: mesh-operations
since: ""
status: stable
---

# import-mesh

## Signature

`(import-mesh path)`
`(import-mesh path :recenter true)`

## Description

Read a mesh file from disk, choosing the parser from the file
extension. **Desktop only** — the read goes through the desktop file
server; in the web build the call throws.

Supported formats:

- `.stl` — binary or ASCII, auto-detected, vertices welded on load
- `.obj` — Wavefront, geometry only (see `import-obj`)

This is the single name to remember when you do not care which format a
file happens to be in. It takes the same options as the format-specific
importers and returns the same mesh. An unsupported extension raises a
readable error naming the formats that are supported.

```clojure
(def part (import-mesh "/Users/me/Downloads/bracket.stl"))
(def scan (import-mesh "/Users/me/Downloads/scan.obj" :recenter true))
```

## Parameters

- `path` — string. Absolute (or relative) filesystem path to a `.stl`
  or `.obj` file.
- `:recenter` — boolean, default `false`. When `true`, translate the
  mesh so its bounding-box center sits at the origin. When `false`, the
  mesh keeps the file's own coordinates and its creation-pose is
  anchored at the bounding-box center.

## Example

{{example: import-mesh-basic}}

<!-- example-source: import-mesh-basic -->
```clojure
;; Works for either format — the extension decides the parser:
(def part (import-mesh "/Users/me/Downloads/bracket.stl" :recenter true))

(register imported part)
```
<!-- /example-source -->

## Notes

- Desktop only: requires the Tauri desktop file server. In the browser
  build the call throws with a clear message.
- The geometry is NOT embedded in the script; only the path is stored.
  For a fully self-contained model, import via the library panel, which
  emits a base64-inlined `decode-mesh` form instead.
- Format-specific behaviour is documented on `import-stl` and
  `import-obj`.

## See also

- **Related:** `import-obj`, `import-stl`, `decode-mesh`, `mesh-diagnose`
