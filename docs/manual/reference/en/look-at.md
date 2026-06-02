---
name: look-at
category: turtle-movement
since: ""
status: stable
---

# look-at

## Signature

`(look-at anchor-name)`

## Description

Rotate the turtle's heading to point toward a named anchor. The turtle does not move; only its orientation changes. The up vector is rotated as little as possible — the previous up is projected onto the plane perpendicular to the new heading and renormalised — so a turtle that was facing horizontally keeps a near-vertical up after a horizontal look-at.

`look-at` is the natural primitive when you want to extrude or shoot a feature *in the direction of* a known anchor without actually navigating to it. Pair it with `(f distance)` to advance partway, with `bezier-to-anchor` to curve smoothly toward the target, or with `extrude` to launch a part along the line of sight.

Like `goto`, anchors come from a `with-path` scope: the macro pins a path's marks as anchors that `look-at` can resolve by name.

## Parameters

- `anchor-name` — keyword identifying an anchor in the turtle's current `:anchors` table. If the name is not present, the call is a no-op.

## Example

{{example: look-at-basic}}

<!-- example-source: look-at-basic -->
```clojure
(def targets
  (path (mark :origin) (f 30) (th 60) (f 20) (mark :target)))

(with-path targets
  (look-at :target)
  ;; Shoot a thin cylinder halfway toward :target
  (register beam (extrude (circle 0.6) (f 25))))
```
<!-- /example-source -->

The turtle is at the origin facing the recorded `:target` anchor; `extrude` then walks 25 units in that direction. Without `look-at`, the cylinder would extrude along the default `+X` heading.

## Variations

{{example: look-at-preserve-up}}

<!-- example-source: look-at-preserve-up -->
```clojure
;; look-at tries to keep the up vector close to its previous value
(def skel (path (mark :base) (f 20) (u 30) (mark :tower-top)))

(with-path skel
  (look-at :tower-top)
  ;; Up is still close to +Z, so a level-axis extrude reads as "leaning toward"
  (register strut (extrude (circle 0.8) (f 35))))
```
<!-- /example-source -->

When `look-at` is called with an anchor that is partly above the turtle, the heading tilts upward while the up is kept as vertical as possible. This is the desired behaviour for visualising aim or shooting struts: roll does not drift unexpectedly.

## Notes

- **No movement.** Position is unchanged. Use `goto` if you also need to relocate to the anchor.
- **Up is preserved as much as possible.** The implementation projects the old up onto the plane perpendicular to the new heading. When the old up is parallel to the new heading (a vertical look-at from a flat pose), an arbitrary perpendicular axis is chosen as a fallback; the resulting roll is well-defined but not under user control. Set the roll explicitly with `tr` afterwards if it matters.
- **No-op when the target is at the same position.** If the anchor coincides with the turtle's position (distance < 1e-4), there is no direction to look in; `look-at` returns the state unchanged.
- **No anchor → no-op.** Like `goto`, an unknown name leaves the turtle untouched. Verify with `(get-anchor :name)` if in doubt.

## See also

- **Related:** `goto`, `path-to`, `with-path`, `get-anchor`, `anchors`,
  `mark`, `tr`
