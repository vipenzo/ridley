---
name: seek!
category: live-interactive
since: ""
status: stable
---

# seek!

## Signature

`(seek! :name fraction)`

## Description

Jump an animation's playhead to a fractional position in `[0, 1]`. Useful for scrubbing through a timeline by hand, inspecting a specific keyframe, or aligning two animations to the same logical moment.

The fraction is clamped to `[0, 1]` before being converted to a time offset against the animation's duration. The animation state (`:playing`, `:paused`, `:stopped`) is not changed — `seek!` only moves the playhead. Combine with `pause!` to freeze on a specific frame, or with `play!` to resume from a new offset.

## Parameters

- `:name` — keyword name passed to `anim!` or `anim-proc!`.
- `fraction` — number in `[0, 1]`. Out-of-range values are clamped.

## Example

{{example: seek-midpoint}}

<!-- example-source: seek-midpoint -->
```clojure
(register gear (cyl 10 4))

(anim! :spin 4.0 :gear
  (span 1.0 :linear (tr 360)))

(play! :spin)

;; Jump to the halfway mark — gear instantly snaps to 180 degrees
(seek! :spin 0.5)

;; Freeze there to inspect the pose
(pause! :spin)
```
<!-- /example-source -->

`seek!` updates `:current-time` to `fraction * duration`. The next frame render picks up the new playhead and snaps the mesh into the corresponding pose.

## Notes

- **No state change.** `seek!` does not start, pause, or stop the animation — it only moves the cursor. If the animation is `:stopped`, the cursor moves but the mesh stays in its base state until `play!` is called.
- **Clamped fraction.** Negative values are treated as `0`, values above `1` as `1`. No exception is thrown.
- **No-op on unknown names.** `(seek! :missing 0.5)` does nothing rather than throwing.

## See also

- **Related:** `play!`, `anim-list`
