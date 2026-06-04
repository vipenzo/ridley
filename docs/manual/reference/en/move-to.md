---
name: move-to
category: positioning-assembly
since: ""
status: stable
---

# move-to

## Signature

`(move-to target)`
`(move-to target :align)`
`(move-to target :center)`
`(move-to target :at anchor)`
`(move-to target :at anchor :align)`

Available inside `attach` / `attach!` bodies (and inside `path`,
where it is recorded for later replay).

## Description

Inside an attach body, move the turtle to another object's pose and
optionally re-align with one of that object's named anchors.
`move-to` is the cleanest primitive for positioning one mesh
relative to another: it translates the attached value and updates
the turtle frame so subsequent commands operate in the target's
coordinate system.

Five forms:

| Form                        | Position                        | Frame                                 |
|-----------------------------|---------------------------------|---------------------------------------|
| `(move-to :name)`           | target's creation-pose position | turtle adopts target's heading/up     |
| `(move-to :name :align)`    | target's creation-pose position | mesh ALSO rotated to match creation-pose |
| `(move-to :name :center)`   | target's centroid               | turtle heading unchanged              |
| `(move-to :name :at :a)`    | target's named anchor `:a`      | turtle adopts anchor's heading/up     |
| `(move-to … :at :a :align)` | target's anchor                 | mesh ALSO rotated to match anchor     |

`:align` is the opt-in flag that turns a translation-only `move-to`
into a translate-and-rotate. It is valid with the default (creation-pose)
form and with `:at :anchor`. It is **not** valid with `:center` —
a centroid has no associated frame, so there is nothing to align to.

The default form (`(move-to :A)`) is "go to A and face the same way
A does". Subsequent `(f …)` means "forward in A's frame", which is
the natural way to write relative positioning that survives later
rotations of A.

`:center` is for centroid alignment without orientation change —
useful when only the position matters.

`:at :anchor` snaps to a named anchor (set on the target via
`attach-path`). Throws if the anchor doesn't exist.

`:align`, added after the anchor name (or after `target` in the
no-`:at` form), also rotates the attached mesh's vertices so its
current frame snaps onto the target frame (the anchor's frame in
`:at` form, the target's creation-pose frame otherwise). The
rotation runs in two steps: first align headings, then roll around
the new heading to align up vectors.

## Parameters

- `target` — a keyword or a mesh/path value. As a keyword, resolved
  in this order: (1) named anchor on the current turtle (set by
  `with-path` or top-level `mark`); (2) registered mesh in the
  registry. As a path value, the path's marks become anchors
  (only valid with `:at`).
- `:center` — flag selecting centroid mode.
- `:at` — keyword introducing a named anchor on the target.
- `anchor` — the anchor's keyword name. Must exist on the target.
- `:align` — flag enabling vertex-level rotation onto the target's
  frame.

When `target` resolves to an anchor (case 1 above), only the default
form and `:align` are valid — `:center` and `:at` throw, because an
anchor is a single pose with no centroid and no sub-anchors.

## Example

{{example: move-to-default}}

<!-- example-source: move-to-default -->
```clojure
(register base (box 40))
(attach! :base (th -90) (f 50) (th 90))    ; move base to (0, 50, 0)

(register sphere (sphere 10))
;; Place sphere on top of base (wherever base is now)
(attach! :sphere (move-to :base) (tv 90) (f 30) (tv -90))
```
<!-- /example-source -->

`move-to :base` snaps the turtle to base's pose and adopts its
orientation. The subsequent `(tv 90) (f 30)` is relative to base's
frame, so the sphere lands "above" base no matter how base has been
rotated.

## Variations

{{example: move-to-center}}

<!-- example-source: move-to-center -->
```clojure
(register block (box 30 30 5))
(register dot (sphere 2))

;; Position the dot at the block's centroid, keeping turtle orientation
(attach! :dot (move-to :block :center))
```
<!-- /example-source -->

`:center` is the right mode when you want centroid alignment without
inheriting the target's frame.

{{example: move-to-anchor}}

<!-- example-source: move-to-anchor -->
```clojure
(register upper (extrude (circle 1.5) (f 15)))
(attach-path :upper (path (mark :top) (f 15) (mark :tip)))

(register lower (extrude (circle 1.2) (f 10)))
;; Snap lower's origin to upper's :tip anchor; turtle adopts :tip's frame
(attach! :lower (move-to :upper :at :tip))
```
<!-- /example-source -->

When the target has named anchors (set via `attach-path`),
`:at :anchor` snaps to a specific feature. The mesh is translated,
the turtle adopts the anchor's frame, but the mesh's vertices are NOT
rotated.

{{example: move-to-align}}

<!-- example-source: move-to-align -->
```clojure
;; Two-step rotation: align the child's frame onto the anchor's frame
(register parent (box 20))
(attach-path :parent (path (mark :slot)))

(register child (extrude (rect 6 3) (f 8)))
;; :align also rotates the child mesh so its heading/up match :slot's
(attach! :child (move-to :parent :at :slot :align))
```
<!-- /example-source -->

`:align` is the natural primitive when the anchor's orientation is
meaningful (e.g. an anchor placed with `(th 180)` to flag a flipped
slot). The child's vertices rotate to face the anchor's heading/up.

## Notes

- Default `move-to` adopts the target's `:creation-pose`, not its
  visual center. After a CSG, the pose may sit far from the visual
  center; reset it on the target with `reset-creation-pose` if
  needed.
- `:at` requires the target to carry the named anchor.
  `attach-path` is the standard way to associate anchors with a
  mesh; alternatively, build the mesh inside a `with-path` scope
  whose marks resolve as anchors.
- Without `:align`, the child mesh keeps its construction
  orientation — only the turtle's frame changes (for subsequent
  commands inside the same `attach`). Add `:align` — valid with the
  default and `:at` forms, rejected with `:center` — when the
  child's vertices must face the target's direction.
- `move-to` is body-only: outside an `attach` / `attach!` body, the
  symbol either is shadowed by the live-turtle helper or unbound.

## See also

- **Related:** `attach`, `attach!`, `attach-path`, `play-path`,
  `cp-rotation`, `with-path`, `goto`, `path-to`
