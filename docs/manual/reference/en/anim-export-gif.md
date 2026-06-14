---
name: anim-export-gif
category: export
since: ""
status: desktop-only
---

# anim-export-gif

## Signature

`(anim-export-gif "filename.gif")`
`(anim-export-gif "filename.gif" & options)`

## Description

Render the current procedural (`anim-proc!`) animation to an animated GIF. Available only in the Ridley Desktop build; the browser build raises an error.

Capture is off-realtime: the live render loop is suspended and frames are generated as fast as the system can compute the procedural mesh. A progress overlay covers the viewport during capture and encoding. The output is written to `~/Documents/Ridley/exports/<filename>` (parent directories are created automatically).

When more than one procedural animation is registered, name the primary one with `:anim`. Its duration determines the total capture length.

## Parameters

| Option       | Default                  | Description                                                                                                                                                  |
|--------------|--------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `:fps`       | `15`                     | Frames per second.                                                                                                                                           |
| `:duration`  | animation's own duration | Capture length in seconds.                                                                                                                                   |
| `:width`     | `720`                    | Output width in pixels; height matches the viewport aspect ratio.                                                                                            |
| `:anim`      | auto-pick                | Primary animation name (keyword). Required only when more than one procedural animation is registered. Determines capture length.                            |
| `:overwrite` | `false`                  | When `true`, replace an existing file at the target path. When `false`, a collision raises an error.                                                         |

## Example

{{example: anim-export-gif-basic}}

<!-- example-source: anim-export-gif-basic -->
```clojure
;; Register the target mesh, then animate it procedurally
(register part (box 20 20 5))

;; (anim-proc! :name duration :target easing [loop-mode] gen-fn)
;; gen-fn returns a fresh mesh each frame; it replaces the :part target.
(anim-proc! :spin 6.0 :part :linear :loop
  (fn [t]
    (attach part (tr (* t 360)))))

(play! :spin)

(anim-export-gif "spin.gif"
                 :fps 15
                 :duration 6
                 :width 720)
;; Writes to ~/Documents/Ridley/exports/spin.gif
```
<!-- /example-source -->

A simple spinning box: the `gen-fn` returns the box rolled by `t · 360°` each frame, replacing the registered `:part` target. `:loop` keeps it spinning and `:linear` gives constant speed. Then `anim-export-gif` drives it off-realtime for 6 seconds at 15 fps and produces a 720-pixel-wide GIF.

## Variations

{{example: anim-export-gif-multi}}

<!-- example-source: anim-export-gif-multi -->
```clojure
;; Two procedural animations in lockstep: pick the primary with :anim
(register ring (cyl 20 3))
(register slider (box 5 5 5))

(anim-proc! :ring 6.0 :ring :linear :loop
  (fn [t] (attach ring (tr (* t 360)))))

(anim-proc! :slider 6.0 :slider :linear :loop
  (fn [t] (attach slider (f (* t 30)))))

(play! :ring)
(play! :slider)

(anim-export-gif "combo.gif"
                 :anim :ring
                 :fps 30
                 :width 1080
                 :overwrite true)
```
<!-- /example-source -->

Every procedural animation in the `:playing` state is driven in lockstep at the same fractional `t` each frame, not just the named one. The `:anim` keyword names the primary animation whose duration governs total length; the others run on the same fractional timeline so animations with matching durations progress in lockstep. `:overwrite true` allows replacing an existing file.

## Notes

- **Desktop only.** The browser build does not implement off-realtime GIF capture; calling this function there raises an error.
- **Multi-anim capture.** All procedural animations in `:playing` are captured together. Static supports, rotating rings, and sliding parts can all appear in the same GIF as their own registered targets.
- **Keyframe `anim!` is not supported.** GIF export only handles procedural (`anim-proc!`) animations.
- **Error cases:**
  - non-procedural animation (keyframe `anim!`) is not supported
  - multiple procedural animations require `:anim <name>`
  - file collision raises an error unless `:overwrite true` is passed
- The file lives under `~/Documents/Ridley/exports/`. The parent directories are created if missing.

## See also

- **Related:** `anim-proc!`, `play!`
