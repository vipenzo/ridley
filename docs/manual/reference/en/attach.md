---
name: attach
category: positioning-assembly
since: ""
status: stable
---

# attach

## Signature

`(attach mesh-or-sdf & body)`

## Description

Apply a sequence of turtle commands to a mesh, panel, or SDF and
return the transformed value. The original is unchanged.

`attach` wraps `body` in a `(path ...)` and replays it on a virtual
turtle attached to the value. Movement commands translate the
geometry along the turtle's local frame; rotation commands rotate it
around the current turtle position. Curve commands (`arc-h`,
`bezier-to`, …) decompose into elementary `f` + rotation steps.
Attach-specific commands (`move-to`, `play-path`, `stretch-*`) and
creation-pose shifts (`cp-*`) are all available. Plain `scale` is
rejected — use `stretch-f` / `stretch-rt` / `stretch-u` for
local-axis scaling.

For an in-place transformation of a registered mesh (writes back to
the registry), see `attach!`.

### Available commands inside `attach` / `attach!`

| Group           | Commands                                                       | Effect                                                                                               |
|-----------------|----------------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| Movement        | `(f dist)`, `(u dist)` / `(d dist)`, `(rt dist)` / `(lt dist)` | Translate the value along the turtle's heading / up / right.                                         |
| Rotation        | `(th angle)`, `(tv angle)`, `(tr angle)`                       | Yaw / pitch / roll around the turtle's up / right / heading axis at the current turtle position.     |
| Curves          | `(arc-h r ang)`, `(arc-v r ang)`, `(bezier-to …)`, `(bezier-as p)` | Decompose into f + rotation; same effect as the equivalent sequence of primitives.            |
| Anchor recording| `(mark :name)`                                                 | Record an anchor at the current pose, attached to the value.                                          |
| Attach-specific | `(move-to …)`, `(play-path p)`                                 | Snap to another object's pose / replay a path. See `move-to` for details.                            |
| Local scaling   | `(stretch-f f)`, `(stretch-rt f)`, `(stretch-u f)`             | Scale along the current turtle's heading / right / up. Pivots at the current turtle position.        |
| Creation-pose   | `(cp-f d)` / `(cp-rt d)` / `(cp-u d)`, `(cp-th α)` / `(cp-tv α)` / `(cp-tr α)` | Slide / rotate the geometry under a stationary anchor. See [cp-position](cp-position.md) and [cp-rotation](cp-rotation.md). |
| Rejected        | `(scale …)`                                                    | Throws — use `stretch-*` instead.                                                                    |

For SDF inputs, the same commands all work and operate incrementally
on the SDF tree: each command transforms the tree directly. `mark`,
`move-to`, and the `cp-*` family carry the same meaning as for
meshes.

## Parameters

- `mesh-or-sdf` — a mesh, an SDF node, or a panel.
- `body` — one or more turtle / attach commands, exactly the same
  forms accepted inside a `path` body, plus the attach-specific
  commands listed in the table above.

## Example

{{example: attach-basic}}

<!-- example-source: attach-basic -->
```clojure
(register cube (box 10))

;; Functional: returns a transformed copy under a new name
(register moved (attach cube (f 25) (th 45) (tv 20)))
(hide :cube)
```
<!-- /example-source -->

A cube is moved forward 25, yawed 45°, and pitched 20°. The result
is a new mesh with the transform baked into its vertices.

## Variations

{{example: attach-stretch}}

<!-- example-source: attach-stretch -->
```clojure
;; Stretch along the local heading after rotation — world-axis scale
;; would not do this without re-orienting the geometry first
(register stretched
  (attach (box 10)
    (th 30) (tv 20)
    (stretch-f 2.5)))
```
<!-- /example-source -->

`stretch-f` scales the mesh along the current turtle's local
heading. Local-axis scaling is the reason `scale` itself is rejected
inside `attach` — `stretch-*` is the body-aware replacement.

{{example: attach-cp-shift}}

<!-- example-source: attach-cp-shift -->
```clojure
;; cp-f slides the geometry under the anchor: useful to align a
;; specific feature (here, the top face) with the creation-pose.
(register cup
  (attach (cyl 8 20)
    (cp-u 10)))    ; geometry slides -10 along U; anchor stays at origin

;; Subsequent in-place rotate now pivots at the cup's top.
(register tilted (rotate cup :y 30))
(hide :cup)
```
<!-- /example-source -->

The creation-pose shift family (`cp-f` / `cp-rt` / `cp-u`) is the
attach-time tool for re-anchoring a feature. See
[cp-position](cp-position.md) and [cp-rotation](cp-rotation.md).

{{example: attach-move-to}}

<!-- example-source: attach-move-to -->
```clojure
;; Snap one mesh onto another's pose, then build further from there
(register base (box 30))
(attach! :base (th -90) (f 40))   ; base moved out along Y

(register sfera (sphere 6))
(attach! :sfera (move-to :base) (tv 90) (f 25))
```
<!-- /example-source -->

`move-to` is the cleanest way to position one object relative to
another: it both translates and re-orients the turtle frame so
subsequent commands operate in the target's coordinate system.

## Notes

- `attach` is functional: it returns a new value without touching
  the registry. To overwrite a registered mesh in place, use
  `attach!`.
- The body is a path recording context: `f`, `th`, `tv`, etc. are
  REBOUND inside it. To call a turtle helper function that uses the
  global bindings, wrap it with `play-path`:
  `(attach m (play-path (my-helper-path)))`.
- `scale` is the only common command rejected inside `attach`. Use
  `stretch-f` / `stretch-rt` / `stretch-u` for local-axis scaling,
  or apply `scale` to the result outside the attach.
- The path commands operate INCREMENTALLY: each command transforms
  the value in turn, so `(attach m (th 30) (f 10))` is "rotate, then
  move", not "compose then apply".
- The §11 Spec table lists every command's effect on SDFs in
  exhaustive detail.

## See also

- **Related:** `attach!`, `move-to`, `play-path`, `stretch-f`,
  `stretch-rt`, `stretch-u`, `cp-position`, `cp-rotation`,
  `transform`, `path`
