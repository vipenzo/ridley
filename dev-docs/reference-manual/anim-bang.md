---
name: anim!
category: live-interactive
since: ""
status: stable
---

# anim!

## Signature

`(anim! :name duration :target & spans)`
`(anim! :name duration :target :loop & spans)`
`(anim! :name duration :target :loop-reverse & spans)`
`(anim! :name duration :target :loop-bounce & spans)`
`(anim! :name duration :target :fps N & spans)`

## Description

Define a timeline-based animation: a named sequence of `span` segments that move, rotate, and otherwise drive a target mesh (or the camera) through turtle-style commands. Spans are preprocessed once into per-frame pose arrays for O(1) playback. The animation is registered under `:name` and can be played, paused, seeked, and stopped through the `play!` / `pause!` / `stop!` / `seek!` family.

`:target` is the keyword name of a registered mesh, or the literal keyword `:camera`. When the target is `:camera`, the turtle commands inside the spans are reinterpreted as **cinematic camera operations**: `rt`/`lt` orbit horizontally around the current OrbitControls pivot, `u`/`d` orbit vertically, `f` dollies toward or away from the pivot, `th`/`tv` pan and tilt the look direction, and `tr` rolls. The pivot is captured from the viewport at registration time.

Loop modes are mutually exclusive: `:loop` repeats forward (0 → 1, 0 → 1, …), `:loop-reverse` repeats backward (1 → 0, 1 → 0, …), and `:loop-bounce` ping-pongs (0 → 1 → 0 → 1, …). The default `:fps` is 60.

## Parameters

- `:name` — keyword name used by `play!`, `pause!`, `stop!`, and `seek!`.
- `duration` — total length in seconds (the sum of span weights is normalized to 1.0).
- `:target` — keyword name of a registered mesh, or `:camera`.
- `:loop` / `:loop-reverse` / `:loop-bounce` — optional loop mode.
- `:fps N` — optional frame rate; default `60`.
- `spans` — one or more `(span weight easing & commands)` forms (see `span`).

## Example

{{example: anim-spin}}

<!-- example-source: anim-spin -->
```clojure
(register gear (extrude (star :points 8 :outer 12 :inner 8) (f 6)))

;; One-shot rotation: 360 degrees in 3 seconds, linear
(anim! :spin 3.0 :gear
  (span 1.0 :linear (tr 360)))

(play! :spin)
```
<!-- /example-source -->

A single span carries the full rotation. After `(play! :spin)` the gear spins once and stops; calling `(play! :spin)` again replays from frame 0.

## Variations

{{example: anim-entrance}}

<!-- example-source: anim-entrance -->
```clojure
(register gear (extrude (star :points 8 :outer 12 :inner 8) (f 6)))

;; Multi-span sequence with easing: pop in, spin twice, settle
(anim! :entrance 8.0 :gear
  (span 0.10 :out    (f 6))
  (span 0.80 :linear (tr 720))
  (span 0.10 :in     (f -6)))

(play! :entrance)
```
<!-- /example-source -->

Span weights are normalized: `0.10 + 0.80 + 0.10` becomes 12.5% / 75% / 12.5% of the 8-second duration. Easings shape the inner pacing of each segment without affecting the overall length.

{{example: anim-camera-orbit}}

<!-- example-source: anim-camera-orbit -->
```clojure
;; Cinematic mode: commands are reinterpreted as camera operations
;; rt = orbit horizontally around the current OrbitControls pivot
(anim! :cam-orbit 5.0 :camera
  (span 1.0 :in-out (rt 360)))

(play! :cam-orbit)
```
<!-- /example-source -->

With `:target :camera`, `(rt 360)` orbits the camera around the scene's pivot point rather than rotating any mesh. Combine with `f` (dolly) and `u`/`d` (vertical orbit) for cinematic moves.

{{example: anim-loop-bounce}}

<!-- example-source: anim-loop-bounce -->
```clojure
(register gear (extrude (circle 10) (f 6)))

;; Sway back and forth between 0 and +90 degrees, forever
(anim! :sway 2.0 :gear :loop-bounce
  (span 1.0 :in-out (tr 90)))

(play! :sway)
```
<!-- /example-source -->

`:loop-bounce` makes the timeline ping-pong: 0 → 1 → 0 → 1. Combined with `:in-out` easing it produces a smooth pendulum motion.

## Notes

- **Preprocessing happens at registration time.** Spans are walked once to produce per-frame pose arrays, so playback is allocation-free. If you need a fresh preprocess after editing the span list, re-evaluate the `anim!` form.
- **Span weights are relative.** They do not have to sum to 1.0 — Ridley normalizes them. `(span 1 ...) (span 2 ...) (span 1 ...)` is equivalent to `(span 0.25 ...) (span 0.5 ...) (span 0.25 ...)`.
- **Camera mode uses the live pivot.** The orbit pivot is sampled from `OrbitControls` when `anim!` runs, not at `play!` time. Re-evaluate the form if you move the pivot and want the animation to follow.
- **For per-frame mesh regeneration**, use `anim-proc!` instead; `anim!` only moves the existing mesh, it does not rebuild geometry every frame.
- **Linking targets.** `link!` makes one animation target follow another at playback time (e.g. camera follows a box) without re-preprocessing.
- **Export.** Currently only `anim-proc!` animations can be exported as GIF (`anim-export-gif`).

## See also

- **Related:** `anim-proc!`, `span`, `play!`, `pause!`, `stop!`, `seek!`, `link!`, `ease`
