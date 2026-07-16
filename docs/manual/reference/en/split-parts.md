---
name: split-parts
category: mesh-operations
since: ""
status: stable
---

# split-parts

## Signature

`(split-parts result)`

## Description

Flatten a `mesh-split` composite result into an ordered vector of
leaves — depth-first, `:behind` before `:ahead` at each node. For the
linear chain a composite `mesh-split` call produces today, that's
simply `[piece-1 piece-2 … remaining]`, in cut order.

A bare mesh (not a `{:behind :ahead}` node — e.g. what the single-cut
primitive `(mesh-split m)` returns) is treated as a single leaf:
`(split-parts mesh)` => `[mesh]`.

`split-parts` walks the node shape itself, not a fixed chain length —
a `:behind` that is itself a `{:behind :ahead}` node is walked the
same way. `mesh-split` only ever builds a linear chain today, but
`split-parts` doesn't assume that: if the composite ever grows into a
real tree (re-entering `edit-mesh-split` on a piece, say), this
function needs no change.

## Parameters

- `result` — the return value of `mesh-split`: either a bare mesh, or
  a `{:behind :ahead}` node (whose values are themselves bare meshes
  or further nodes).

## Example

{{example: split-parts-basic}}

<!-- example-source: split-parts-basic -->
```clojure
(register block (extrude (rect 20 20) (f 30)))
(def result
  (mesh-split (get-mesh :block)
              (path (f 10) (mark :cut-1) (f 10) (mark :cut-2))))
(def parts (split-parts result))
(out (str "pieces: " (count parts)))
```
<!-- /example-source -->

## Notes

- Useful whenever the number of pieces matters more than which mark
  produced which piece — printing/registering every piece, summing
  volumes, checking every piece is convex before accepting a
  decomposition as final. When you *do* want to say which piece, reach
  for `split-tree`: same order, same leaves, named `{:piece-1 …}`
  instead of a vector — and that's the shape `mesh-board` and `attach`
  take.
- `(split-parts (mesh-split m))` (single-cut primitive) is
  `[(:behind r) (:ahead r)]` where `r` is that call's own result —
  the two-element case is exactly what you'd expect, not a special
  case in the implementation.

## See also

- **Related:** `split-tree`, `mesh-split`, `convex?`
