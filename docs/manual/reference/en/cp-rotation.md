---
name: cp-th
category: positioning-assembly
since: ""
status: stable
---

# cp-th / cp-tv / cp-tr

## Signature

`(cp-th angle)`
`(cp-tv angle)`
`(cp-tr angle)`

Available inside `attach` / `attach!` bodies (also `path`, where
they are recorded for later replay).

## Description

Rotate the geometry under a stationary anchor — the mesh (or SDF)
rotates by `-angle` around the chosen local axis while the
creation-pose's orientation stays fixed in world. The geometry
spins beneath the anchor; the anchor still points where it pointed
before.

Three commands, one per local axis:

| Command         | Rotation axis (turtle frame) | Geometry rotates by |
|-----------------|------------------------------|---------------------|
| `(cp-th angle)` | up                           | `-angle` around up  |
| `(cp-tv angle)` | right                        | `-angle` around right |
| `(cp-tr angle)` | heading                      | `-angle` around heading |

This is the rotation half of the creation-pose family. The position
half — `cp-f` / `cp-rt` / `cp-u` — re-anchors the POSITION of the
geometry under the same anchor. See [cp-position](cp-position.md).

The rotation variants matter when the anchor's orientation will be
read by downstream operations — most notably `move-to`, which adopts
the anchor's heading/up. Rotating the geometry with `cp-th` leaves
the anchor pointing the way it did before, so a later `move-to` of
this mesh into another assembly behaves as if no rotation had
happened.

## Parameters

- `angle` — signed rotation angle, in degrees. Positive: the
  anchor's frame would rotate `+angle`; the geometry rotates
  `-angle` to compensate.

## Example

{{example: cp-rotation-basic}}

<!-- example-source: cp-rotation-basic -->
```clojure
;; Rotate a bracket under a fixed-orientation anchor:
;; the geometry tilts, the anchor still points along +X.
(register bracket
  (attach (extrude (rect 8 3) (f 15))
    (cp-tv 30)))   ; geometry pitches -30; anchor's frame unchanged

;; A later move-to that adopts this anchor will face +X, not the
;; rotated direction.
(register socket (box 20))
(attach! :socket (move-to :bracket))
```
<!-- /example-source -->

Without `cp-tv`, the bracket's anchor would point along the rotated
heading. With it, the geometry rotates while the anchor stays
neutral — useful when the anchor's orientation must remain
predictable for downstream assemblies.

## Variations

{{example: cp-rotation-twist}}

<!-- example-source: cp-rotation-twist -->
```clojure
;; Roll the geometry under the anchor: useful for matching one
;; mesh's roll to another's at attach time
(register beam
  (attach (extrude (rect 6 2) (f 25))
    (cp-tr 45)))  ; geometry rolls -45° around heading
```
<!-- /example-source -->

`cp-tr` is the roll variant: rotate the mesh around its heading
axis without changing the anchor's heading/up. Useful when two
mating parts must share a heading but differ in roll.

## Notes

- Chains in the ORIGINAL creation-pose frame: a `cp-tv` after a
  `cp-th` rotates around the original right axis, not the
  post-`cp-th` right axis. Same convention as the position family.
- For SDFs, the same effect: the SDF tree is rotated by `-angle`;
  the SDF's pose orientation is unchanged. Compose with `cp-position`
  to fully re-anchor an SDF before booleans.
- The position-shift companions are documented in
  [cp-position](cp-position.md).

## See also

- **Related:** `cp-position`, `attach`, `attach!`, `move-to`,
  `reset-creation-pose`
