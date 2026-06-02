---
name: span
category: live-interactive
since: ""
status: stable
---

# span

## Signature

`(span weight easing & commands)`
`(span weight easing :ang-velocity N & commands)`
`(span weight easing :on-enter expr & commands)`
`(span weight easing :on-exit expr & commands)`

## Description

Macro that defines one segment of a timeline-based animation, used inside `anim!`. A span has a **weight** (relative share of the total duration), an **easing** (pacing curve), and a body of turtle commands that drive the target mesh — or the camera, when the parent `anim!` targets `:camera`.

The body accepts the standard turtle vocabulary: `f`, `u`, `d`, `rt`, `lt`, `th`, `tv`, `tr`. It also recognizes a special sub-form, `parallel`, that runs its inner commands simultaneously over the same frames (see Variations).

Optional keyword arguments can appear anywhere in the body:

- `:ang-velocity N` controls rotation pacing. The default `1` means a 360° rotation takes the same time as `(f N)`, so rotations are visible. `0` makes rotations instantaneous. Larger `N` makes rotations relatively slower.
- `:on-enter expr` runs `expr` when the playhead enters the span.
- `:on-exit expr` runs `expr` when the playhead leaves the span.

## Parameters

- `weight` — number; relative share of the parent `anim!`'s duration. Weights across spans are normalized to sum to 1.0.
- `easing` — one of `:linear`, `:in`, `:out`, `:in-out`, `:in-cubic`, `:out-cubic`, `:in-out-cubic`, `:spring`, `:bounce`. See `ease` for the curves.
- `:ang-velocity N` — optional; rotation pacing relative to translation (default `1`).
- `:on-enter expr` / `:on-exit expr` — optional callbacks fired once when the playhead crosses the span boundary.
- `commands` — turtle commands and `parallel` sub-forms.

## Example

{{example: span-basic}}

<!-- example-source: span-basic -->
```clojure
(register gear (cyl 10 4))

;; A 4-second animation made of three spans
(anim! :wiggle 4.0 :gear
  (span 1 :out     (f 10))      ;; 25% of duration, decelerating dolly
  (span 2 :in-out  (tr 360))    ;; 50% of duration, smooth full rotation
  (span 1 :in      (f -10)))    ;; 25% of duration, accelerating return

(play! :wiggle)
```
<!-- /example-source -->

Weights `1 / 2 / 1` are normalized to 25% / 50% / 25% of the 4 s duration. Each span carries its own easing, applied to the local progress within that segment.

## Variations

{{example: span-parallel}}

<!-- example-source: span-parallel -->
```clojure
(register box1 (box 10))

;; Sequential: orbit first, then elevate (allocations split across the span)
(anim! :seq 2.0 :box1
  (span 1.0 :linear (rt 360) (u 90)))

;; Parallel: both happen over the same frames — diagonal corkscrew
(anim! :para 2.0 :box1
  (span 1.0 :linear (parallel (rt 360) (u 90))))
```
<!-- /example-source -->

`parallel` is recognized **only inside a `span` body**, never at the top level. A parallel group's frame allocation equals the max of its sub-commands, and all sub-commands advance at the same fractional progress per frame. Use it whenever you want two motions to overlap instead of chain.

{{example: span-ang-velocity}}

<!-- example-source: span-ang-velocity -->
```clojure
(register top (cyl 5 20))

;; Snap-rotate at the start, then dolly: :ang-velocity 0 zero-cost rotation
(anim! :snap 2.0 :top
  (span 1.0 :linear :ang-velocity 0 (tr 90) (f 20)))

(play! :snap)
```
<!-- /example-source -->

With `:ang-velocity 0`, the rotation consumes no frame budget — it happens instantly at the start of the span, and the entire span is spent on the translation that follows. Default `:ang-velocity 1` would have made the rotation last as long as `(f 1)`.

{{example: span-callbacks}}

<!-- example-source: span-callbacks -->
```clojure
(register cube (box 10))

(anim! :announce 3.0 :cube
  (span 1.0 :in-out
        :on-enter (out "entering span")
        :on-exit  (out "leaving span")
        (tr 360)))

(play! :announce)
```
<!-- /example-source -->

`:on-enter` and `:on-exit` are evaluated once per crossing of the span boundary. Useful for logging, sound triggers, or chaining external state changes alongside the geometric animation.

## Notes

- **`span` is body-only inside `anim!`.** Calling it at the top level is meaningless: it produces a span value that nobody consumes.
- **`parallel` is body-only inside `span`.** It is not a standalone binding; the macro recognises the symbol when expanding span commands.
- **Weights are relative.** Use any numbers — Ridley divides each by the sum. `(span 1 :linear ...) (span 1 :linear ...)` and `(span 50 :linear ...) (span 50 :linear ...)` produce the same timing.
- **Easing curves.** All easings map `t ∈ [0, 1] → [0, 1]`. `:spring` and `:bounce` overshoot during the curve but settle at exactly `1` at the end. Use `ease` to preview a curve numerically.
- **Camera-mode reinterpretation.** When the parent `anim!` targets `:camera`, turtle commands inside spans become cinematic operations: `rt`/`lt` orbit horizontally, `u`/`d` orbit vertically, `f` dollies, `th`/`tv` pan/tilt, `tr` rolls. The pivot is the OrbitControls target at registration time.

## See also

- **Related:** `anim!`, `ease`
