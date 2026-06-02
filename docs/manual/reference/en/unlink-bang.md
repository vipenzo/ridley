---
name: unlink!
category: positioning-assembly
since: ""
status: stable
---

# unlink!

## Signature

`(unlink! child-target)`

## Description

Remove a previously installed animation link from a child target. The child is no longer driven by any parent at playback; subsequent `play!` / `seek!` calls treat its frames as the preprocessing produced them.

`unlink!` is the inverse of `link!`. There is no concept of a partial unlink: every option-tuple recorded by the previous `link!` call (parent target, parent anchor, child anchor, `:inherit-rotation`) is removed in one go. To change one option, call `link!` again with the new options — the new link overwrites the old.

## Parameters

- `child-target` — keyword identifying the child whose link should be removed. No-op if no link is currently installed for that target.

## Example

{{example: unlink-bang-basic}}

<!-- example-source: unlink-bang-basic -->
```clojure
(register box (box 10))
(anim! :slide 2.0 :box :linear (span 1.0 (f 30)))

;; Camera follows the box…
(link! :camera :box)

;; …until we change our mind
(unlink! :camera)
```
<!-- /example-source -->

After `unlink!`, the camera no longer inherits the box's delta on playback. The animation on the box itself is untouched — `unlink!` only removes the parent/child binding.

## Notes

- **Idempotent.** Calling `unlink!` on a target that has no link is a no-op; no error is raised.
- **No partial unlink.** To switch parent or change `:at` / `:from` / `:inherit-rotation`, call `link!` again with the new options. The new entry replaces the previous one.
- **Does not affect static placement.** Like `link!`, `unlink!` only touches the animation link table. The current world position of the child mesh is unchanged.

## See also

- **Related:** `link!`, `attach-path`, `anchors`, `move-to`, `anim!`,
  `play!`
