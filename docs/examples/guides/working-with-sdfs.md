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

The wall mount in `03_guitar_wall_mount.clj` is a good mixed case. The cradle that wraps the guitar neck is unmistakably an SDF problem — there is no clean way to model that flowing wrap with mesh booleans. The screw holes with countersinks, and the flat back face that sits against the wall, are unmistakably mesh problems. The pilot uses both, and the boundary between the two worlds is porous: the same turtle vocabulary works on both sides (see *Unifying mesh and SDF* below).

## Blend: the sculptor's knob

`sdf-blend` is the single most distinctive SDF operator. Given two shapes and a radius `k`, it returns a shape that is the union of the two — but with a smooth fillet of "size" `k` along the seam where they meet. At `k = 0` you get an ordinary union with a hard crease; as `k` grows, the seam thickens, the fillet rounds out, and at large `k` the two shapes melt into a single blob with the original silhouettes barely recognisable.

There is no formula for the right `k`. It depends on the relative size of the two shapes, on how perpendicularly they meet, on what the surrounding surfaces look like, on what the part needs to feel like. It is a knob you turn by ear.

The wall mount uses several blends with different radii — 1 between the inner ring and the spheres at its tips, 1 between the rings and the base structure, 7 between the neck and the headstock, 2 in the soft cavity carved by `sdf-blend-difference`. Each radius was chosen by trying values, regenerating, looking, and adjusting. Not by computation.

The implication is methodological: **change one blend value at a time**. If you change two simultaneously and the result looks worse, you cannot tell which one is responsible. With one change at a time, every regeneration teaches you something. With two, you are guessing.

## The exploratory workflow, and what tweak can (and can't) do for you

`tweak` is meant for live parameter tuning: you wrap a literal number in a slider, drag it, and watch the model update in real time. With meshes, this is essentially instantaneous and the slider feels like a knob. With SDFs the situation is more nuanced, and worth understanding before reaching for the slider.

At a working resolution of 100, a regeneration takes a couple of seconds — slow enough that you cannot drag the slider continuously and watch the form follow your finger, but fast enough that nudging a value, waiting a beat, and seeing the result is a workable feedback loop. At production resolution (200+, millions of triangles), the loop stretches to several seconds per step, and the slider stops being meaningfully more convenient than editing the number in the source. The crossover is somewhere in between, and depends on the size of your model.

So `tweak` on SDF parameters is *usable*, with two caveats:

- **Use it at exploration resolution, not production resolution.** The same logic that governs the resolution-as-workflow choice (below) applies here doubly. Tweaking at 200 is asking the slider to do what the slider can't do. Tweaking at 100 is fine.

- **Watch out for the feedback ambiguity.** When a regeneration takes one or two seconds, you can lose track of whether the system is working on your last change or has already finished and is waiting for the next. Sometimes a small parameter change produces no visible effect (the blend was already saturated, or the change is below the resolution's perceptual threshold), and without an indicator that says *"working… done"*, you sit there waiting for something to happen that already happened. This is a UI gap more than an SDF problem, and worth knowing about so you don't misread silence as work-in-progress.

The deeper rule, independent of `tweak`, is the one-parameter-at-a-time discipline from the previous section. Whether you're using a slider or editing literals by hand, change one thing per regeneration. The slider doesn't relax that rule; it just makes the per-step cost smaller.

## The blend inflates everywhere (and sometimes you don't want that)

`sdf-blend` operates on the distance field globally. When two shapes meet, it doesn't only soften the seam — it bulges the surface outward in every direction near the seam, including directions you'd rather it left alone. The blend doesn't know which side of the assembly is "the front" or "the back"; it has no notion of a face that should stay flat.

The wall mount hit this directly. The back of the part needs to sit flush against a wall — a hard requirement, not an aesthetic preference. But every blend in the model contributes a bit of outward swell, and the cumulative effect on the back surface is a gentle, irregular bulge that no amount of further blend tuning can flatten. Within the SDF world, there is no operator for "soften here but stay rigorously planar there".

The escape is to leave the SDF world. The last lines of the wall mount do exactly this:

```clojure
(register WallMount
  (mesh-union
    anello-mobile                          ; placed separately
    (mesh-intersection
      (mesh-difference
        wallmount                          ; the SDF result, now meshed
        ...)                               ; screw holes & ring slot
      (attach (box 180 110 180) (u -30))))) ; <-- the planar cut
```

That last `mesh-intersection` with a box clips the entire part against a planar bounding region. The bulged back face is sliced off cleanly; what remains is a flat face that the printer (and the wall) can rely on.

The general principle: **SDFs decide how form flows; meshes decide where form ends**. When you need a hard, exact boundary — a flat face, a precise cut, a defined edge — convert to mesh and impose the boundary there. Trying to coax the SDF into producing the boundary itself is a losing battle.

## Unifying mesh and SDF: the polymorphic core

The mesh and SDF worlds share a single vocabulary for transformation and assembly. The same operators work on both:

- `translate`, `rotate`, `scale` are **polymorphic**: they dispatch on whether their argument is a mesh, an SDF, or a 2D shape.
- **`attach` works on SDFs.** The full turtle vocabulary inside an `attach` body is available: movement (`f`, `rt`, `u`), rotation (`th`, `tv`, `tr`), creation-pose shifts (`cp-f`, `cp-rt`, `cp-u`), `mark`, and `move-to` (with or without `:align`). Two commands are rejected on SDFs: `inset` (no SDF analogue) and the legacy single-argument `(scale n)` inside `attach` (use the top-level `scale` outside the attach).
- **Anchors carry across the SDF–mesh boundary.** `mark` records anchors on an SDF; those anchors survive through SDF booleans and through the eventual conversion to mesh.

In practice this means you can place an SDF primitive at a turtle pose without manually composing translates and rotates. The wall mount uses this to put a sphere at each tip of an arc-shaped torus:

```clojure
(defn anelli [n p R r sz arc-deg arc-rot]
  (let [...
        s1 (sdf-sphere (* r 1.3))]
    (sdf-blend
      (reduce sdf-union (map anello (range n)))
      (sdf-union
        (attach s1 (tv 90) (th (+ 180 (/ arc-deg 2))) (f R))
        (attach s1 (tv 90) (th (+ 180 (/ arc-deg -2))) (f R)))
      1)))
```

Read the two `attach` calls turtle-first: pitch up by 90°, yaw to the angular endpoint of the arc, walk forward by R (the major radius of the torus). Drop a sphere there. This is exactly the same vocabulary you'd use to place a feature on a mesh.

The practical consequence is that **most of the assembly can happen inside the SDF world**, with smooth fusions and clean booleans, and the hand-off to mesh is driven by the *kind* of operation you need next — mesh booleans against pre-built mesh parts, planar cuts, mechanical detail — rather than by ergonomic friction. The next sections walk through the patterns where SDF stays superior, and the patterns where mesh stays superior.

## Idiom: half-space and clipping

Half-space cuts come up constantly when sculpting with SDFs — keeping one side of a cylinder, slicing a blob against a plane, flattening a region against a known boundary. Two operators, both turtle-relative, cover the common cases: `sdf-half-space` returns the half-space defined by the turtle's current pose (cut plane through the turtle, normal along the heading), and `sdf-clip` clips a given shape against that plane.

The wall mount uses this to build the neck — a *half-cylinder*, the back side of a guitar neck:

```clojure
(let [cilindro (rotate (sdf-cyl r_manico l_manico) :y 90)
      sotto    (turtle (tv 90) (sdf-half-space))   ; isolated turtle scope
      manico   (sdf-intersection cilindro sotto)] ...)
```

The `(turtle (tv 90) (sdf-half-space))` form is worth dwelling on. The `turtle` macro creates an isolated turtle scope: the rotation `(tv 90)` only applies inside it, and the outer turtle state is left untouched. Without the scope, the `tv 90` would leak into whatever turtle code runs next. With the scope, the half-space construction is *local* — a small parenthesized intent — and the surrounding code stays clean. This is the recommended idiom.

The convention is the same as `extrude`'s: the turtle "looks out of the material". After an `extrude`, the turtle sits with the new solid behind it; calling `(sdf-half-space)` at that pose returns exactly the half-space containing the material. The same intuition carries over: at any point, the turtle pose specifies a cutting plane, and the half-space *behind* the turtle is the one that's kept.

For the rare case where you want the front half instead, `(sdf-half-space :cut-ahead)` flips the convention. `sdf-clip` doesn't take that option — if you need to keep the front, use the explicit form:

```clojure
(sdf-intersection shape (sdf-half-space :cut-ahead))
```

**Composition: arcs from a torus.** Two half-spaces combined are a *wedge*. The wall mount uses this to cut arc-shaped pieces out of a torus:

```clojure
(defn torus-arc [R r arc-deg arc-rot]
  (let [half (/ arc-deg 2)
        h1 (turtle (th (+ 90 half))     (sdf-half-space))
        h2 (turtle (th (- (+ 90 half)))  (sdf-half-space))]
    (rotate
      (sdf-intersection
        (sdf-torus R r)
        (if (<= arc-deg 180)
          (sdf-intersection h1 h2)   ; narrow wedge: the slice we want
          (sdf-union h1 h2)))        ; wide arc: complement of the missing slice
      :z arc-rot)))
```

The two half-spaces, oriented symmetrically around the heading, define a wedge of opening angle `arc-deg`. When the arc is less than 180°, the wedge is narrow — what we want to keep — so we *intersect*. When the arc is more than 180°, the "wedge" of the missing slice would be the narrow one; we want everything else, so we *union* the two complementary half-spaces. The same two anchor pieces, glued together by `intersection` or `union`, cover the whole 0°–360° range.

This is a small but transferable pattern. Anywhere you'd reach for "keep / discard a wedge of a body", `(intersection h1 h2)` or `(union h1 h2)` of two turtle-aimed half-spaces will do the job.

## Idiom: offset as clearance

Another operation that SDFs make almost trivial: uniform offset. Adding a constant to a distance field grows or shrinks the implicit surface by exactly that amount, in every direction, on every shape, however irregular.

The wall mount uses `sdf-offset` twice for two different kinds of clearance.

**Clearance for the guitar.** The cradle has to be slightly larger than the guitar that slides into it — a couple of millimeters of slop, all around the surface, so the user can drop the instrument in without forcing it. Computing this on the mesh of an irregular shape (the neck-plus-headstock blob) would be ugly: surface offsets are notoriously prone to self-intersection. On the SDF, it's adding 1.5 to the field:

```clojure
(sdf-blend-difference
  (sdf-blend struttura base 1)
  (sdf-offset (chitarra-sdf) 1.5)        ; <-- inflated guitar
  2)
```

**Clearance for a mating part.** The wall mount has a *moving ring* (`anello-mobile`) — a separate piece that slides through a slot in the body to hold the guitar in place. The slot has to be slightly bigger than the ring itself, so the ring can move. The trick: subtract an *offset* version of the ring from the body, before re-uniting the actual ring at assembly time:

```clojure
(register WallMount
  (mesh-union
    anello-mobile                                ; the ring itself
    (mesh-intersection
      (mesh-difference
        wallmount
        (mesh-union
          ...                                    ; screw holes
          (sdf-offset anello-mobile 0.5)))       ; the ring's slot, with clearance
      ...)))
```

The ring is built once. From it we derive two things: the ring as a solid (added to the assembly), and the ring expanded by 0.5 mm (subtracted from the body to carve the slot). The two are guaranteed to fit together with exactly 0.5 mm of slop, by construction.

Whenever you need a uniform inflation or deflation of an arbitrary form — clearances, shells, dilation/erosion — this is qualitatively easier in SDF than in mesh, not just incrementally. The two clearances above (1.5 mm for the guitar, 0.5 mm for the moving ring) come from physical experiments — printer tolerance, surface roughness, ease of insertion. The DSL doesn't help you choose the number; it helps you apply it without fighting the geometry.

## Modelling things that are also tools

There's a category-shift that's easy to miss until you hit it physically. An SDF used to *show* something — a guitar on a shelf, a vase on a table — only needs to look right. An SDF used as a *negative*, to carve out the space a real object will occupy, has to *match* that real object.

The wall mount uses `(chitarra-sdf)` as a subtractive tool: it's offset by 1.5 mm and used to hollow out the cradle that will hold the actual guitar. A model that looked like a guitar — half-cylinder neck, angled slab headstock — wasn't enough. To hollow a cradle that the real instrument can actually slide into, the model has to reproduce the real geometry: correct headstock thickness and width, correct neck-to-headstock angle, and the lateral space the guitar body needs to pass through during insertion. The latter is sculpted by subtracting two cylinders (`c1` and `c2`) from the neck region via `sdf-blend-difference` — a soft subtraction, because the corridors need to ease into the surrounding form, not cut into it sharply.

None of this changes how the guitar *looks* in renders. All of it changes whether the part *works*.

The principle generalises. Whenever you use an SDF as a subtractive tool — to make a holder, a socket, a mating cavity — its fidelity becomes a functional requirement, not a cosmetic one. A 1.5 mm clearance offset will save you from manufacturing tolerance, but not from a model that's geometrically wrong by 5 mm in the wrong place. The clearance papers over noise; it does not paper over inaccuracy.

A practical consequence: before committing a part to print, it's worth asking, for every SDF that's being used subtractively, *is this faithful enough to the real object, or only recognisable as it?* The two are very different standards, and the first one is the one that matters when atoms get involved.

## Idiom: SDF-then-mesh hybrid for mechanical detail

Even with `attach` working on SDFs, there are still good reasons to leave SDF land at some point. Three, in fact:

1. **Mesh booleans against pre-built mesh parts.** If a feature is naturally modelled with the turtle (a screw hole with a countersink, a dovetail, a snap fit), it lives as a mesh. To difference it from the body, the body has to be a mesh too — at the moment of the boolean, at least.

2. **Planar cuts.** As discussed above, the blend always inflates the surface a bit. Any face that has to be rigorously flat — a wall-contact face, a print bed face, a mating surface — has to be enforced after meshing, with a `mesh-intersection` against a bounding box.

3. **Operations with no SDF analogue.** `inset`, face selection, mesh smoothing, hull. These are mesh-side concepts.

The wall mount illustrates all three:

```clojure
(def hole
  (mesh-union
    (cyl 2 5 64)
    (attach (cone 8 2 5 64) (f 2.5))
    (attach (cyl 10 100 64) (f 52.5))))         ; mesh-side detail

(register WallMount
  (mesh-union
    anello-mobile
    (mesh-intersection
      (mesh-difference
        wallmount                                ; SDF result, materialized
        (mesh-union
          (concat-meshes
            (attach hole (f -40) (rt 50)  (tv 90) (f -83))
            (attach hole (f -40) (rt -50) (tv 90) (f -83))
            (attach hole (f 30)            (tv 90) (f -83)))
          (sdf-offset anello-mobile 0.5)))
      (attach (box 180 110 180) (u -30)))))     ; planar cut
```

The `hole` is a counter-sunk screw hole built with ordinary turtle moves, three primitives unioned together. It would be perverse to model that as an SDF — the geometry is exact and rigid, the depths and diameters are dimensions, not feelings. So it is a mesh, and three placed copies are `mesh-difference`d from the body.

Practical rule: **use SDF for the form, mesh for the boundary**. The form is "where does the surface go, how does it flow" — SDF excels. The boundary is "where exactly does the part end, what mates with what, what holds the screws" — mesh excels. The hand-off is one-way and should be made deliberately, not by default, but it's still essential.

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

- **Using `tweak` at production resolution.** The slider's value comes from being faster than editing literals. At resolution 200+, that advantage shrinks to nothing. Tweak at exploration resolution; render at production resolution.

- **Trying to flatten a blended face from inside SDF.** It cannot be done. Convert to mesh, intersect with a planar bounding box, move on.

- **Letting `(tv 90)` (or any turtle command before `sdf-half-space`) leak into surrounding code.** Wrap half-space constructions in `(turtle ...)` to keep the turtle state clean. This is small, but the bugs caused by accidental turtle-state leakage are annoying to track down.

## See also

- Spec, *SDF Modeling* — full reference for the SDF primitives and operators (`sdf-cyl`, `sdf-box`, `sdf-rounded-box`, `sdf-sphere`, `sdf-torus`, `sdf-blend`, `sdf-blend-difference`, `sdf-offset`, `sdf-half-space`, `sdf-clip`, `sdf-intersection`, `sdf-difference`, `sdf-union`, `sdf-displace`, `sdf-formula`).
- Spec, *Top-level transforms* — `translate`, `rotate`, `scale` as polymorphic operations across mesh, SDF, and 2D shape.
- Spec, *attach* — the unified attach operator and its handling of SDFs.
- `defining-2d-profiles.md` — the other side of modelling, where shapes are *contours* rather than *fields*.
- `03_guitar_wall_mount.clj` — full pilot example using the patterns above.