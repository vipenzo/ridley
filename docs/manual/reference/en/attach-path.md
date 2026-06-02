---
name: attach-path
category: positioning-assembly
since: ""
status: stable
---

# attach-path

## Signature

`(attach-path mesh-name path)`

## Description

Resolve a path's `mark`s at a registered mesh's creation-pose and store the resulting `name → pose` map on the mesh as its `:anchors`. The mesh keeps its geometry; the path is not extruded — it is only used as a source of named feature points (positions + orientations) that subsequent operations can query.

After `(attach-path :mesh sk)`, the mesh `:mesh` carries named anchors that can be addressed by:

- `(move-to :mesh :at :anchor)` — snap a child to the anchor inside `attach` / `attach!`.
- `(move-to :mesh :at :anchor :align)` — snap and also rotate to the anchor's frame.
- `(anchors :mesh)` — return the full anchor map for inspection or iteration.
- `(link! :child :mesh :at :anchor)` — bind a child target to the anchor for animation playback.

`attach-path` is the bridge between a freestanding skeleton path and a registered mesh: write the skeleton once, give the mesh a stable vocabulary of feature names, then assemble children against those names without having to re-derive coordinates.

## Parameters

- `mesh-name` — keyword. Must be the name of a mesh already in the registry; the call is a no-op if no mesh is registered under that name.
- `path` — a recorded path containing one or more `mark`s. Marks are resolved relative to the mesh's `:creation-pose`, so anchors track the mesh through subsequent `attach!` / `translate` / `rotate` calls in world coordinates.

## Example

{{example: attach-path-basic}}

<!-- example-source: attach-path-basic -->
```clojure
(register upper (extrude (circle 1.5) (f 15)))

;; Define and attach a skeleton with two named feature points
(attach-path :upper (path (mark :top) (f 15) (mark :tip)))

(register lower (extrude (circle 1.2) (f 10)))
;; Snap lower's origin onto upper's :tip anchor
(attach! :lower (move-to :upper :at :tip))
```
<!-- /example-source -->

The upper segment gets two named anchors at its base and tip. The lower segment is then snapped to `:tip` via `move-to`, producing a two-bone arm without manual coordinate maths.

## Variations

{{example: attach-path-iterate-marks}}

<!-- example-source: attach-path-iterate-marks -->
```clojure
;; A row of slots, then iterate over them to drop one bolt per slot
(register rail (box 60 10 4))
(attach-path :rail
  (path (rt -25) (mark :s1) (rt 10) (mark :s2)
        (rt 10) (mark :s3) (rt 10) (mark :s4) (rt 10) (mark :s5)))

(register bolt (cyl 1.5 6))
(doseq [m (keys (anchors :rail))]
  (attach! :bolt (move-to :rail :at m)))
```
<!-- /example-source -->

Anchors named generatively let downstream code iterate over them by name. `(keys (anchors :rail))` returns every mark; one mesh per slot is snapped in place by the `doseq`.

## Notes

- **Anchors are relative to the creation-pose.** They follow the mesh under further `attach!` / `translate` / `rotate` operations: query `(anchors :mesh)` after any pose change and the anchor positions come back in updated world coordinates.
- **No-op for unregistered names.** `attach-path` looks up the mesh in the registry; passing a keyword that has never been `register`ed leaves the call silently effectless. Register first, attach the path second.
- **Pass a path, not a mesh.** The second argument must be a path map (the return value of `(path ...)`). Bare turtle commands are not auto-wrapped — wrap them in `(path ...)` if you have not already.
- **Empty paths are tolerated.** A path with no `mark`s yields no anchors and the existing `:anchors` map is left untouched.
- **Inspect anchors before assembling.** `(anchors :mesh)` returns the full map; `(get-anchor :name)` returns one pose from the live turtle. Use them to verify a skeleton resolves the way you expect before driving `move-to` / `link!`.

## See also

- **Related:** `anchors`, `link!`, `move-to`, `path`, `mark`,
  `with-path`, `get-anchor`
