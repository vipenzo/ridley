---
name: anim-proc!
category: live-interactive
since: ""
status: stable
---

# anim-proc!

## Signature

`(anim-proc! :name duration :target easing gen-fn)`
`(anim-proc! :name duration :target easing :loop gen-fn)`
`(anim-proc! :name duration :target easing :loop-reverse gen-fn)`
`(anim-proc! :name duration :target easing :loop-bounce gen-fn)`

## Description

Define a **procedural** animation: a generator function that returns a brand-new mesh on every frame, replacing the registered target. Unlike `anim!`, which preprocesses a fixed pose timeline and only moves vertices, `anim-proc!` calls `gen-fn` each frame with the eased fraction `t ∈ [0, 1]` and expects it to return a fully rebuilt mesh.

This is the right tool when the geometry itself changes — radius, segment count, topology — not just the transform. Typical uses are pulsing primitives, parametric morphs, and effects driven by `sin` / `cos` / `noise` on `t`.

Loop modes (`:loop`, `:loop-reverse`, `:loop-bounce`) work the same way as in `anim!`. The easing keyword (`:linear`, `:in-out`, `:bounce`, …) is applied to the raw fraction before being passed to `gen-fn`, so the function always sees a curve-shaped `t`.

## Parameters

- `:name` — keyword name used by `play!`, `pause!`, `stop!`, and `seek!`.
- `duration` — total length in seconds (or one cycle, when looping).
- `:target` — keyword name of a registered mesh whose value will be replaced each frame.
- `easing` — easing keyword applied to the timeline fraction before `gen-fn`. See `ease` for the full list.
- `:loop` / `:loop-reverse` / `:loop-bounce` — optional loop mode.
- `gen-fn` — function `(fn [t] -> mesh)` called every frame with the eased `t`.

## Example

{{example: anim-proc-grow}}

<!-- example-source: anim-proc-grow -->
```clojure
(register blob (sphere 1))

;; Grow from radius 1 to radius 20 in 3 seconds, decelerating
(anim-proc! :grow 3.0 :blob :out
  (fn [t] (sphere (+ 1 (* 19 t)))))

(play! :grow)
```
<!-- /example-source -->

`gen-fn` returns a fresh sphere on every frame with an interpolated radius. The `:out` easing makes the growth start fast and ease into the final size.

## Variations

{{example: anim-proc-bend}}

<!-- example-source: anim-proc-bend -->
```clojure
(register arm (extrude (circle 2) (f 15) (f 12)))

;; Bend at the elbow: the second segment rotates from 0 to 90 degrees
(anim-proc! :bend 2.0 :arm :in-out
  (fn [t] (extrude (circle 2) (f 15) (th (* t 90)) (f 12))))

(play! :bend)
```
<!-- /example-source -->

Each frame rebuilds the extrusion with a different bend angle. Cheap because `extrude` is fast and the profile is constant — only the turtle path changes.

{{example: anim-proc-pulse}}

<!-- example-source: anim-proc-pulse -->
```clojure
(register heart (box 10 10 10))

;; Pulse forever: scale oscillates with sin(2 pi t)
(anim-proc! :pulse 1.0 :heart :in-out :loop
  (fn [t]
    (let [s (+ 1.0 (* 0.3 (sin (* t PI 2))))]
      (box (* 10 s) (* 10 s) (* 10 s)))))

(play! :pulse)
```
<!-- /example-source -->

A one-second loop modulates the box dimensions with a sine. `:loop` re-runs forward each cycle, so `t` jumps from 1 back to 0 between cycles; use `:loop-bounce` if the discontinuity matters.

{{example: anim-proc-breathe}}

<!-- example-source: anim-proc-breathe -->
```clojure
(register blob (sphere 5))

;; Bounce: t goes 0→1→0→1→..., so the sphere grows and shrinks smoothly
(anim-proc! :breathe 2.0 :blob :in-out :loop-bounce
  (fn [t] (sphere (+ 5 (* 15 t)))))

(play! :breathe)
```
<!-- /example-source -->

`:loop-bounce` ping-pongs the fraction without ever resetting, so the size transition stays smooth across cycles — no need to encode the oscillation in `gen-fn` yourself.

## Notes

- **The target must be a registered mesh.** `:target` names a mesh that already exists in the scene via `(register <name> …)`. `anim-proc!` replaces that registered mesh each frame — it does not create one. Pointing `:target` at a bare `defn` (a function, not a registered mesh) registers nothing in the scene, so nothing renders and the animation has no mesh to update. Always `(register …)` the target first.
- **Performance budget.** `gen-fn` runs every frame (default 60 fps). Keep it cheap: avoid `mesh-union`, `mesh-difference`, `mesh-hull`, and other CSG ops inside the function. Vertex transforms, profile changes, and pure extrusions are usually fast enough.
- **Constant face count helps.** When the mesh keeps the same `:faces` topology between frames, Ridley can take a fast path that updates vertex buffers in place. Changing point counts (e.g. `(circle 12)` one frame, `(circle 24)` the next) forces a full re-upload.
- **`gen-fn` sees the eased `t`.** If you want the raw linear fraction, pass `:linear` as the easing.
- **No spans, no parallel.** `anim-proc!` does not use `span` or `parallel`; the entire timeline is the single `gen-fn`. Switch to `anim!` when you need preprocessed keyframes with per-segment easing.
- **Export.** Procedural animations can be saved as GIF via `anim-export-gif` (desktop only).

## See also

- **Related:** `anim!`, `play!`, `pause!`, `stop!`, `seek!`, `link!`, `ease`, `anim-export-gif`
