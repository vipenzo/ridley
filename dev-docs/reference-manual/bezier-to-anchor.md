---
name: bezier-to-anchor
category: turtle-movement
since: ""
status: stable
---

# bezier-to-anchor

## Signature

`(bezier-to-anchor name)`
`(bezier-to-anchor name & {:keys [steps tension]})`

## Description

Move the turtle to a named anchor along a smooth bezier curve that
respects both the current heading and the anchor's heading, producing a
C1-continuous connection. **Modifies turtle state.**

Anchors are named poses created by resolving marks via `with-path` (see
Spec §2 *Anchors & Navigation*). The control points of the bezier are
derived from the two headings; `:tension` adjusts how far the control
points sit from each endpoint.

## Parameters

- `name` — the anchor name (keyword).
- `:steps` — number of segments along the curve. Defaults to the
  resolution setting.
- `:tension` — control-point distance factor as a fraction of the
  straight-line distance. Default `0.33`.

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

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `bezier-to`, `bezier-as`, `goto`, `look-at`, `with-path`,
  `resolution`
