---
name: export
category: export
since: ""
status: stable
---

# export

## Signature

`(export :name)`
`(export mesh-ref)`
`(export :name1 :name2 …)`
`(export mesh-ref1 mesh-ref2 …)`
`(export collection)`
`(export collection index-or-key)`
`(export … :3mf)`

## Description

Export one or more registered meshes to a file and trigger a browser download. The default format is STL; pass `:3mf` as the trailing argument to switch to 3MF.

`export` is the keyword-driven, registry-aware entry point: it accepts registered names (`:name`), mesh references (the value returned by `register`), or vectors/maps that hold meshes. Multiple targets are merged into a single file in STL mode; in 3MF mode they become distinct objects within the file.

For lower-level export from a mesh value already in hand (without going through the registry), reach for `save-mesh` / `save-stl` / `save-3mf` — they raise the native save-file picker instead of forcing a download with a derived filename.

## Parameters

- `:name` — keyword naming a registered object (single or repeated).
- `mesh-ref` — the value returned by `register` (or any mesh value), or repeated values.
- `collection` — a vector or map of meshes. Without a second argument, all elements are exported as one file.
- `index-or-key` — when passed, selects a specific element of the collection (integer index for vectors, keyword key for maps).
- `:3mf` (trailing) — switch the output format from STL to 3MF.

## Example

{{example: export-simple-stl}}

<!-- example-source: export-simple-stl -->
```clojure
;; Single registered mesh, STL
(register torus
  (revolve+ (circle 5) (move-to [20 0])))

(export :torus)             ; downloads torus.stl
```
<!-- /example-source -->

The single most common form: register, then `(export :name)` to drop an STL into the browser's download folder.

## Variations

{{example: export-multiple-by-name}}

<!-- example-source: export-multiple-by-name -->
```clojure
(register cube  (box 20))
(register ball  (sphere 12))

(export :cube :ball)        ; downloads a single STL with both meshes merged
(export cube ball)          ; same, but by mesh reference
```
<!-- /example-source -->

Multiple targets in STL mode are merged into one file. Either by name or by reference, the result is the same.

{{example: export-collection-by-index}}

<!-- example-source: export-collection-by-index -->
```clojure
(def parts
  [(box 20)
   (sphere 12)
   (cyl 8 25)])

(export parts)              ; all three in one STL
(export parts 2)            ; just the cylinder
```
<!-- /example-source -->

A vector or map of meshes can be exported wholesale or filtered by index/key. `(export parts 2)` picks element 2 of the vector; for maps the same slot is a keyword.

{{example: export-3mf-trailing}}

<!-- example-source: export-3mf-trailing -->
```clojure
(register torus
  (revolve+ (circle 5) (move-to [20 0])))

(export :torus :3mf)        ; downloads torus.3mf
```
<!-- /example-source -->

Add `:3mf` as the last argument to switch formats. Everything else about the call is identical.

{{example: export-3mf-multi-material}}

<!-- example-source: export-3mf-multi-material -->
```clojure
;; Multi-material 3MF: distinct objects, distinct colors, AMS-ready
(register :supporto (box 40 20 2))
(register :scritta  (extrude (text-shape "OK") :h 1))
(color :supporto 0xff0000)
(color :scritta  0xffffff)

(export :supporto :scritta :3mf)
```
<!-- /example-source -->

When two or more registered meshes have a `color` set, the 3MF file carries the color through to the slicer: each mesh becomes a separate `<object>` with its own material entry, ready to be mapped to AMS slots in Bambu Studio or OrcaSlicer. Identical colors on multiple meshes share one material entry.

## Notes

- **STL vs 3MF merging.** STL exports merge all selected meshes into one geometric body (no metadata). 3MF preserves them as distinct objects, which is why multi-material workflows require 3MF.
- **Color handling.** Only the multi-material 3MF path uses `color`. Plain `(export … :3mf)` on meshes without colors produces the same single-object structure as STL.
- **Filename.** Derived from the first argument. `(export :torus)` produces `torus.stl`; `(export torus cube)` derives the name from the first registered binding it can resolve. When you need control over the filename, use `save-mesh`/`save-stl`/`save-3mf` — they accept an explicit suggested name and raise the native save-file picker.
- **Materials.** `material` on a mesh affects the viewport rendering only; STL/3MF export carries `color` (for 3MF) but does not embed material properties.

## See also

- **Related:** `save-mesh`, `save-stl`, `save-3mf`, `color`, `material`
