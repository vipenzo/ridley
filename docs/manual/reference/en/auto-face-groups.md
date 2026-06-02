---
name: auto-face-groups
category: faces
since: ""
status: stable
---

# auto-face-groups

## Signature

`(auto-face-groups mesh)`
`(auto-face-groups mesh threshold)`

## Description

Group the triangles of `mesh` into coplanar faces by flood-fill over
adjacency. Returns the `:face-groups` map (integer ids → vectors of
triangle index triples). Pure function; does not modify the mesh in
place.

Two triangles that share an edge are merged into the same group when
their normals agree within `threshold`, the cosine cutoff (default
`0.996` ≈ 5°). This is how CSG-derived meshes get face metadata they did
not carry through the boolean operation.

Most user code does not call `auto-face-groups` directly — the selection
functions (`find-faces`, `face-at`, `face-nearest`, `largest-face`) and
`face-shape` invoke `ensure-face-groups` for you. Use it explicitly when
you want to control the threshold, or when you want to attach the groups
once and reuse them across many queries.

## Parameters

- `mesh` — a mesh value (no registry lookup; this is a raw mesh function).
- `threshold` (optional) — cosine cutoff for "same plane" (default `0.996`,
  about 5°). Lower the cutoff to merge gently-curved areas into one group;
  raise it to split nearly-coplanar faces apart.

## Example

{{example: auto-face-groups-csg}}

<!-- example-source: auto-face-groups-csg -->
```clojure
;; Attach groups to a CSG result once, then enumerate them
(def cut (difference (box 30) (translate (box 20) 0 8 0)))
(def groups (auto-face-groups cut))
(count groups)
;; => 9   ; the box's 6 faces become more after the boolean cut
```
<!-- /example-source -->

After a CSG cut, the original six box faces become more groups: the cut
introduces new coplanar regions on the top and inside.

## Variations

{{example: auto-face-groups-threshold}}

<!-- example-source: auto-face-groups-threshold -->
```clojure
;; Loosen the threshold to merge near-coplanar triangles
(def s (sphere 10 :segments 16))
(count (auto-face-groups s))            ; many tiny groups
(count (auto-face-groups s 0.5))        ; fewer, larger groups
```
<!-- /example-source -->

Sphere triangles have continuously varying normals. Loosening the
threshold sweeps progressively wider patches into the same group.

## Notes

- The function returns the groups map only; it does not assoc them onto
  the mesh. To get a mesh ready for `list-faces`, use `ensure-face-groups`
  or build it manually: `(assoc mesh :face-groups (auto-face-groups mesh))`.
- Triangles are joined only when they share an **edge** — coplanar
  triangles that are disconnected become separate groups.
- The default `0.996` cutoff is intentionally tight to avoid bleeding
  across fillet rings or chamfer steps. Loosen it only when you know the
  geometry tolerates it.

## See also

- **Related:** `ensure-face-groups`, `list-faces`, `face-ids`,
  `find-faces`, `face-info`
