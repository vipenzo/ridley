---
name: shape?
category: 2d-shapes
since: ""
status: stable
---

# shape?

## Signature

`(shape? x)`

## Description

Predicate. Returns `true` if `x` is a 2D shape map (a map with
`:type :shape`), `false` otherwise. Does not modify turtle state.

Useful in user code that accepts both shapes and other geometry kinds
(meshes, paths, SDFs) and needs to dispatch on type.

## Parameters

- `x` — any value.

## Example

{{example: shape-p-basic}}

<!-- example-source: shape-p-basic -->
```clojure
(shape? (circle 10))            ; => true
(shape? (rect 20 5))            ; => true
(shape? (path (f 30)))          ; => false
(shape? "not a shape")          ; => false
```
<!-- /example-source -->

Sanity checks at the REPL. `shape?` only recognises the 2D shape map; for
paths use `path?`.

## See also

- **Related:** `path?`, `shape-fn?`, `make-shape`
