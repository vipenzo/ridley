---
name: on-anchors
category: positioning-assembly
since: ""
status: stable
---

# on-anchors

## Signature

`(on-anchors target [combine-mode] pattern [:align] body ...)`

## Description

Iterate over the anchors of a path or mesh and evaluate a body per matching
anchor, with the turtle positioned at that anchor. Each clause pairs a
**pattern** with a **body**; for every anchor, clauses are tested in order
and the first matching clause's body runs (no fallthrough). The body is
evaluated inside an implicit `(turtle :pose <anchor-pose> body)` scope, so
turtle primitives (`f`, `th`, `attach`, `cyl`, …) operate relative to the
anchor.

`on-anchors` collapses the explicit `for` + `filter` + `(attach foo (move-to skel :at m :align))`
idiom into a structured dispatch: one pass over the anchors, multiple
patterns matched in priority order, each pattern documenting the role of
the anchors it targets.

## Parameters

- `target` — a path map (with `(mark …)` recordings) **or** a mesh value
  carrying an `:anchors` map (set by `attach-path` or by registering
  inside a `with-path` scope). Resolved via `anchors`.
- `combine-mode` *(optional, immediately after `target`)* — one of
  `:concat` (default), `:union`, or `:vec`. Controls how the per-anchor
  bodies are combined into the return value. Any other keyword in that
  position is treated as a pattern, preserving the original API.
- `pattern` — one of four kinds:

| Pattern | Match                                        |
|---------|----------------------------------------------|
| string  | prefix match on `(name anchor-name)`         |
| regex   | `re-find` on `(name anchor-name)`            |
| keyword | equality with `anchor-name`                  |
| set     | `contains?` of `anchor-name` in the set      |

- `:align` *(optional, per clause)* — when present, the body is evaluated
  with the full anchor pose (position + heading + up). When omitted (the
  default), only the position is taken; the turtle's heading/up are
  inherited from the parent scope. Same default as `move-to`.
- `body` — a single expression. For multiple pieces on one anchor, wrap
  them in `(mesh-union …)`, `(concat-meshes …)`, or `(do …)`.

## Returns

Depends on `combine-mode`:

| Mode      | Result                                                                      |
|-----------|-----------------------------------------------------------------------------|
| `:concat` *(default)* | `(concat-meshes …)` — fast geometric merge of every body's mesh; valid only when the bodies are **disjoint**. Returns `nil` if no body produced a mesh. |
| `:union`  | `(mesh-union …)` — boolean union. Use when the per-anchor bodies overlap each other or the host mesh; concat in that case leaves interior faces that produce artefacts in later booleans. |
| `:vec`    | A flat vector of the per-anchor meshes (via `flatten-meshes`) — useful when the caller needs to compose them with a non-default operator. Empty vector if no body produced a mesh. |

Non-mesh values are silently dropped in every mode.

A console warning is emitted for any pattern that matched **zero**
anchors, listing the available anchor names. Anchors that match no
pattern are skipped silently — filtering only a subset is a normal use.

## Example

{{example: on-anchors-fence}}

<!-- example-source: on-anchors-fence
(def row-skel
  (path (mark :end-post-start) (f 20)
        (mark :mid-post-0) (f 20)
        (mark :mid-post-1) (f 20)
        (mark :end-post-finish)))
(def end-post (cyl 8 40))
(def mid-post (cyl 4 30))
(register fence
  (on-anchors row-skel
    "end-post-" :align (attach end-post)
    "mid-post-" :align (attach mid-post)))
-->

Two roles (`end-post`, `mid-post`) are dispatched by name prefix on the
same skeleton. `:align` snaps each component to the marker's frame.

## Variations

{{example: on-anchors-feet}}

<!-- example-source: on-anchors-feet
(def W 80) (def D 50)
(def plate-skel
  (path (rt (/ W 2)) (u (/ D 2)) (mark :foot-1)
        (u (- D)) (mark :foot-2)
        (rt (- W)) (mark :foot-3)
        (u D) (mark :foot-4)))
(def foot (cyl 4 20))
(def plate (extrude (rect W D) (f 3)))
(register table
  (mesh-union plate
    (on-anchors plate-skel
      "foot-" (attach foot))))
-->

No `:align` — vertical feet attach by position only, retaining their
construction orientation regardless of the path's local heading.

{{example: on-anchors-set}}

<!-- example-source: on-anchors-set
(def W 80) (def D 50)
(def skel
  (path (rt (/ W 2)) (u (/ D 2)) (mark :foot-1)
        (u (- D)) (mark :foot-2)
        (rt (- W)) (mark :foot-3)
        (u D) (mark :foot-4)))
(def long-foot  (cyl 4 30))
(def short-foot (cyl 4 15))
(register stand
  (on-anchors skel
    #{:foot-1 :foot-3} (attach long-foot)
    #{:foot-2 :foot-4} (attach short-foot)))
-->

A set pattern picks an explicit subset of anchors by name.

{{example: on-anchors-direct}}

<!-- example-source: on-anchors-direct
(def skel
  (path (mark :end-1) (f 20) (mark :mid) (f 20) (mark :end-2)))
(register markers
  (on-anchors skel :align
    "end-" (cyl 8 40)
    :mid   (cyl 4 30)))
-->

The body need not be an `attach` — any primitive or composite mesh
expression works, evaluated in the turtle scope at the anchor.

## Notes

- **First match wins.** Overlapping patterns are dispatched by clause
  order. If `#"^foo-"` precedes `#{:foo-1}`, the regex consumes `:foo-1`
  and the set clause matches nothing (and warns).
- **Default is no-align.** Mirrors `move-to`'s default — the body runs
  at the anchor's *position* but inherits the parent turtle's
  heading/up. Add `:align` to also rotate the body's local frame onto
  the anchor's frame.
- **Pattern resolution order is anchors-major.** For each anchor, the
  patterns are tested in order; not the other way around. This means a
  body cannot inspect *which* pattern matched.
- **Body inside a turtle scope.** Each body runs inside an implicit
  `(turtle :pose <pose> body)`. Subsequent turtle commands (`f`, `th`,
  `attach`, …) all operate in that scope; on body exit, the parent
  turtle state is restored.
- **No fallthrough.** If no pattern matches an anchor, that anchor is
  silently skipped. To detect this, use a final `#".*"` regex clause.
- **Path marks resolve at the *current* turtle pose**, not at the
  world origin. This is the `with-path` convention, intentionally
  different from `(anchors path)` (which always returns origin-
  anchored marks for inspection) and from `(move-to path :at name)`
  (which uses absolute marks). The composable choice — `on-anchors`
  inside `(turtle …)` or inside another assembly body inherits the
  outer pose, so a re-usable component that calls `on-anchors path`
  internally lands its pieces where its caller put the turtle.
  Mesh targets are unaffected: a mesh's `:anchors` are stored in
  world coordinates and used as-is.

## See also

- **Guide:** placeholder → cap. 8 (Assemblaggio)
- **Related:** `anchors`, `attach`, `move-to`, `with-path`, `turtle`,
  `mark`, `attach-path`
