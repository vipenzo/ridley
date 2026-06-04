---
name: cp-f
category: positioning-assembly
since: ""
status: stable
---

# cp-f / cp-rt / cp-u

## Signature

`(cp-f dist)`
`(cp-rt dist)`
`(cp-u dist)`

Available inside `attach` / `attach!` bodies (also `path`, where
they are recorded for later replay).

## Description

Slide the geometry under a stationary anchor — translate the mesh
(or SDF) by `-dist` along the chosen local axis while the
creation-pose stays put. The result: a specific feature of the mesh
now coincides with the anchor instead of whatever was originally at
the construction origin.

Three commands, one per local axis:

| Command       | Direction (in turtle frame) | Mesh slides       |
|---------------|-----------------------------|-------------------|
| `(cp-f dist)` | heading                     | `-dist` along heading |
| `(cp-rt dist)`| right                       | `-dist` along right   |
| `(cp-u dist)` | up                          | `-dist` along up      |

This is the position-shift half of the creation-pose family. The
rotation half — `cp-th` / `cp-tv` / `cp-tr` — re-anchors the
ORIENTATION of the geometry under the same anchor. See
[cp-rotation](cp-rotation.md).

The natural use case is "what part of this mesh should snap to its
attachment point?". A cylinder built with the default cyl is
attached at one of its caps; `(cp-u 5)` shifts the geometry so the
midpoint is now at the anchor instead.

## Parameters

- `dist` — signed distance, in turtle units. Positive: the chosen
  feature moves `+dist` along the axis; the geometry slides
  `-dist` (i.e. the anchor effectively migrates to a deeper part of
  the mesh).

## Example

{{example: cp-position-basic}}

<!-- example-source: cp-position-basic -->
```clojure
;; Center a cylinder vertically so a later rotate pivots at the middle
(register col
  (attach (cyl 5 20)
    (cp-u 10)))     ; geometry slides -10 along U; midpoint is now at the anchor

(register tilted (rotate col :y 30))
(hide :col)
```
<!-- /example-source -->

Without `cp-u`, the cylinder is anchored at its bottom; rotating
around Y pivots there. With `(cp-u 10)`, the anchor lands at the
midpoint, and the rotation feels visually centered.

## Variations

{{example: cp-position-chain}}

<!-- example-source: cp-position-chain -->
```clojure
;; Re-anchor an off-center extrusion so its tip lines up with the origin
(register hook
  (attach (extrude (circle 1) (f 30) (th 60) (f 20))
    (cp-f 30)))      ; the original endpoint of (f 30) is now the anchor
```
<!-- /example-source -->

The `cp-*` family lets you re-pick which point of the geometry
coincides with the creation-pose, without rebuilding the mesh.

## Notes

- All three commands chain in the ORIGINAL creation-pose frame:
  a `cp-rt` after a `cp-f` slides along the original right axis,
  not along the post-`cp-f` one. Same convention as `cp-th` /
  `cp-tv` / `cp-tr` for rotations.
- For SDFs, the same effect: the SDF tree is translated by `-dist`
  along the chosen axis; the SDF's pose is unchanged. Useful when
  composing SDF assemblies that expect a feature (a planar face, a
  tip) to coincide with the origin.
- The companion rotation family is documented in
  [cp-rotation](cp-rotation.md).

## See also

- **Related:** `cp-rotation`, `attach`, `attach!`, `move-to`,
  `reset-creation-pose`
