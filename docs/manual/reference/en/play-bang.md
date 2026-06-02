---
name: play!
category: live-interactive
since: ""
status: stable
---

# play!

## Signature

`(play!)`
`(play! :name)`

## Description

Start playback of a registered animation. Without arguments, every registered animation is started. With a keyword name, only the matching animation is started.

If the animation is currently `:stopped`, `play!` first re-captures the base mesh state (vertices, faces, creation-pose) from the registry so the animation always runs against the live scene rather than against a stale snapshot taken at `anim!` time. If the animation is `:paused`, playback resumes from the current frame.

When multiple animations share the same target mesh, `play!` reuses the base state from any already-playing sibling, so two animations on `:gear` will not fight over the base capture.

## Parameters

- `:name` — keyword name passed to `anim!` or `anim-proc!`. Omit to start every registered animation.

## Example

{{example: play-named}}

<!-- example-source: play-named -->
```clojure
(register gear (cyl 10 4))

(anim! :spin 3.0 :gear
  (span 1.0 :linear (tr 360)))

;; Start the named animation
(play! :spin)
```
<!-- /example-source -->

The animation starts from frame 0 (or resumes from the paused frame). The viewport renders each preprocessed pose without re-evaluating the spans.

## Variations

{{example: play-all}}

<!-- example-source: play-all -->
```clojure
(register gear (cyl 10 4))
(register orbiter (sphere 3))

(anim! :spin 3.0 :gear
  (span 1.0 :linear (tr 360)))

(anim-proc! :pulse 1.0 :orbiter :in-out :loop
  (fn [t] (sphere (+ 2 (* 2 (sin (* t PI 2)))))))

;; Start every animation in the registry at once
(play!)
```
<!-- /example-source -->

The zero-arity form is useful when a scene defines several animations together and you want a single "go" command to kick them all off.

## Notes

- **Stopped vs paused.** `play!` on a `:stopped` animation re-captures the base mesh state. `play!` on a `:paused` animation simply flips the state back to `:playing` and continues from where it left off.
- **Shared base across siblings.** Multiple animations on the same target share a single base capture; whichever one starts first sets it, and the others reuse it until all of them stop.
- **No-op on unknown names.** `(play! :missing)` does nothing rather than throwing — handy for scripts that conditionally register animations.

## See also

- **Related:** `pause!`, `stop!`, `seek!`, `anim-list`
