---
name: mesh-split
category: mesh-operations
since: ""
status: stable
---

# mesh-split

## Signature

`(mesh-split mesh)`
`(mesh-split mesh path)`
`(mesh-split mesh path marks-vector)`
`(mesh-split mesh path marks-map)`
`(mesh-split mesh path marks-spec opts)`

## Description

Split a mesh with the plane defined by the turtle's current pose —
point = position, normal = heading — into two halves. Returns
`{:behind <mesh> :ahead <mesh>}`.

`:behind` is the half **behind** the heading — the side the turtle
came from. This is the same convention as `sdf-half-space`, on
purpose: after `extrude` the turtle ends on the far face of the new
solid with the material behind it, so calling `mesh-split` at that
pose puts the material in `:behind`. `:ahead` is the opposite half.
One truth about which side is which, shared by `sdf-half-space` and
`mesh-split` across the whole system.

Either half may come back as an empty mesh (`:vertices []`,
`:faces []`) when the plane misses the piece entirely, or only grazes
it — that is a legitimate result, not an error.

Both halves inherit the source mesh's `:creation-pose`, `:material`
and `:anchors` — the same single-source policy `mesh-hull`/`solidify`
already use for a single-input operation.

`mesh-split` accepts a mesh map, a keyword (registered mesh name), or
an SDF node (auto-materialized).

**Composite form.** With a `path`, `mesh-split` cuts at every `(mark
…)` in it — guillotine-style, one cut per mark, each result's
`:ahead` right-nested into the next cut:

```clojure
(mesh-split block (path (f 10) (mark :cut-1) (f 10) (mark :cut-2)))
;; => {:behind piece-1 :ahead {:behind piece-2 :ahead remaining}}
```

`(mesh-split m path)` cuts at every mark in appearance order;
`(mesh-split m path marks-vector)` cuts only at the listed marks, in
the vector's own order (letting you select a subset, or reorder). A
single-mark composite call returns exactly the same `{:behind :ahead}`
shape as the primitive — the composite and the primitive are the same
operation, just with more marks to walk. The path is resolved from
the turtle's *current* pose, same resolver every other path consumer
(`with-path`, `attach`, …) uses — not from the path's own internal
identity frame.

A mark whose plane misses the remaining produces an empty `:behind`
at its place in the chain — the chain continues from `:ahead`
unchanged, without error.

**Branching form.** The third argument generalizes from a flat
`marks-vector` to a **map**, `{mark sub-spec …}`, letting the piece
*detached* at a mark (its `:behind`) be cut further instead of staying
a leaf — the composite generalizes right along with it: a `:behind`
becomes a node (`{:behind :ahead}`) instead of a mesh.

```clojure
(mesh-split mount
  (path (tv 90) (f -10) (mark :cut-1) (f -20) (mark :cut-2))
  {:cut-1 (path (f -3) (mark :cut-1-1))   ; the piece detached at :cut-1
                                          ;   gets cut again, once
   :cut-2 nil})                           ; :cut-2 just cuts, no more
```

Grammar (`spec` is the third argument):

```
spec     := [mark …]              ; flat selection (unchanged from above)
          | {mark sub-spec, …}    ; branching
sub-spec := nil                   ; the mark just cuts — a leaf
          | path                  ; the detached piece is cut at every
                                  ;   mark of this path (this path's own
                                  ;   default: cut everything)
          | [path spec]           ; full recursive form — spec here can
                                  ;   itself branch (a branch within a
                                  ;   branch), same two shapes as above
```

Two rules that don't generalize the way you might expect from the
vector form:

- **Cut order** comes from the `path`'s own mark order, never the
  map's key order — Clojure doesn't guarantee one, and the plane
  positions must be reproducible from the source alone.
- **Sub-path frame.** A sub-spec's `path` resolves from the mark's own
  *cut-pose* — where the turtle was when that cut happened — not from
  the live turtle's current pose. It is the sub-piece's natural frame:
  the piece being cut further no longer has anything to do with
  wherever the turtle has since moved on to.

A mark named in a `marks-vector` or as a `marks-map` key that doesn't
exist in `path` throws, naming it — same error, both shapes.

**Sliver healing.** A fourth, optional `opts` map turns on a post-split
safety net: `{:heal-slivers true}` (or `{:heal-slivers {:thickness t}}`
to override the default threshold). A cut placed exactly flush with a
flat face can shave an irregular, near-degenerate sliver off the wrong
side — Manifold's meshes are float32, so a "flat" face is rippled by a
few nm of tessellation noise, and a plane sitting exactly on it splits
that noise unpredictably. With `:heal-slivers` on, each half of the
result is decomposed into its connected components; any component
whose extension **along the cut plane's normal** is under the
threshold (default on the order of a few µm, scale-aware like
`cut-candidates`' `:bias`) is treated as sliver debris, not real
material, and reassigned to the *other* half instead — the criterion
is thickness along the cut normal specifically, not volume, so a
small-but-intentional tab (thin in some other direction, but not along
the cut normal) is left alone. Either half may legitimately end up
empty. `(mesh-split m nil nil {:heal-slivers true})` applies it to a
single cut at the turtle's current pose; with a `path`, it applies to
every cut in the plan. See `edit-mesh-split`, whose own internal cuts
heal by default — this DSL function stays opt-in so its existing
contract doesn't change underfoot.

See `split-parts` to flatten a composite result into an ordered
vector of leaves, or `split-tree` to get the same leaves named —
`{:piece-1 … :piece-2 …}`, in cut order. `split-tree` is what
`mesh-board` and `attach` want: both refuse a raw composite and say
so, rather than mistake its `:behind`/`:ahead` keys for piece names.

## Parameters

- `mesh` — the mesh (or keyword name, or SDF node) to split.
- `path` (optional) — a path containing one or more `(mark …)`
  commands; each mark is one cut. Omit both `path` and
  `marks-vector` for a single cut at the turtle's current pose.
- `marks-vector`/`marks-map` (optional) — a vector of mark names
  selecting and ordering which marks to cut at (defaults to every
  mark in `path`, in appearance order), or a map for branching — see
  Branching form above.
- `opts` (optional, 4-arity only) — `{:heal-slivers true}` or
  `{:heal-slivers {:thickness t}}` to turn on the sliver safety net;
  see Sliver healing above. Pass `path`/`marks-spec` as `nil` for a
  single cut at the current pose with `opts`.

## Example

{{example: mesh-split-basic}}

<!-- example-source: mesh-split-basic -->
```clojure
(register block (extrude (rect 20 20) (f 20)))
(f 10)
(def halves (mesh-split (get-mesh :block)))
(register :left (:behind halves))
```
<!-- /example-source -->

Moving the turtle halfway into the block (`(f 10)`) before calling
`mesh-split` cuts it in two at that plane; `:behind` is the half
between the origin and the cut.

## Variations

{{example: mesh-split-degenerate}}

<!-- example-source: mesh-split-degenerate -->
```clojure
(register block (extrude (rect 10 10) (f 10)))
(f 100)
(def halves (mesh-split (get-mesh :block)))
;; the plane is far past the block — :ahead is empty, :behind is
;; the whole block
```
<!-- /example-source -->

A cut plane that doesn't touch the mesh is a normal outcome, not an
error — one of the two halves comes back empty.

{{example: mesh-split-composite}}

<!-- example-source: mesh-split-composite -->
```clojure
(register block (extrude (rect 20 20) (f 30)))
(def result
  (mesh-split (get-mesh :block)
              (path (f 10) (mark :cut-1) (f 10) (mark :cut-2))))
(def parts (split-parts result))   ; [piece-1 piece-2 remaining]
(def named (split-tree result))    ; {:piece-1 … :piece-2 … :piece-3 …}
```
<!-- /example-source -->

Two marks cut the block into three convex slabs. `split-parts`
flattens the nested result into an ordered vector, `split-tree` into
the same leaves under their `:piece-N` names — see their own
reference cards.

## Notes

- The `:behind`/`:ahead` mapping is decided in exactly one place
  (the underlying `split-by-plane` wrapper) and consumed as-is
  everywhere else — never re-derived.
- `mesh-split` results do not currently carry AI-describe/history
  provenance the way `mesh-union`/`mesh-hull` do (it returns a map of
  two meshes rather than a single mesh, which doesn't fit that
  single-mesh tracking).
- For a convexity check on either half (e.g. before treating a piece
  as final), see `convex?`.

## See also

- **Related:** `sdf-half-space`, `slice-mesh`, `mesh-diagnose`, `convex?`,
  `split-parts`, `split-tree`
- **Interactive:** `edit-mesh-split` — a modal session for decomposing
  a mesh into pieces by eye instead of computing cut poses by hand
