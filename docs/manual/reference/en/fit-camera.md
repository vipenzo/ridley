---
name: fit-camera
category: registration-visibility
since: ""
status: stable
---

# fit-camera

## Signature

`(fit-camera)`

## Description

Frame the viewport camera to fit all currently visible geometry. The
camera distance and target are recomputed from the union bounding box
of every visible object (registered and anonymous). No arguments, no
return value worth using — call for the effect. Does not modify turtle
state.

## Parameters

None.

## Example

{{example: fit-camera-basic}}

<!-- example-source: fit-camera-basic -->
```clojure
(register frame (extrude (rect 200 200) (f 50)))
(register knob (do (f 10) (sphere 5)))
(fit-camera)
;; viewport zoomed to include both meshes
```
<!-- /example-source -->

After registering geometry that doesn't fit the current view, call
`fit-camera` to recenter and rescale.

## Notes

- Hidden objects are ignored — only what is currently rendered
  contributes to the bounding box.
- For a permanent visibility toggle of construction lines (without
  camera movement), see `show-lines` / `hide-lines`.

## See also

- **Related:** `bounds`, `show-all`, `show-only-objects`
