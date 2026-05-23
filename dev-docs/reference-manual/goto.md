---
name: goto
category: turtle-movement
since: ""
status: stable
---

# goto

## Signature

`(goto anchor-name)`

## Description

Move the turtle to a named anchor and adopt its full pose: position, heading, and up. After `(goto :a)` the turtle stands at `:a`'s position with `:a`'s orientation — so a subsequent `(f 10)` advances along `:a`'s forward, not along whatever direction the turtle was facing before.

Anchors are produced by resolving `mark`s embedded in a path. The standard pattern is `(with-path skeleton (goto :name))`: `with-path` pins the path at the current turtle pose and turns its marks into anchors; `goto` then navigates between them. Outside a `with-path` scope, the turtle's anchor table is empty and `goto` is a no-op (state returned unchanged).

If the pen is on (`pen-down`), `goto` also draws a line from the previous position to the anchor's position, recorded as a scene line for the implicit layer. The pen state is otherwise untouched.

## Parameters

- `anchor-name` — keyword identifying an anchor that exists in the turtle's current `:anchors` table. If the name is not present, the call is silently a no-op.

## Example

{{example: goto-basic}}

<!-- example-source: goto-basic -->
```clojure
(def arm-sk
  (path
    (mark :shoulder)
    (f 30) (mark :elbow)
    (th 40) (f 25) (mark :wrist)))

(with-path arm-sk
  (goto :shoulder)
  (register upper (extrude (circle 1.5) (path-to :elbow)))
  (goto :elbow)
  (register fore  (extrude (circle 1.2) (path-to :wrist))))
```
<!-- /example-source -->

The arm skeleton is pinned at the origin; `goto` walks between two anchors, and each `extrude` runs from the current pose in the direction of the next anchor. Both segments build natively in world coordinates, no post-hoc snapping needed.

## Variations

{{example: goto-pen-line}}

<!-- example-source: goto-pen-line -->
```clojure
;; goto draws a line when the pen is down — useful for visualising a skeleton
(def waypoints (path (mark :a) (f 20) (mark :b) (th 90) (f 15) (mark :c)))

(pen-down)
(with-path waypoints
  (goto :a) (goto :b) (goto :c))
```
<!-- /example-source -->

With `pen-down`, every `goto` records a scene line from the previous position to the anchor — handy when debugging an assembly's skeleton before building geometry on top.

## Notes

- **Adopts heading AND up.** `goto` makes the turtle frame match the anchor's frame in full. To navigate to the position only, use `(move-to :name :center)` inside `attach`, or `look-at` followed by `(f distance)`.
- **No-op on missing anchor.** If the name is not in the current anchor table, `goto` returns the turtle state unchanged. Inspect `(keys (anchors path))` or `(get-anchor :name)` to confirm a name resolves before relying on it.
- **Inside `with-path` only.** Outside the scope of `with-path` (or other anchor-providing macros), the turtle has no anchors and `goto` does nothing useful. To verify, call `(get-anchor :name)` and check for `nil`.
- **Pen behaviour.** Only the line is recorded; heading/up are taken from the anchor, not derived from the pen segment. The pen state is not modified.

## See also

- **Related:** `look-at`, `path-to`, `with-path`, `get-anchor`,
  `anchors`, `mark`
