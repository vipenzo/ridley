---
name: split-tree
category: mesh-operations
since: ""
status: stable
---

# split-tree

## Signature

`(split-tree result)`

## Description

Turn a `mesh-split` composite into a map of **named pieces** —
`{:piece-1 … :piece-2 … :piece-3 …}` — numbered in cut order, so
`:piece-N` is the Nth piece detached and the last key is the final
remaining piece.

`edit-mesh-split` emits a bare `mesh-split` call for a straight
guillotine, deliberately: nothing to unpick if you want to re-open it,
and no names you didn't ask for. `split-tree` is where the names come
from when you do want them — and they are the same names the session's
own piece labels showed you.

```clojure
(def AA (mesh-split (get-mesh :mount)
          (path (tv 90) (f -1.62) (mark :cut-1))
          [:cut-1]))

(mesh-board (split-tree AA))                   ; show every piece
(mesh-board (split-tree AA) {:only [:piece-2]}) ; show one by name
(attach (split-tree AA) (f 10))                ; move them as a group

(let [{:keys [piece-1 piece-2]} (split-tree AA)]
  (register base piece-1))
```

Values are the very same meshes, untouched — `split-tree` renames, it
never copies or transforms.

## Parameters

- `result` — the return value of `mesh-split`: a `{:behind :ahead}`
  node, or a bare mesh (an uncut mesh is a one-piece tree,
  `{:piece-1 mesh}`).

## Example

{{example: split-tree-basic}}

<!-- example-source: split-tree-basic -->
```clojure
(register block (extrude (rect 20 20) (f 30)))
(def result
  (mesh-split (get-mesh :block)
              (path (f 10) (mark :cut-1) (f 10) (mark :cut-2))))
(def pieces (split-tree result))
(out (str "names: " (sort (keys pieces))))
```
<!-- /example-source -->

## Notes

- `split-tree` is `split-parts` plus names: same order, same leaves,
  `{:piece-N …}` instead of a vector. Reach for `split-parts` when the
  count or the sum is what matters, `split-tree` when you want to say
  *which* piece.
- `mesh-board` and `attach` **refuse** a raw composite and point here.
  That is on purpose: a one-cut composite would otherwise pass for a
  perfectly good two-piece tree named `:behind`/`:ahead`, and only break
  once a second cut nests the `:ahead`. The conversion stays visible in
  your source instead of being guessed at.
- The names match `edit-mesh-split`'s labels for the bare-call shape,
  which is the only shape it emits nude. A branched decomposition emits
  a `let`-chain that names its own pieces — there is nothing to convert.

## See also

- **Related:** `split-parts`, `mesh-split`, `edit-mesh-split`, `mesh-board`, `attach`
