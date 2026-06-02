---
name: stop!
category: live-interactive
since: ""
status: stable
---

# stop!

## Signature

`(stop!)`
`(stop! :name)`

## Description

Stop an animation and reset its playhead to frame 0. Unlike `pause!`, `stop!` also restores the target mesh to its captured base state — the vertices, faces, and creation-pose recorded when the animation first started playing. For `:camera` targets, `stop!` re-enables the OrbitControls so manual interaction works again.

When several animations share a target, `stop!` only restores the base state if no sibling animation is still playing on the same target. This avoids one animation yanking the mesh out from under another mid-playback.

Without arguments, `stop!` is equivalent to `stop-all!`.

## Parameters

- `:name` — keyword name passed to `anim!` or `anim-proc!`. Omit to stop every animation.

## Example

{{example: stop-named}}

<!-- example-source: stop-named -->
```clojure
(register gear (cyl 10 4))

(anim! :spin 3.0 :gear :loop
  (span 1.0 :linear (tr 360)))

(play! :spin)

;; Stop the loop and restore the gear to its pre-animation pose
(stop! :spin)
```
<!-- /example-source -->

After `stop!`, the gear is back to its original orientation as if `:spin` had never run. Calling `(play! :spin)` again starts a fresh playback from frame 0.

## Notes

- **Shared-target safety.** If two animations target `:gear` and one is still playing, `(stop! :other)` resets that animation's state but does not touch the mesh — the sibling keeps driving it.
- **Camera reset.** Stopping a `:camera` animation re-enables OrbitControls via a callback registered at startup; you can immediately drag the viewport again.
- **No-op on unknown names.** `(stop! :missing)` does nothing rather than throwing.

## See also

- **Related:** `stop-all!`, `play!`, `pause!`
