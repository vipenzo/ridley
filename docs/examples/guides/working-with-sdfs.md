# Working with SDFs

> A potent but unruly modeling tool. What it gives you, what it takes away, and how to organize your work around its quirks.

## When to reach for SDFs (and when not)

SDFs (signed distance fields) are a different way of representing solids: instead of a list of triangles, every shape is a function that returns, for any point in space, the signed distance to its surface — negative inside, positive outside. The trick is that this representation makes some operations almost free that on meshes are hard or expensive: smooth fusions between shapes, uniform offsets, half-space intersections, blends that ease one form into another.

Reach for SDFs when:

- The form is **organic** — sculptural, not mechanical. Shapes that flow into each other, with continuous transitions rather than sharp edges.
- You want **soft fusion** between parts (`sdf-blend`, `sdf-blend-difference`). The result is a single continuous surface, not a boolean seam.
- You need **uniform offset** of an arbitrary shape (a clearance, a shell). Trivial in SDFs, painful with meshes.
- You're **carving** with half-space intersections — keep only the lower half of a cylinder, slice a shape against an arbitrary plane.

Stay with meshes when:

- The part has **mechanical detail** — countersunk holes, threaded inserts, exact fillets at known radii.
- You need **flat faces guaranteed** — a mounting surface, a bed-contact face for printing, a planar interface that mates with another part.
- The geometry is **dimensionally constrained** — "this hole is 5 mm, this wall is 2 mm thick, this distance is exactly 30 mm" — and any deviation is wrong.
- You expect to **animate** or `tweak` parameters live: meshes regenerate fast enough; SDFs at usable resolution don't.

The wall mount in `03_guitar_wall_mount.clj` is the canonical mixed case. The cradle that wraps the guitar neck is unmistakably an SDF problem — there is no clean way to model that flowing wrap with mesh booleans. The screw holes with countersinks, and the flat back face that sits against the wall, are unmistakably mesh problems. The pilot uses both, in that order.

## Blend: the sculptor's knob

`sdf-blend` is the single most distinctive SDF operator. Given two shapes and a radius `k`, it returns a shape that is the union of the two — but with a smooth fillet of "size" `k` along the seam where they meet. At `k = 0` you get an ordinary union with a hard crease; as `k` grows, the seam thickens, the fillet rounds out, and at large `k` the two shapes melt into a single blob with the original silhouettes barely recognisable.

There is no formula for the right `k`. It depends on the relative size of the two shapes, on how perpendicularly they meet, on what the surrounding surfaces look like, on what the part needs to feel like. It is a knob you turn by ear.

The wall mount uses three blends with three different radii:

```clojure
(def body (sdf-blend manico paletta 7))           ; neck-to-headstock
(def struttura (sdf-blend (anelli ...) (anelli ...) 10))   ; backing rings
(sdf-blend struttura base 5)                      ; backing-to-base
```

Each radius was chosen by trying values, regenerating, looking, and adjusting. Not by computation.

The implication is methodological: **change one blend value at a time**. If you change two simultaneously and the result looks worse, you cannot tell which one is responsible. With one change at a time, every regeneration teaches you something. With two, you are guessing.

## The exploratory workflow, and why `tweak` doesn't work here

`tweak` is meant for live parameter tuning: you wrap a literal number in a slider, drag it, and watch the model update in real time. It is the ideal interface for parametric work — when the regeneration is fast.

With SDFs at production resolution (200, meshes of millions of triangles), regeneration takes seconds. The slider becomes a way to fire off a regeneration you didn't intend, then another, then another, while the viewport struggles to catch up. The feedback loop that makes `tweak` worth having is broken.

In practice, editing the number directly in the source — `7` to `8`, save, rebuild — is faster and more deliberate. One regeneration, one decision, one comparison. The slowness imposes a discipline that is, on reflection, well-suited to SDF tuning: you cannot survey a range in a glance, so you choose a value, wait, look, choose the next. This pairs naturally with the *one-parameter-at-a-time* rule above.

The combined practice: pick the parameter you're tuning now (one blend radius, one offset, one position), edit by hand, regenerate, judge. Resist the urge to "just also try" something else in the same pass. With SDFs, parallelism in tuning is self-sabotage.

## The blend inflates everywhere (and sometimes you don't want that)

`sdf-blend` operates on the distance field globally. When two shapes meet, it doesn't only soften the seam — it bulges the surface outward in every direction near the seam, including directions you'd rather it left alone. The blend doesn't know which side of the assembly is "the front" or "the back"; it has no notion of a face that should stay flat.

The wall mount hit this directly. The back of the part needs to sit flush against a wall — a hard requirement, not an aesthetic preference. But every blend in the model contributes a bit of outward swell, and the cumulative effect on the back surface is a gentle, irregular bulge that no amount of further blend tuning can flatten. Within the SDF world, there is no operator for "soften here but stay rigorously planar there".

The escape is to leave the SDF world. The last lines of the wall mount do exactly this:

```clojure
(register WallMount
  (mesh-intersection
    (mesh-difference
      wallmount                       ; the SDF result, now meshed
      (concat-meshes ... screw-holes))
    (attach (box 180 110 180) (u -30))))   ; <-- the planar cut
```

That last `mesh-intersection` with a box clips the entire part against a planar bounding region. The bulged back face is sliced off cleanly; what remains is a flat face that the printer (and the wall) can rely on.

The general principle: **SDFs decide how form flows; meshes decide where form ends**. When you need a hard, exact boundary — a flat face, a precise cut, a defined edge — convert to mesh and impose the boundary there. Trying to coax the SDF into producing the boundary itself is a losing battle.

## Idiom: half-space via clip

A useful primitive that SDFs make trivial: the half-space. The dedicated operators are `sdf-half-space` (the half-space itself) and `sdf-clip` (intersect a shape with the half-space behind the turtle's heading).

The wall mount uses this to model the neck — a *half-cylinder*, the back side of a guitar neck:

```clojure
(turtle (tv 90)                                         ; turtle looks +Z
  (sdf-clip (sdf-rotate (sdf-cyl r_manico l_manico) :y 90)))
```

`sdf-clip` reads the turtle's pose at call time. The cut plane passes through the turtle position with normal equal to the heading; the half kept is the one *behind* the heading. With the turtle at the origin facing `+z`, that's `z ≤ 0` — the lower half of the cylinder. The `(turtle ...)` wrapper scopes the rotation so global turtle state stays untouched.

For the rare case of keeping the *front* half, the keyword form is explicit:

```clojure
(sdf-intersection shape (sdf-half-space :cut-ahead))
```

Same idiom works for clipping at any plane — move and orient the turtle so its position lies on the cut plane and its heading points away from the side you want to keep. No oversized box, no magic numbers, no `sdf-move` to position a bounding region. Implemented as a single dot product per voxel rather than a `max` of six plane evaluations.

> Historical note: before `sdf-half-space` landed, the canonical pattern was to intersect with an oversized box (`(sdf-box 1000 ... 1000)`) shifted to align one face with the cut plane. It worked, but the box dimensions were always relative to the rest of the model. Old code following that pattern is still correct — it just has more knobs.

## Idiom: offset as clearance

Another operation that SDFs make almost trivial: uniform offset. Adding a constant to a distance field grows or shrinks the implicit surface by exactly that amount, in every direction, on every shape, however irregular.

The wall mount uses this for assembly clearance:

```clojure
(sdf-offset (chitarra-sdf) 1.5)
```

The cradle has to be slightly larger than the guitar that slides into it — 1.5 mm of slop, all around the surface. Computing this on the mesh of an irregular shape (the neck-plus-headstock blob) would be an ugly job: surface offsets are notoriously prone to self-intersection. On the SDF, it's adding 1.5 to the field. One token.

Whenever you need a uniform inflation or deflation of an arbitrary form — clearances, shells, dilation/erosion — this is qualitatively easier in SDF than in mesh, not just incrementally.

## Idiom: SDF-then-mesh hybrid for mechanical detail

The wall mount illustrates a pattern worth naming: **organic shape lives in SDF, mechanical detail lives in mesh, and the hand-off is one-way**.

The structure:

1. Build all the soft, blended, offset, sculptural form as SDFs. Keep them composed via `sdf-union`, `sdf-blend`, `sdf-intersection`, `sdf-offset`.
2. At a chosen point, the SDF result is meshed (implicitly, when it's first used in a mesh-world operator).
3. From there onward, work in mesh: `mesh-difference` for screw holes drawn with the turtle, `mesh-intersection` for planar cuts, `attach` for placing precision components.

```clojure
(def hole
  (mesh-union
    (cyl 2 5 64)
    (attach (cone 8 2 5 64) (f 2.5))
    (attach (cyl 10 100 64) (f 52.5))))

(register WallMount
  (mesh-intersection
    (mesh-difference
      wallmount
      (concat-meshes
        (attach hole (f -40) (rt 50)  (tv 90) (f -83))
        (attach hole (f -40) (rt -50) (tv 90) (f -83))
        (attach hole (f 30)           (tv 90) (f -83))))
    (attach (box 180 110 180) (u -30))))
```

The `hole` is a counter-sunk screw hole built with ordinary turtle moves, three primitives unioned together. It would be perverse to model that as an SDF — the geometry is exact and rigid, the depths and diameters are dimensions, not feelings. So it is a mesh, and the wall mount is `mesh-difference`d against three placed copies.

Practical rule: **close the SDF phase as soon as the form is decided**. Keep further work in mesh. The temptation to add "just one more SDF operation" after the mesh transition is mostly nostalgia; if you find yourself wanting to do it, it usually means you're about to undo a planar cut or a precise hole that would be much harder to redo from scratch.

## Resolution as a workflow choice

`sdf-resolution!` governs the trade-off between iteration speed and feedback fidelity. The natural strategy is to scale resolution alongside the scale of the work: start low when you're still deciding the overall form (blocks fusing, masses, proportions), and ramp up as the work shifts to refinement (blend radii, fillets, edge details).

There is, however, a **floor** to respect. Around 100, meshing artefacts (terracing, quantised micro-facets, small bumps where the surface runs nearly tangent to the grid) start to be on the same scale as the details you are trying to evaluate. Below that threshold, you are looking at noise, not form, and you risk "correcting" things that don't exist in the model — only in the mesh. The practical rule: **keep resolution just above the scale of the detail you're currently judging**. Coarse decisions → 100–120, plenty. Fine blend tuning → 150–200. Final render or printable-model validation → 200+, accepting the time cost.

Ramping up at the end is almost always the right move. Starting high "to be safe" is the most common trap: slow regeneration discourages exploration, and high resolution does not help you decide whether two masses should blend at 5 or at 7. Resolution is for *seeing* the answer, not for *finding* it.

## Common pitfalls

- **Changing more than one blend parameter per regeneration.** You lose track of which change did what, and the next decision is a guess.

- **Expecting the blend to respect local constraints.** Flat faces, sharp edges, exact perpendicularities — `sdf-blend` doesn't know about any of these. It is global and isotropic. If you need a planar feature, plan to leave SDF for a `mesh-intersection`.

- **Staying in SDF "for consistency" when a mesh detail would be immediate.** Countersunk holes, dovetails, threaded bosses, square keys — model them with the turtle, mesh-difference them in. Trying to express them in SDF wastes time and produces approximations.

- **Working at high resolution during exploration.** Each regeneration is a tax on iteration. You'll iterate less and decide worse.

- **Working at very low resolution (under 100) during refinement.** Artefacts at the same scale as the details you're judging will mislead you. The fix you "see" disappears when you finally render at proper resolution.

- **Using `tweak` on SDF parameters.** The refresh isn't real-time at usable resolution. Editing the number by hand and rebuilding is faster, more deliberate, and produces a cleaner mental record of what changed and why.

- **Trying to flatten a blended face from inside SDF.** It cannot be done. Convert to mesh, intersect with a planar bounding box, move on.

## See also

- Spec, *SDF* — full reference for `sdf-cyl`, `sdf-box`, `sdf-blend`, `sdf-offset`, `sdf-intersection`, `sdf-rotate`, `sdf-move`, `sdf-blend-difference`, and the meshing bridge.
- `defining-2d-profiles.md` — the other side of modelling, where shapes are *contours* rather than *fields*.
- `03_guitar_wall_mount.clj` — full pilot example using all the patterns above.