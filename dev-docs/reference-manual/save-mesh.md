---
name: save-mesh
category: export
since: ""
status: stable
---

# save-mesh

## Signature

`(save-mesh mesh)`
`(save-mesh mesh "suggested-name")`
`(save-mesh mesh "suggested-name" :3mf)`

## Description

Save a mesh value to disk through the native save-file picker. The user is shown a system dialog with a suggested filename; the format the picker writes follows the extension the user actually types.

`save-mesh` is the lower-level companion to `export`. Where `export` works from registered names and forces a browser download with a derived filename, `save-mesh` takes an already-resolved mesh value and lets the user choose the destination interactively. Use it when the mesh is the result of an inline computation, when the value is not (or should not be) registered, or when the user expects a "Save as…" experience.

The `:3mf` keyword only seeds the suggested extension; the actual format is decided by the extension the user types into the picker. Passing `.3mf` to a default `save-mesh` call will produce a 3MF file even though no `:3mf` keyword was passed. Conversely, typing `.stl` after `(save-mesh m "foo.3mf" :3mf)` produces an STL file. Effectively the trailing keyword only changes the default suggestion.

## Parameters

- `mesh` — a single mesh value or a vector of meshes. Vectors are merged into one STL or kept distinct in 3MF (see `export` for multi-material details).
- `"suggested-name"` — string shown pre-filled in the native picker. Defaults to `"model.stl"`.
- `:3mf` — trailing keyword that switches the suggested extension to `.3mf`. The user's typed extension wins.

## Example

{{example: save-mesh-picker}}

<!-- example-source: save-mesh-picker -->
```clojure
(let [m (mesh-difference (box 30) (cyl 6 35))]
  (save-mesh m))
;; Native picker opens with "model.stl" pre-filled.
;; Whatever extension the user types — .stl, .3mf — wins.
```
<!-- /example-source -->

A computed mesh is offered directly to the picker. No registration is required; the value is exported on the spot.

## Variations

{{example: save-mesh-named}}

<!-- example-source: save-mesh-named -->
```clojure
(let [m (cyl 20 40)]
  (save-mesh m "spool.stl"))
;; Picker pre-fills "spool.stl"; user can still change it.
```
<!-- /example-source -->

The second argument is purely a suggestion shown to the user; it does not force the final filename.

{{example: save-mesh-3mf-suggestion}}

<!-- example-source: save-mesh-3mf-suggestion -->
```clojure
(let [m (sphere 15)]
  (save-mesh m "part.3mf" :3mf))
;; Pre-fills "part.3mf". Typing "part.stl" instead produces STL.
```
<!-- /example-source -->

`:3mf` seeds the picker with a `.3mf` extension. The picker is still authoritative; the user can override.

## Notes

- **Picker, not download.** `save-mesh` opens a native "Save as…" dialog. The browser/desktop chooses where the file lands.
- **Extension wins.** The format is decided by the typed extension. The `:3mf` keyword only pre-fills the suggestion.
- **Vector of meshes.** Same merging rule as `export`: STL merges, 3MF keeps distinct objects.
- **vs `export`.** Use `export` when the workflow is "I have a registered name and want the file now"; use `save-mesh` when the mesh is a fresh value or the user should pick the path.

## See also

- **Related:** `save-stl`, `save-3mf`, `export`
