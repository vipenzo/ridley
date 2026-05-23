---
name: show-turtle
category: registration-visibility
since: ""
status: stable
---

# show-turtle / hide-turtle

## Signature

`(show-turtle keyword)`
`(show-turtle mesh)`
`(hide-turtle)`

## Description

Toggle the on-screen turtle indicator — the XYZ axes gizmo that displays the turtle's pose at its creation time. `show-turtle` makes the gizmo visible and pins it to a specific mesh's creation pose; `hide-turtle` removes the gizmo from the viewport.

The argument to `show-turtle` can be either:

- a **keyword** naming a registered mesh, in which case the gizmo snaps to that mesh's `:creation-pose`, or
- a **mesh value** that carries a `:creation-pose` (any mesh produced by a Ridley primitive does); the gizmo snaps to that pose directly.

`hide-turtle` takes no argument and simply switches the indicator off.

## Parameters

- `keyword` — the registry key of a mesh (`:torus`, `:left-arm`, …).
- `mesh` — a mesh map with a `:creation-pose` entry.

## Example

{{example: turtle-visibility-basic}}

<!-- example-source: turtle-visibility-basic -->
```clojure
;; Register a tilted cylinder, then pin the turtle indicator to its origin
(reset)
(tv 30) (f 10)
(register pipe (cyl 4 20))

(show-turtle :pipe)    ;; XYZ gizmo appears at the cylinder's creation pose
;; ... inspect orientation ...
(hide-turtle)          ;; remove it from the viewport
```
<!-- /example-source -->

The gizmo confirms where the cylinder was placed and how it was oriented at construction time — handy when debugging assemblies built from many primitives.

## Notes

- `show-turtle` throws if given a value that is neither a keyword nor a mesh carrying `:creation-pose`. The error message says exactly that.
- The setting is global: only one turtle indicator is shown at a time, and re-calling `show-turtle` retargets the existing gizmo rather than adding a second one.
- Useful in tandem with `fit-camera` when probing the pose of an off-screen mesh: target the camera at the gizmo, then read off the axes directly.

## See also

- **Related:** `show-lines`, `hide-lines`, `fit-camera`
