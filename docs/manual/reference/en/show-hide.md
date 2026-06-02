---
name: show
category: registration-visibility
since: ""
status: stable
---

# show / hide

## Signature

`(show name-or-ref)`
`(show coll key)`
`(hide name-or-ref)`
`(hide coll key)`

## Description

Toggle the visibility of registered objects (meshes and panels).
Polymorphic on the first argument:

- **Keyword / string / symbol** — registered name, including prefixes
  for assemblies (`(hide :puppet/r-arm)` hides every sub-mesh under the
  prefix).
- **Mesh or panel value** — the value's registry name is looked up; the
  matching entry is toggled.
- **Vector / map of meshes** — every element is toggled.

The two-arity form `(show coll key)` (and the matching `(hide coll key)`)
toggles a single element of a collection — useful when working with the
vector / map returned by a multi-mesh `register`.

`show` and `hide` are nullary on the visibility side: they do not return
anything meaningful for chaining — call them for their effect.

## Parameters

- `name-or-ref` — registered name (keyword / string / symbol), mesh value,
  panel value, vector of meshes, or map of meshes.
- `coll` — a vector or map of meshes.
- `key` — index (for a vector) or key (for a map).

## Example

{{example: show-hide-basic}}

<!-- example-source: show-hide-basic -->
```clojure
(register torus (extrude-closed (circle 5) (path (dotimes [_ 4] (f 20) (th 90)))))
(hide :torus)         ;; remove from view
(show :torus)         ;; bring back
```
<!-- /example-source -->

Toggle visibility by registered keyword name.

## Variations

{{example: show-hide-by-reference}}

<!-- example-source: show-hide-by-reference -->
```clojure
(def parts (mapv #(do (f 10) (sphere 3)) (range 5)))
(register chain parts)
(hide chain)               ;; hide every element of the vector
(show (nth parts 2))       ;; bring back the third sphere by value
```
<!-- /example-source -->

When you have the value in hand, pass it directly — the registry name
is resolved internally.

{{example: show-hide-collection-by-key}}

<!-- example-source: show-hide-collection-by-key -->
```clojure
(register robot {:torso (box 12 6 20) :head (do (f 10) (sphere 4))})
(hide robot :head)         ;; hide just :robot/head
```
<!-- /example-source -->

Two-arity form picks a single element from a vector or map — same
semantics as `(get coll key)` followed by `(hide …)`.

## Notes

- Prefix matching works for `register` assemblies: hiding `:puppet`
  hides every `:puppet/*` entry.
- Re-evaluating `(register name …)` preserves the current visibility
  state, so explicit `show` / `hide` calls stick across edits.

## See also

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `register`, `show-all`, `hide-all`, `show-only-objects`,
  `objects`, `info`
