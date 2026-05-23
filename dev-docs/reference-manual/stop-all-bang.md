---
name: stop-all!
category: live-interactive
since: ""
status: stable
---

# stop-all!

## Signature

`(stop-all!)`

## Description

Stop every registered animation and reset every target mesh to its base state. Equivalent to calling `(stop!)` with no arguments. The most useful single-call "stop everything and reset the scene" command.

For each animation, the playhead returns to frame 0, the mesh is restored to its captured base vertices and creation-pose, and `:camera` targets get OrbitControls re-enabled.

## Example

{{example: stop-all}}

<!-- example-source: stop-all -->
```clojure
(register gear (cyl 10 4))
(register orbiter (sphere 3))

(anim! :spin 3.0 :gear :loop
  (span 1.0 :linear (tr 360)))

(anim-proc! :pulse 1.0 :orbiter :in-out :loop
  (fn [t] (sphere (+ 2 (* 2 (sin (* t PI 2)))))))

(play!)

;; Stop everything in one call — both meshes return to their base state
(stop-all!)
```
<!-- /example-source -->

Useful at the start of a re-eval session to clear leftover animations before redefining them.

## Notes

- **Idempotent.** Calling `stop-all!` on an empty registry is a no-op.
- **Does not unregister.** Animations remain in the registry after `stop-all!`; `play!` can restart them. Use `(anim-list)` to inspect what is still around.

## See also

- **Related:** `stop!`
