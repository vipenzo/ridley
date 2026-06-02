---
name: link!
category: positioning-assembly
since: ""
status: stable
---

# link!

## Signature

`(link! child-target parent-target)`
`(link! child-target parent-target :at parent-anchor)`
`(link! child-target parent-target :at parent-anchor :from child-anchor)`
`(link! child-target parent-target :at parent-anchor :inherit-rotation true)`

## Description

Bind a child target to a parent target so the child inherits the parent's runtime delta during animation playback. Each frame, the playback engine computes the parent's translation (and optionally rotation) since the start of the timeline and applies the same delta to the child — turning two independently-animated meshes into a parent/child rig.

Linking is an **animation-time** binding: at construction time the meshes are not moved. Animation preprocessing computes each target's per-frame frame as if no links existed; the link adds the parent's runtime offset at playback. Targets are processed in topological order (parents before children), so chains of links resolve correctly.

The default form `(link! :child :parent)` follows the parent's centroid translation. The options refine that:

- `:at parent-anchor` — track a named anchor on the parent (set via `attach-path` or `with-path`) instead of the centroid. Use when the connection point on the parent is a specific feature rather than the bbox centre.
- `:from child-anchor` — specify which anchor on the child should coincide with the parent anchor. Without `:from`, the child's creation-pose is the connection point.
- `:inherit-rotation true` — also inherit the parent's rotation delta. When the parent rotates 30°, the child rotates 30° around the parent's pivot. Default `false` (translation only).

For static (non-animated) assembly, `link!` is **not** the right tool — it does nothing at construction time. Use `move-to … :at … :align` or the path-driven `with-path` / `goto` pattern instead.

## Parameters

- `child-target` — keyword identifying the child target (a registered mesh, camera, or other animatable entity).
- `parent-target` — keyword identifying the parent.
- `:at parent-anchor` — keyword: a named anchor on the parent. Optional.
- `:from child-anchor` — keyword: a named anchor on the child. Optional.
- `:inherit-rotation bool` — propagate rotation deltas as well as translation. Default `false`.

## Example

{{example: link-bang-camera-follow}}

<!-- example-source: link-bang-camera-follow -->
```clojure
;; A moving box, with the camera tracking it
(register box (box 10))

(anim! :slide 4.0 :box :in-out
  (span 1.0 (f 40))
  (span 1.0 (th 90)))

;; The camera target follows the box's translation
(link! :camera :box)

(play! :slide)
```
<!-- /example-source -->

The animation moves `:box` along a two-segment timeline. `link!` binds the camera to it; on `play!`, the camera tracks the box's centroid each frame.

## Variations

{{example: link-bang-skeleton-joint}}

<!-- example-source: link-bang-skeleton-joint -->
```clojure
;; Lower arm follows the elbow anchor of the upper arm
(register upper (extrude (circle 1.5) (f 15)))
(attach-path :upper (path (mark :elbow) (f 15) (mark :wrist)))

(register lower (extrude (circle 1.2) (f 10)))

;; Static snap to the elbow first, then bind for animation
(attach! :lower (move-to :upper :at :wrist :align))

;; Inherit translation AND rotation, so the lower arm swings with the upper
(link! :lower :upper :at :wrist :inherit-rotation true)

(anim! :wave 3.0 :upper :in-out
  (span 1.5 (th 45)))

(play! :wave)
```
<!-- /example-source -->

The lower arm is snapped to the upper arm's `:wrist` anchor for the static rig. `link!` with `:inherit-rotation true` then makes it follow both translation and rotation during the animation: as the upper arm yaws 45°, the lower arm yaws with it around the wrist anchor.

## Notes

- **Animation-only effect.** `link!` does not move meshes at construction time. The static placement still needs `move-to` / `attach!` — `link!` is purely about the runtime delta inherited during `play!` / `seek!`.
- **Topological resolution.** Chains (`A → B → C`) resolve in parent-before-child order each frame. Cycles will hang playback; do not chain a child back to its own ancestor.
- **Targets are keywords.** Both `child-target` and `parent-target` are keyword identifiers, matching the registry and the animation system's binding table.
- **`:at` requires the anchor to exist on the parent.** Set it with `attach-path` (or build the parent inside a `with-path`). Without `:at`, the parent's centroid is the tracking point.
- **Remove a link with `unlink!`.** A `link!` overwrites any previous link for the same child; `unlink!` clears it.

## See also

- **Related:** `unlink!`, `attach-path`, `anchors`, `move-to`, `anim!`,
  `play!`
