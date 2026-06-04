---
name: info
category: registration-visibility
since: ""
status: stable
---

# info

## Signature

`(info name-or-ref)`
`(info coll key)`

## Description

Return a summary map for a registered object. The map carries the name,
visibility, vertex / face counts, and the 3D bounding box. Polymorphic
on the first argument:

- **Keyword / string / symbol** — registered name.
- **Mesh value** — uses the value directly.
- **Vector / map of meshes** — returns a sequence of info maps, one per
  element.

The two-arity form `(info coll key)` picks a single element of a
collection by index (vector) or key (map).

## Parameters

- `name-or-ref` — registered name, mesh value, or collection.
- `coll`, `key` — collection + index/key.

## Example

{{example: info-basic}}

<!-- example-source: info-basic -->
```clojure
(register torus (extrude-closed (circle 5) (path (dotimes [_ 4] (f 20) (th 90)))))
(info :torus)
;; => {:name :torus :visible true :vertices N :faces M :bounds {...}}
```
<!-- /example-source -->

Returns the registry view of `torus`: visibility, mesh size, and the
axis-aligned bounding box.

## See also

- **Related:** `bounds`, `mesh`, `dimensions` (`height` / `width` /
  `depth` / `top` / `bottom` / `center-*`), `registered`, `objects`
