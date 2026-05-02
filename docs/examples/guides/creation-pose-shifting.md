# Creation-pose shifting

> Use `cp-f` / `cp-rt` / `cp-u` to relocate a mesh's anchor point so it snaps to the right place at compose time.

## When to use this

Every mesh has an anchor: a point and orientation it carries with itself, used whenever something else attaches to it. The anchor is the turtle's pose at the moment the mesh was built (or the moment of its most recent `attach`). What varies between mesh types is *where the geometry sits relative to that anchor*:

- **Primitives** (`box`, `cyl`, `sphere`, `cone`) are centred on the anchor — the geometry's centroid coincides with it.
- **SDF primitives** are placed with their `[0, 0, 0]` at the anchor.
- **Extrusions and lofts** (`extrude`, `loft`, `revolve`) start *at* the anchor — their first cross-section sits on it, and the geometry grows forward from there.

Sometimes the default anchor lines up with the point you actually care about. Often it doesn't.

Reach for creation-pose shifting when:

- A component should attach to another component at a point that isn't where the default anchor lands — a hinge pivot at the top of a box, a peg's bottom face, a mounting surface on the side.
- You want to *rotate* a mesh around a specific point (not the default anchor). The pivot of any rotation applied via `attach` is the mesh's creation pose. Move the creation pose, and you move the pivot.
- You're snapping components onto a skeleton via `move-to` and the natural anchor of each component would land in the wrong place.

The alternative — leaving the creation pose at the default and compensating with extra `(f …)` or rotations at compose time — works but scatters geometry across the codebase. With creation-pose shifted to the right place, the compose-time call stays clean: `(attach component (move-to skel :at :mark))` and nothing more.

## How it works

A mesh's creation pose is a transform tag attached to the mesh, separate from the geometry. It says: *"if anyone attaches to me, here is the pose they should snap onto."*

The `cp-*` family slides the geometry under the (stationary) anchor so that a chosen feature point comes to coincide with it:

- `cp-f n` — re-anchor at the point `+n` along heading from the original anchor (geometry slides backward by `n` along heading).
- `cp-u n` — re-anchor at the point `+n` along up (geometry slides down by `n`).
- `cp-rt n` — re-anchor at the point `+n` along right (geometry slides left by `n`).

The anchor stays put in world. The geometry moves under it. Net effect: the original local point at `+n` along the chosen axis now coincides with the anchor — so future attachers land there.

The effect shows up later, when something else uses the mesh:

```clojure
;; A box with creation pose at its centre (default for primitives).
(def box-default (box 40 40 40))

;; Same box, but with creation pose shifted up by 20
;; — i.e., to the centre of the top face.
(def box-top-anchor
  (attach (box 40 40 40) (cp-u 20)))
```

If you `attach` something onto `box-default` at the origin, it lands at the box's centre. If you `attach` onto `box-top-anchor` at the origin, it lands on the top face. Same geometry, different anchor.

This is also what makes rotation work intuitively. A rotation applied via `attach` pivots around the mesh's creation pose:

```clojure
(attach mesh (tv 30))      ; pivots around mesh's creation pose
```

If the creation pose is at the centre, the mesh rotates around its centre. If you've shifted the creation pose to the pivot point you actually care about — a hinge pin, a corner, a contact face — the rotation pivots there.

## Example: hinge rotation around a pin

The pilot example `assembly/01-hinge-c-profile.cljs` builds two C-shaped shells that pivot around a shared pin axis. The inner shell is built as a primitive box, so its default anchor is at the box's centre. But the natural pivot of the assembly — the pin — sits at the top of the box, `H/2` away from the centre.

Without a creation-pose shift, rotating the inner shell to open the hinge would pivot around the box's centre. The shell would swing through the outer shell's walls:

```clojure
;; WRONG — anchor at the box centre. Rotation pivots there, not at the pin.
(def inner-shell-naive (box inner-w inner-d H))

(attach inner-shell-naive (tv 60))   ; pivots around centre — clips
```

With `cp-f` shifting the anchor to the pin axis, the same rotation pivots around the pin instead, and the hinge opens correctly:

```clojure
;; RIGHT — anchor shifted to the pin axis. Rotation pivots there.
(def inner-shell
  (attach (box inner-w inner-d H)
          (cp-f (- (/ H 2)))))

(attach inner-shell (tv 60))   ; pivots around the pin
```

Here is the rotation in action:

![Hinge rotation around the pin](../assembly/media/hinge-rotation.gif)

The inner C rotates from 0° (closed) through 60° (operating) to 180° (fully open) and back. The pivot is the pin, not the box centre — that's the creation-pose shift at work. Replace `(cp-f (- (/ H 2)))` with no shift at all, and the animation breaks: the inner C swings through the outer C's walls.

## Example: anchoring at a face

A different use case: putting a peg on top of a plate.

```clojure
;; A flat plate, anchor at the centre by default.
(def plate (box 100 100 5))

;; A peg, anchor shifted to its bottom — so attaching it
;; somewhere lands its bottom on the target, not its midpoint.
(def peg (attach (cyl 8 30) (cp-f -15)))
;(def peg (cyl 8 30)) ; WRONG!

;; Now snap the peg onto the top face of the plate, at a corner.
(register Assembly
  (mesh-union
   plate
    (attach peg (rt (- 50 8)) (u (- 50 8)))))

```

Two creation-pose decisions are happening:

- The plate's anchor stays at its centre. We use the plate as the reference.
- The peg's anchor is shifted to its bottom. When attached, the peg's bottom lands on the target point, not its midpoint.

Without the shift on the peg, its centre would coincide with the plate's top face, and the peg would be sunk halfway into the plate.

## Common pitfalls

- **Confusing `cp-*` with positioning moves.** `cp-f n` re-picks the anchor: it slides the geometry under a stationary anchor so the local point at `+n` along heading lands on it. `(attach mesh (f n))` translates the *mesh* forward by `n` at compose time, keeping the anchor at the centroid (or wherever it was). Both look similar in code but they live in different phases — `cp-*` runs at creation and is "baked in" for all later uses; `f` is a one-shot translation at compose. Mix them up and you'll compensate twice.

- **Forgetting that rotation pivots around the creation pose.** If a rotation looks wrong — pivots somewhere unexpected, swings the mesh off into space — check the creation pose first. Most of the time the rotation is correct and the pose is in the wrong place.

- **Compensating with a positioning move when `cp-*` would do.** Writing `(attach (box …) (f offset))` to "shift the box so it sits the way I want when later attached" works, but it bakes the offset into compose-time code. `cp-f` records the same shift on the mesh itself, so the compose-time call stays clean.

## See also

- `skeleton-driven-assembly.md` — the broader pattern this technique supports: components anchor onto a skeleton, and the creation pose decides *where* on each component the anchor is.
- `assembly/01-hinge-c-profile.cljs` — full pilot example. The inner shell uses `cp-f` to put its anchor at the pin axis; the rotation animation in section 10 only works because of this.