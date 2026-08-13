---
name: edit-plane-mark
category: acquisition
since: ""
status: stable
---

# edit-plane-mark

## Signature

`(edit-plane-mark pose-map)`
`(edit-plane-mark)`

## Description

Re-open a **plane mark** of an evaluated `(acquire …)` on the acquisition
stage. Unlike the other `edit-*` forms it is not a top-level marker: it lives
**inside** the acquire's `:marks` map — but the gesture is the family's usual
one, because a mark's resting form is `(plane-mark …)`: put `edit-` in front of
the head and Run.

```clojure
(acquire "dir"
  {:proxy (registration-plate :d 300)
   :marks {:piano-1 (plane-mark {:position [...] :heading [...] :up [...]})
           :piano-2 (edit-plane-mark {:position [...] :heading [...] :up [...]
                                      :from [[...] [...] [...]]})}})
```

Evaluating that source opens the plane editor on `:piano-2`: its plane is shown
as a translucent disc, its origin as a magenta dot, and the points it was
fitted through (`:from`) come back so they can be added to or thinned. **Accept**
writes `(plane-mark {…})` with the updated values; **Esc** renames the head back
to `plane-mark` and leaves the body byte-identical.

Written with no argument — `:piano-4 (edit-plane-mark)` — it is the *creation*
gesture instead, with the destination already chosen in the source: click the
points, accept, and the mark appears there. Cancelling that one removes the
whole entry, key included, rather than leaving anything behind.

A bare `{…}` literal is accepted too (marks emitted before `plane-mark`
existed), and confirming converts it: the source converges on one dialect.

While the editor is open the form returns its argument unchanged, so the
`(acquire …)` value stays complete and anything downstream of it keeps working.

## Parameters

- `pose-map` — the mark to edit: `{:position … :heading … :up …}`, optionally
  with `:from [[x y z] …]`, the triangulated points it was fitted through.
  Omit it to create a new mark at this key.

## Notes

- `:from` is what makes a mark re-editable: without it the source keeps the
  result and discards the evidence, so a plane can only be rebuilt from
  scratch — which moves its origin, and with it anything measured against it.
  Every consumer (`turtle`, the anchor machinery) ignores the extra key.
- `:from` holds the **triangulated points**, not the per-photo clicks. A point
  can be added or dropped and the plane refitted; an individual 2D click cannot
  be revisited.
- A deliberately placed origin survives a refit: it is projected onto the new
  plane rather than recomputed as the centroid.
- With several `(edit-plane-mark …)` forms present, the first is opened and the
  others wait — confirm this one and re-run to reach the next.
- The name is not `edit-mark`: `(mark name)` is the path-anchor command, a
  different thing that name would promise to edit.

## See also

- `plane-mark` — the resting form this pairs with
- `acquire` — the form whose `:marks` this edits
- `turtle` — how a mark is used: `(turtle (:piano-2 (:marks A)) (edit-path-2d))`
