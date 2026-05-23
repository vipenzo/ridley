---
name: pause!
category: live-interactive
since: ""
status: stable
---

# pause!

## Signature

`(pause!)`
`(pause! :name)`

## Description

Pause a playing animation, freezing the playhead at the current frame. Calling `play!` later resumes from the same frame; calling `stop!` rewinds to frame 0 and restores the base mesh state.

Without arguments, every animation that is currently `:playing` is paused. With a keyword name, only the matching animation is paused (no-op if it is already paused or stopped).

## Parameters

- `:name` — keyword name passed to `anim!` or `anim-proc!`. Omit to pause every playing animation.

## Example

{{example: pause-named}}

<!-- example-source: pause-named -->
```clojure
(register gear (cyl 10 4))

(anim! :spin 6.0 :gear
  (span 1.0 :linear (tr 360)))

(play! :spin)

;; Freeze the animation in place — playhead stays where it is
(pause! :spin)

;; ... inspect the frozen pose, then resume
(play! :spin)
```
<!-- /example-source -->

`pause!` freezes the mesh in its current animated pose; the base mesh state is preserved so a later `stop!` can restore the original.

## Notes

- **State transitions.** `:playing → :paused` on `pause!`; `:paused → :playing` on `play!`; `:stopped` is untouched.
- **No-op on unknown names.** `(pause! :missing)` does nothing rather than throwing.

## See also

- **Related:** `play!`, `stop!`
