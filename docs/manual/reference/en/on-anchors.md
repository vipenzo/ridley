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
| vector `[rail-sel shape-pat]` | **grid mode** — see below   |

- `:align` *(optional, per clause)* — when present, the body is evaluated
  with the full anchor pose (position + heading + up). When omitted (the
  default), only the position is taken; the turtle's heading/up are
  inherited from the parent scope. Same default as `move-to`.

  **Grid mode.** When the target mesh was built from a marked **profile**
  swept along a rail (so it carries `:section-anchors` + `:rail-path`), a
  clause pattern that is a 2-vector `[rail-sel shape-pat]` stamps the body
  over the **product** of rail locations × matching profile marks. `rail-sel`
  is a fraction `t∈[0,1]`, a vector of fractions, or a pattern over the rail's
  `(mark …)` names; `shape-pat` matches the profile marks. Grid clauses are
  independent passes — they don't interact with the flat per-anchor matching,
  so plain clauses keep working unchanged:

  ```clojure
  ;; a peg at every foot (profile mark) × at 0, mid, end of the sweep
  (on-anchors plate
    [[0 0.5 1] "foot"] (cyl 2 5))
  ```
- `body` — a single expression. For multiple pieces on one anchor, wrap
  them in `(mesh-union …)`, `(concat-meshes …)`, or `(do …)`.

### Match bindings

Inside each flat clause body these symbols are bound to the **anchor that
matched**, so one parameterized clause can replace several near-identical
ones:

| Symbol   | Value                                                              |
|----------|-------------------------------------------------------------------|
| `anchor` | the matched anchor name (keyword, e.g. `:0|here`)                  |
| `$`      | the full match string — `(name anchor)` for non-regex patterns, the `re-find` match for a regex |
| `$1`..`$9` | regex capture groups (strings), or `nil` when the group is absent |

```clojure
;; four scissor holders, one per arm, dispatched by the captured digit
(def arm-tags {"0" :red-small "1" :red-big "2" :green "3" :purple})
(on-anchors Arms
  #"(\d)\|here" :align
  (attach (mkmesh (arm-tags $1)) (f 10) (tv -220)))
```

> The SCI context has **no** `js/parseInt` or `parse-long`, so `$1` is a
> string. Index a string-keyed map (`{"0" …}`) rather than parsing it to a
> number. These bindings are not available in **grid-mode** clauses (which
> have no single matched anchor).

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
(defn end-post [] (attach (cyl 8 40) (cp-f -20) (tv 90)))
(defn mid-post [] (attach (cyl 4 30) (cp-f -15) (tv 90)))
(register fence
  (on-anchors row-skel
    "end-post-" (end-post)
    "mid-post-" (mid-post)))
-->

Two roles (`end-post`, `mid-post`) are dispatched by name prefix on the
same skeleton. Each role is a 0-arg helper that **builds its post inline**
(`tv 90` stands the cylinder up, `cp-f` drops its base onto the anchor), so
the geometry is born at the anchor's pose — see the note on building bodies
inline below.

## Variations

{{example: on-anchors-feet}}

<!-- example-source: on-anchors-feet
(def W 80) (def D 50) (def inset 5)
(def hx (- (/ D 2) inset))
(def hy (- (/ W 2) inset))
(def leg-skel
  (path (f hx) (rt hy) (mark :leg-1)
        (f (* -2 hx)) (mark :leg-2)
        (rt (* -2 hy)) (mark :leg-3)
        (f (* 2 hx)) (mark :leg-4)))
(defn leg [] (attach (cyl 4 30) (cp-f -15) (tv -90)))
(def top (extrude (rect W D) (tv 90) (f 3)))
(register table
  (mesh-union top
    (on-anchors leg-skel
      "leg-" (leg))))
-->

The four legs are dispatched by the `"leg-"` prefix and unioned with the
host `top` mesh. No `:align` — each leg is built inline at its anchor and
turned downward with `tv -90`, so it hangs below the table regardless of the
skeleton's local heading.

{{example: on-anchors-set}}

<!-- example-source: on-anchors-set
(def W 80) (def D 50) (def inset 5)
(def hx (- (/ D 2) inset))
(def hy (- (/ W 2) inset))
(def leg-skel
  (path (f hx) (rt hy) (mark :leg-1)
        (f (* -2 hx)) (mark :leg-2)
        (rt (* -2 hy)) (mark :leg-3)
        (f (* 2 hx)) (mark :leg-4)))
(defn long-leg  [] (attach (cyl 4 40) (cp-f -20) (tv -90)))
(defn short-leg [] (attach (cyl 4 20) (cp-f -10) (tv -90)))
(register stand
  (on-anchors leg-skel
    #{:leg-1 :leg-3} (long-leg)
    #{:leg-2 :leg-4} (short-leg)))
-->

A set pattern picks an explicit subset of anchors by name — here the two
diagonal pairs get long and short legs respectively.

{{example: on-anchors-direct}}

<!-- example-source: on-anchors-direct
(def skel
  (path (mark :end-1) (f 30) (tv 40) (mark :mid) (f 30) (tv 40) (mark :end-2)))
(register markers
  (on-anchors skel
    "end-" :align (cyl 6 30)
    :mid   :align (cyl 4 24)))
-->

The body need not be an `attach` — a bare primitive works, evaluated in the
turtle scope at the anchor. Here `:align` is a **per-clause** flag (it sits
between the pattern and its body): with it, each cylinder takes the anchor's
full pose, so along this climbing path the markers tilt to follow the local
heading. Drop `:align` and they would all stay axis-aligned.

## Notes

- **First match wins.** Overlapping patterns are dispatched by clause
  order. If `#"^foo-"` precedes `#{:foo-1}`, the regex consumes `:foo-1`
  and the set clause matches nothing (and warns).
- **Default is no-align.** Mirrors `move-to`'s default — the body runs
  at the anchor's *position* but inherits the parent turtle's
  heading/up. Add `:align` to also rotate the body's local frame onto
  the anchor's frame.
- **Pattern resolution order is anchors-major.** For each anchor, the
  patterns are tested in order; not the other way around. A body cannot
  inspect *which* pattern matched, but it can inspect *which anchor*
  matched it — via the `anchor` / `$` / `$1`..`$9` bindings above.
- **Body inside a turtle scope.** Each body runs inside an implicit
  `(turtle :pose <pose> body)`. Subsequent turtle commands (`f`, `th`,
  `attach`, …) all operate in that scope; on body exit, the parent
  turtle state is restored.
- **Build the body geometry inline.** A primitive is born at the *current*
  turtle pose, so constructing it inside the body (`(cyl …)`, or a 0-arg
  helper that builds and returns one) places it at the anchor. A mesh built
  earlier with `def` carries its own origin pose, and `(attach that-mesh)`
  replays its attach-path from a fresh turtle — it does **not** relocate the
  mesh to the anchor. So `(attach (cyl 8 40) …)` lands on the anchor, but
  `(def post (cyl 8 40))` followed by `(attach post)` leaves every instance
  stacked at the origin. Reach for `def` only to share a *shape/profile*,
  not a finished mesh you intend to scatter; otherwise wrap construction in a
  `(defn post [] …)` helper as the examples do.
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

- **Related:** `anchors`, `attach`, `move-to`, `with-path`, `turtle`,
  `mark`, `attach-path`
