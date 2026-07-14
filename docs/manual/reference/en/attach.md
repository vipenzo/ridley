---
name: attach
category: positioning-assembly
since: ""
status: stable
---

# attach

## Signature

`(attach mesh-or-sdf & body)`
`(attach meshes & body)` — a vector or map of meshes, moved as one rigid group

## Description

Apply a sequence of turtle commands to a mesh, panel, or SDF and
return the transformed value. The original is unchanged.

`attach` also accepts a **collection of meshes** — a vector, or a map (name
→ mesh, e.g. a `mesh-board` tree value): the whole collection moves as one
rigid group, replaying `body` once against the first mesh's shared
creation-pose and applying the resulting transform to every mesh's vertices
and creation-pose alike. Relative disposition within the group is preserved.
The container shape is preserved too — a map goes in, a map with the same
keys comes out; a vector goes in, a vector comes out (never unwrapped, even
for a single element). An empty collection returns empty, unchanged. Any
other unsupported argument — a map that isn't a mesh collection, or a
foreign type — is a readable error naming what was received, never a silent
no-op.

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

- `mesh-or-sdf` — a mesh, an SDF node, a panel, or a vector/map of meshes
  (moved together as a rigid group — see above).
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

{{example: attach-group}}

<!-- example-source: attach-group -->
```clojure
;; A map of meshes (e.g. a mesh-board decomposition tree) moves as one
;; rigid group — relative disposition preserved, container shape preserved
(def piece-1 (box 10 10 10))
(def piece-2 (attach piece-1 (f 20)))
(def t {:piece-1 piece-1 :piece-2 piece-2})
(def moved (attach t (f 10) (th 90)))
(mesh-board moved)
```
<!-- /example-source -->

Re-positioning a whole `mesh-board` tree at once: transform the data, not
the display — `(mesh-board (attach t (f 10)))` shows the scaffold where the
moved tree now lives.

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
  `transform`, `path`, `mesh-board`
