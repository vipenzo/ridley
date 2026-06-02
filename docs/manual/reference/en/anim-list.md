---
name: anim-list
category: live-interactive
since: ""
status: stable
---

# anim-list

## Signature

`(anim-list)`

## Description

Return a vector of maps describing every registered animation, one map per animation. Each map carries the animation's name, type (`:preprocessed` for `anim!`, `:procedural` for `anim-proc!`), target mesh, duration, current playback state, loop mode, current time offset, and total preprocessed frame count.

Useful for live introspection at the REPL: confirming that an animation got registered, checking what is currently playing, or feeding the names into a `doseq` to control them in bulk.

## Example

{{example: anim-list-basic}}

<!-- example-source: anim-list-basic -->
```clojure
(register gear (cyl 10 4))
(register orbiter (sphere 3))

(anim! :spin 3.0 :gear :loop
  (span 1.0 :linear (tr 360)))

(anim-proc! :pulse 1.0 :orbiter :in-out :loop
  (fn [t] (sphere (+ 2 (* 2 (sin (* t PI 2)))))))

(play!)

(anim-list)
;; =>
;; [{:name :spin   :type :preprocessed :target :gear     :duration 3.0
;;   :state :playing :loop :forward :current-time 0.0 :total-frames 180}
;;  {:name :pulse  :type :procedural   :target :orbiter  :duration 1.0
;;   :state :playing :loop :forward :current-time 0.0 :total-frames nil}]
```
<!-- /example-source -->

The map structure is stable; downstream code can pluck `:name` and pass it to `play!` / `pause!` / `stop!` to script bulk control.

## Notes

- **Returns a vector**, not a sequence — safe to index and `count` without realizing.
- **`:total-frames` is `nil` for procedural animations.** They have no preprocessed frame array.
- **`:loop`** is `:forward`, `:reverse`, `:bounce`, or `false` (one-shot).
- **Order is registration order**, not playback order or alphabetical.

## See also

- **Related:** `anim!`, `anim-proc!`, `play!`
