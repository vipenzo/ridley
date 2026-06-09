---
name: bezier-to-anchor
category: turtle-movement
since: ""
status: stable
---

# bezier-to-anchor

## Signature

`(bezier-to-anchor name)`
`(bezier-to-anchor path :at name)`
`(bezier-to-anchor name & {:keys [steps tension tension-end]})`

## Description

Move the turtle to a named anchor along a smooth bezier curve that
respects both the current heading and the anchor's heading, producing a
C1-continuous connection. **Modifies turtle state.**

Anchors are named poses created by resolving marks via `with-path` (see
Spec §2 *Anchors & Navigation*). As a shorthand, pass a path directly with
`:at`, e.g. `(bezier-to-anchor my-path :at :tip)`, to resolve the mark from
that path at the current pose without an enclosing `with-path` (mirrors the
`(turtle path :at :name)` form; anchors are restored afterwards). The control points of the bezier are
derived from the two headings; `:tension` adjusts how far the control
points sit from each endpoint. By default both control points use the same
`:tension` (symmetric handles); pass `:tension-end` to give the arrival
control point a different distance (asymmetric handles), while both handle
directions stay locked to the headings.

## Parameters

- `name` — the anchor name (keyword).
- `:steps` — number of segments along the curve. Defaults to the
  resolution setting.
- `:tension` — control-point distance factor as a fraction of the
  straight-line distance. Default `0.33`. Applies to both handles unless
  `:tension-end` is given.
- `:tension-end` — distance factor for the arrival (anchor-side) control
  point only. Defaults to `:tension`, i.e. symmetric handles.

## Example

{{example: bezier-to-anchor-basic}}

<!-- example-source: bezier-to-anchor-basic -->
```clojure
(def skeleton (path (f 30) (mark :shoulder) (th 45) (f 20) (mark :elbow)))

(with-path skeleton
  (goto :shoulder)
  (bezier-to-anchor :elbow))
```
<!-- /example-source -->

Inside `with-path`, the anchor names become resolvable; the bezier
connects the current pose to `:elbow` smoothly.

## Variations

{{example: bezier-to-anchor-tension}}

<!-- example-source: bezier-to-anchor-tension -->
```clojure
(def skeleton (path (f 30) (mark :shoulder) (th 45) (f 20) (mark :elbow)))

(with-path skeleton
  (goto :shoulder)
  (bezier-to-anchor :elbow :tension 0.5))
```
<!-- /example-source -->

Higher `:tension` pushes the control points further from the endpoints,
producing a more swooping curve.

## Notes

- Requires the target anchor to exist in scope (via `with-path`).
- The endpoint heading after the call matches the anchor's heading —
  useful when chaining further movements that need to continue
  tangentially.

## See also

- **Related:** `bezier-to`, `bezier-as`, `goto`, `look-at`, `with-path`,
  `resolution`
