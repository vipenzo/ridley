<!--
=========================================================================
NOTE INTERNE PER L'AUTORE — NON DESTINATE AL LETTORE FINALE
Promemoria delle convenzioni accertate per orientamento delle primitive.

Verità accertate (vedi diagnosi di Code, T-006 chat 2026-05-16):

- box (w h d): w=destra, h=alto, d=avanti (chiamata diretta apply-transform,
  no swap)
- cyl (r h): r=raggio sezione, h=altezza in avanti (wrapper
  transform-mesh-to-turtle-upright applica swap heading↔up)
- cone (r1 r2 h): centrato sulla turtle (come box/sphere), facce a +/-h/2
  lungo l'avanti. r1=raggio della faccia vicino/start (dietro, -heading),
  r2=raggio della faccia lontana (avanti, +heading); h=lunghezza totale
  (stesso wrapper di cyl). Ordine come loft: (cone r1 r2 h) ≈ (loft (circle r1) (circle r2) (f h))
- sphere (r): radialmente simmetrica, orientamento poli invisibile

Tutte le primitive con asse di sviluppo si estendono lungo l'avanti.
Sezione (se presente) nel piano destra-alto. Regolarità con extrude/loft:
  (box w h d) ≈ (extrude (rect w h) (f d))
  (cyl r h)   ≈ (extrude (circle r) (f h))
  (cone r1 r2 h) ≈ (loft (circle r1) (circle r2) (f h))  ; loft, NON extrude; cone centrato vs loft all'inizio

Discrepanze sorgente↔realtà tracciate in dev-docs/code-issues.md.
=========================================================================
-->

# Modeling with primitives

The most intuitive way to model a new object in any CAD is to assemble it from more elementary objects. These, usually called "primitives", are shapes like cubes, boxes, cylinders, spheres, cones, and the like.

Ridley supports this way of working too, among many others. And it is often the most intuitive, as well as the simplest to use. The tools are the primitives themselves: `box` for cubes and boxes, `cyl` for cylinders, `cone` for cones and truncated cones, `sphere` for spheres, plus a few functions that let you move them (`attach`), deform them (`scale`), and compose them: `mesh-union` to join them into a single object, `mesh-difference` to carve the shape of one object out of another, `mesh-intersection` to keep the part common to two objects, `mesh-hull` to build their convex hull.

This is a minimal but powerful grammar: from these few operations you can build a surprising variety of useful objects. We will see it concretely through the chapter, building small objects along the way (a pen holder, a little sailboat) that help us fix the ideas.

## The primitives

Primitives are the elementary building blocks of this way of working. Each one produces a 3D mesh at the turtle's current position, but does not move the turtle: after you create a shape, the turtle stays exactly where it was.

All primitives are born oriented to the turtle, and those that have an "axis of development" (a long box, a cylinder, a cone) extend it *forward* from the turtle. The cross-section, where there is one, lies in the right-up plane. This regularity will be useful again in chapter 4, where we will see that `extrude` follows the same logic.

### The cube and the box

`box` creates a cube, or a box, of the requested size.

<!-- example-source: primitive-cube -->
```clojure
(register cube-demo (box 20))
```

A single argument produces a cube: the same side on all three dimensions.

<!-- example-source: primitive-box -->
```clojure
(register box-demo (box 10 20 30))
```

Three arguments produce a box: the first dimension is to the right, the second up, the third forward. So `(box 10 20 30)` is 10 wide, 20 tall, 30 deep in the direction the turtle is facing. Turning the turtle before the command makes the same code produce a box oriented differently.

The order "right, up, forward" is unusual compared to more traditional CADs. A useful mnemonic is the correspondence with extrusion: `(box 10 20 30)` produces the same solid as `(extrude (rect 10 20) (f 30))`, apart from where they are anchored to the turtle (`box` takes it at the center, `extrude` at the base). The first two numbers are the cross-section, the third the extrusion depth.

### The cylinder

`cyl` creates a cylinder: radius of the base, height forward.

<!-- example-source: primitive-cyl -->
```clojure
(register cyl-demo (cyl 5 30))
```

`(cyl 5 30)` is a cylinder of radius 5, 30 long in the direction the turtle is facing.

### The cone

`cone` creates a truncated cone: radius of the near/start end, radius of the far end, length along the heading. Like `box` and `sphere`, the cone is born centered on the turtle: its two circular faces sit along the heading, half the length to either side, and the turtle is equidistant from both. The two radii follow the same reading order as `loft`: `(cone r1 r2 h)` ≈ `(loft (circle r1) (circle r2) (f h))`.

<!-- example-source: primitive-cone -->
```clojure
(register frustum-demo (cone 10 5 30))
```

`(cone 10 5 30)` is a truncated cone 30 long: `r1 = 10` is the radius of the near/start end (behind), `r2 = 5` the radius of the far end (forward, along the heading) — exactly the shape of `(loft (circle 10) (circle 5) (f 30))`. The turtle sits at the center, 15 from each face. For a true cone, set the far radius to zero: `(cone 10 0 30)` (the point falls on the forward face).

### The sphere

`sphere` creates a sphere of the requested radius, centered on the turtle.

<!-- example-source: primitive-sphere -->
```clojure
(register sphere-demo (sphere 10))
```

`(sphere 10)` is a sphere of radius 10. For a sphere no directional argument makes sense: the shape is symmetric.

### Moving and resizing

Primitives are born at the turtle, so all in the same place. To build something with more than one piece you need two operations: moving a mesh, and, when proportions are not enough, resizing it.

`attach` moves a mesh by following a series of turtle commands.

<!-- example-source: primitive-attach -->
```clojure
(register shifted-sphere (attach (sphere 5) (f 20)))
```

`scale` resizes a mesh. With a single number it scales it uniformly; with three numbers it scales it non-uniformly on the three axes.

<!-- example-source: primitive-scale-uniform -->
```clojure
(register big-sphere (scale (sphere 10) 2))
```

<!-- example-source: primitive-scale-non-uniform -->
```clojure
(register ellipsoid (scale (sphere 10) 1 0.5 2))
```

Non-uniform scaling is especially useful in combination with symmetric primitives: a squashed sphere, a stretched cylinder, a cube that becomes a box. We will use it right away in the two mini-projects.

## Composing

Primitives are elementary objects. For more elaborate models we combine them with four operations: `mesh-union` joins them, `mesh-difference` carves one out of another, `mesh-intersection` keeps the common part, `mesh-hull` builds their convex hull.

### `mesh-union`

`mesh-union` merges two or more meshes into a single mesh.

<!-- example-source: union-cross -->
```clojure
(register cross
  (mesh-union
    (box 40 10 10)
    (box 10 10 40)))
```

`(mesh-union (box 40 10 10) (box 10 10 40))` produces a cross: the two boxes interpenetrate at the center, and where they overlap they become a single volume. The result is one mesh, not two overlapping meshes: there is no internal geometry where the two meet.

Often, though, the pieces are not born already in the right position. By combining `mesh-union` with `attach`, which we saw a moment ago, we can merge one primitive with another that has been moved.

<!-- example-source: union-t -->
```clojure
(register t-shape
  (mesh-union
    (box 40 10 10)
    (attach (box 10 10 30) (f 15))))
```

`(mesh-union (box 40 10 10) (attach (box 10 10 30) (f 15)))` produces a T: the transverse base stays on the turtle, the longitudinal bar is moved forward by 15 (half the length of the base, so it emerges from the center), and the union merges the two.

### `mesh-difference`

`mesh-difference` carves one mesh out of another: it takes the volume of the first and subtracts the volume of the second.

<!-- example-source: difference-drilled-cube -->
```clojure
(register drilled-cube
  (mesh-difference
    (box 30 30 30)
    (cyl 8 30)))
```

`(mesh-difference (box 30 30 30) (cyl 8 30))` produces a cube pierced by a through cylindrical hole: the cube is the "material" we start from, the cylinder is the "shape to remove".

Unlike `mesh-union`, the order of the operands matters: swapping them gives a completely different result, no longer a drilled cube but a cylinder with a cube subtracted, so almost all of the cylinder is swept away by the intersection with the much larger cube.

### `mesh-intersection`

`mesh-intersection` keeps only the part common to two meshes: the volume where both are present.

<!-- example-source: intersection-half-cyl -->
```clojure
(register half-cyl
  (mesh-intersection
    (cyl 10 20)
    (attach (box 20) (u 10))))
```

`(mesh-intersection (cyl 10 20) (attach (box 20) (u 10)))` produces a half cylinder: the cylinder is the starting shape, the cube moved up by 10 occupies only the upper half of the cylinder's space, and the intersection keeps exactly that half.

At first sight it may look like a long way to halve a cylinder. That is the point: `mesh-intersection` is not (only) "finding the region two shapes share", it is a way to *cut* a volume with a plane, defining the plane as "a very large box moved up to there". The same pattern works with any primitive: we will use it right away to build a boat, where we will need a half sphere (hull) and a triangle obtained from a cone (sail).

### `mesh-hull`

`mesh-hull` encloses two or more meshes in the smallest convex volume that contains them all: the convex hull of their union.

Unlike the three previous operations, `mesh-hull` does not work "point by point" on the volumes (keep, remove, intersect), but builds a smooth surface around all the input meshes that wraps them. It is useful as a generative tool: starting from simple shapes you produce shapes that would be awkward to describe otherwise.

<!-- example-source: hull-base-plate -->
```clojure
(register base-plate
  (mesh-hull
    (attach (sphere 4) (rt  30) (f  30))
    (attach (sphere 4) (rt -30) (f  30))
    (attach (sphere 4) (rt  30) (f -30))
    (attach (sphere 4) (rt -30) (f -30))))
```

Four spheres at the corners of a horizontal rectangle, and `mesh-hull` produces a base plate with soft edges: the four corners are spherical, the edges cylindrical, the sides flat.

## A pen holder

The simplest and most useful pattern in primitive modeling is also the oldest in sculpture: start from a solid block and remove material. In Ridley you do it with `mesh-difference`: a solid minus a slightly smaller solid, moved upward, leaves a shell open at the top. A pen holder is exactly this.

<!-- example-source: pen-holder-v1 -->
```clojure
(register pen-holder
  (mesh-difference
    (box 40 40 80)
    (attach (box 36 40 76) (u 2))))
```

`(box 40 40 80)` is the outer block: 40 wide, 40 deep, 80 tall. `(box 36 40 76)` is the cavity: 4 mm narrower in width and 4 mm shorter in depth, same height. `(attach ... (u 2))` raises it by 2: this way the bottom stays solid, 2 thick, and the top is open.

This pattern, a solid volume minus a raised cavity, does not depend on the shape: it depends only on the operation. We will meet it again shortly in the boat.

### Cylindrical variant

Changing the primitive changes the look of the pen holder, but the logic is identical.

<!-- example-source: round-pen-holder -->
```clojure
(register round-pen-holder
  (mesh-difference
    (attach (cyl 20 80) (tv 90))
    (attach (cyl 18 80) (tv 90) (f 2))))
```

Outer cylinder of radius 20, 80 tall, rotated upright with `(tv 90)`. Inner cylinder of radius 18 (wall thickness: 2), rotated the same way and raised by 2 with `(f 2)` to leave the bottom. Remember that `cyl` extrudes forward: after `(tv 90)` forward becomes up, so `(f 2)` raises the inner cylinder.

If you plan to print the pen holder, the only number that really matters is the wall thickness: 2 mm holds for most filaments, 3 mm if you want more sturdiness. The bottom thickness is the value passed to `(f ...)` after the `(tv 90)`.

<!-- example-source: sturdy-pen-holder -->
```clojure
(def wall 3)

(register sturdy-pen-holder
  (mesh-difference
    (attach (cyl 22 100) (tv 90))
    (attach (cyl (- 22 wall) 100) (tv 90) (f wall))))
```

Here `def` gives the thickness a name, so you change it in one place and the bottom and walls update together. We will see this better in the section on `def`, where it becomes the central tool for parameterizing a model.

### A boat

The four operations working together are already enough to build a small recognizable object: a little sailboat.

The code is longer than the ones we have seen so far, but each piece uses one or two tools we have already met. We read it from the top.

```clojure
(def hull-vol (scale
               (mesh-intersection
                 (sphere 50)
                 (attach (box 100) (u -50)))
               1 0.5 0.5))
```

`def` gives a piece a name: here `hull-vol` is the *volume* of the hull, a squashed half sphere. The intersection between a sphere of radius 50 and a very large box moved 50 down keeps only the lower half of the sphere. `scale` with three numbers deforms it non-uniformly: it leaves the width intact (`1`), halves the height (`0.5`) and the length (`0.5`), so it becomes an elongated ellipsoid, the outline of a hull. We will use `def` much more in the next section: here we only need it to avoid writing the same expression twice.

```clojure
(def hull
  (mesh-difference
    hull-vol
    (attach hull-vol (u 2))))
```

The actual hull, hollowed out. `mesh-difference` subtracts from `hull-vol` a copy of itself raised by 2: it leaves a shell 2 thick on the bottom and sides, open at the top. It is the same pattern as the pen holder, applied to a more complex shape.

```clojure
(def mast (attach (cyl 3 85) (tv 90) (f 18)))
```

A thin tall cylinder, rotated 90° with `tv` to make it vertical relative to the hull (the turtle started facing forward, after `tv 90` it faces up), and moved forward by 18 so it sits toward the bow.

```clojure
(def sail (attach (scale (mesh-intersection
                           (cone 30 0 30)
                           (attach (box 100) (u 50)))
                         1.5 0.1 2)
            (f 26) (u 1)))
```

The sail. The intersection between a cone (radius 0 in front, 30 behind, 30 long) and a box moved up by 50 keeps only the upper half of the cone: a triangle. `scale 1.5 0.1 2` stretches it in width (`1.5`), flattens it almost to a sheet (`0.1`), and lengthens it in height (`2`). Then `attach` places it 26 forward and slightly raised by 1.

```clojure
(register boat
  (mesh-union
    hull
    mast
    sail
    (attach (scale sail -1 0.7 0.9) (f -52))))
```

Finally, the union of everything. The last line is the second sail: the first `sail` rescaled with `scale -1 0.7 0.9` (the `-1` mirrors it on the `right` axis, the `0.7` and `0.9` make it slightly different to give a bit of asymmetry) and pushed back by 52 with `(f -52)` to place it toward the stern. `f` accepts negative values, and means "backward".

The result is a little boat with a hollowed hemispherical hull, a mast, a mainsail, and a jib. Several operations from 2.2 working together, plus the few `attach` and `scale` that hold the pieces together.

All together, here is the complete code:

<!-- example-source: boat -->
```clojure
(def hull-vol (scale
               (mesh-intersection
                 (sphere 50)
                 (attach (box 100) (u -50)))
               1 0.5 0.5))

(def hull
  (mesh-difference
    hull-vol
    (attach hull-vol (u 2))))

(def mast (attach (cyl 3 85) (tv 90) (f 18)))

(def sail (attach (scale (mesh-intersection
                           (cone 30 0 30)
                           (attach (box 100) (u 50)))
                         1.5 0.1 2)
            (f 26) (u 1)))

(register boat
  (mesh-union
    hull
    mast
    sail
    (attach (scale sail -1 0.7 0.9) (f -52))))
```

## Parameterizing with def

In the previous examples the dimensions are numbers written directly in the code: 40, 80, 2. It works, but as soon as a model grows it becomes fragile: if you decide the pen holder should be wider, you have to find and update every number that depends on that decision. Forgetting one produces a silently wrong model: no error, just a thicker wall on one side or a misaligned cavity.

`def` solves the problem by giving a value a name. We already used it for the thickness of the sturdy pen holder; here we extend it to all the dimensions.

<!-- example-source: parametric-pen-holder -->
```clojure
(def radius 20)
(def height 100)
(def wall 3)

(register pen-holder
  (mesh-difference
    (attach (cyl radius height) (tv 90))
    (attach (cyl (- radius wall) height) (tv 90) (f wall))))
```

Three `def` at the top, a model below that uses them. Changing the radius from 20 to 25 automatically updates both the shell and the cavity; changing the thickness updates walls and bottom together. The numbers have a name, and that name says *what* they represent, not just *how much* they are worth.

Any Clojure expression can go on the right of a `def`, not just numbers. This is useful when one dimension depends on another.

<!-- example-source: two-compartment-pen-holder -->
```clojure
(def radius 15)
(def height 90)
(def wall 2)
(def spacing (* radius 2))

(def compartment
  (mesh-difference
    (attach (cyl radius height) (tv 90))
    (attach (cyl (- radius wall) height) (tv 90) (f wall))))

(register pen-holder
  (mesh-union
    compartment
    (attach compartment (rt spacing))))
```

`spacing` is not a fixed number: it is `(* radius 2)`, that is twice the radius. This way the two compartments touch, and `mesh-union` merges the shared wall into a single volume. If you enlarge the compartments, the distance grows accordingly.

`compartment` itself is a `def`: a whole piece we give a name so we can reuse it twice, one in the original position and one moved to the right with `(rt spacing)`.

This is the point where `def` stops being just a way to avoid repetition and becomes a design tool: the relationships between the parts are explicit in the code, and changing a design decision comes down to changing a number at the top of the file.

## Reusable pieces with defn

`def` gives a name to a fixed value. `defn` gives a name to a *recipe* that produces different values each time, depending on the parameters you pass it.

In the two-compartment pen holder of the previous section, both compartments had the same dimensions. With `defn` we can write a "compartment" recipe that takes a size and a height, and use it several times with different parameters.

<!-- example-source: defn-compartment -->
```clojure
(def wall 2)

(defn round-compartment [radius height]
  (mesh-difference
    (attach (cyl radius height) (tv 90) (f (/ height 2)))
    (attach (cyl (- radius wall) height) (tv 90) (f (+ (/ height 2) wall)))))

(defn square-compartment [half-side height]
  (mesh-difference
    (attach (box (* half-side 2) (* half-side 2) height) (tv 90) (f (/ height 2)))
    (attach (box (* (- half-side wall) 2) (* (- half-side wall) 2) height)
      (tv 90)
      (f (+ (/ height 2) wall)))))

(defn compartment [kind size height]
  (if (= kind :round)
    (round-compartment size height)
    (square-compartment size height)))

(register pen-holder
  (mesh-union
    (compartment :round 18 100)
    (attach (compartment :round 10 80) (rt (+ 10 18)))
    (attach (compartment :square 12 70) (u (+ 18 12)))
    (attach (compartment :square 8 60) (u (- (+ 18 8))))))
```

`round-compartment` and `square-compartment` are two functions that do exactly what we did by hand in the previous sections: a shell minus a raised cavity. The only new thing is that the radius (or half side) and the height are no longer fixed numbers but parameters, different on each call. The `(tv 90)` and the `(f (/ height 2))` serve to stand the compartment up and align the bases, since they have different heights.

`compartment` is a third function that chooses which of the two to call based on `kind`: `:round` or `:square`. One function can call another, and here the advantage is practical: whoever uses `compartment` does not have to remember which of the two specific functions is needed, they pass the type and the choice happens inside.

Four calls with different parameters produce four compartments: a large round one for brushes, a small round one for pens moved to the right, and two square ones moved up and down. The `attach` distances add the radii of the two adjacent compartments, so they touch without interpenetrating. `mesh-union` merges everything into a single piece.

If you decide all the compartments should have a thicker bottom, you change `wall` and all four calls update.

## Many primitives at once

So far we have built each object with a few pieces placed by hand. But the language underneath Ridley is a programming language, and a programming language can do loops. We can generate dozens of primitives with a single expression.

Let us take the cylindrical pen holder from the previous section and add a decoration: a spiral of little spheres wrapping around the outer wall.

<!-- example-source: spiral-vase :warning slow 
```clojure
(def radius 40)
(def h 80)
(def n-spheres 36)

(def vase-outer
  (attach (cyl radius h) (tv 90)))

(def vase-cut
  (attach (cyl (- radius 4) h) (tv 90) (f 2)))

(def decoration
  (mesh-union
    (for [i (range n-spheres)
          :let [angle (* i 20)
                z (- (/ (- h 10) 2)
                     (* i (/ (- h 10) n-spheres)))]]
      (attach (sphere 5) (th angle) (u z) (f radius)))))

(register vase
  (mesh-difference
    (mesh-union vase-outer decoration)
    vase-cut))
```
-->

The pen holder scheme is the same: an outer cylinder minus an inner one raised by 2. The new thing is `decoration`.

`(for [i (range n-spheres)] ...)` is a loop: `i` takes the values from 0 to 35 (36 values), and for each one produces a sphere placed with `attach`. `:let` introduces two local names that exist only inside the loop: `angle` is the horizontal rotation (it grows by 20° for each sphere, so over 36 spheres it makes almost two full turns), `z` is the height (it descends linearly from top to bottom). The result is a spiral.

`mesh-union` receives the list of 36 spheres produced by the `for` and merges them into a single mesh. Then the decoration is joined to the outer cylinder, and from their union the inner cavity is subtracted. The spheres that protrude inward are cut away by the difference, leaving the half spheres in relief on the outer wall.

`for` and `range` are Clojure, not Ridley. But this is the advantage of having a programming language underneath: any geometric pattern you can describe with a formula becomes a loop that generates primitives. By changing `n-spheres`, the radius of the spheres, or the angle formula, the same structure produces denser spirals, sparser ones, double helices, or grids. It is worth experimenting.

## For those coming from a traditional CAD

If you have used OpenSCAD, Tinkercad, or any modeler that works with `translate`, `rotate`, `scale`, you will find all three in Ridley. They work in world coordinates: `translate` moves along the world X/Y/Z axes, `scale` scales along those same axes with the pivot at the mesh's creation-pose, `rotate` rotates around the creation-pose along a world axis.

<!-- example-source: cad-translate-rotate -->
```clojure
(register shelf-bracket
  (mesh-difference
    (translate (box 40 5 30) 15 20 0)
    (translate (cyl 3 25) 10 20 1)))
```

It works, and it is the right approach when you think in terms of "this thing goes at X=20, Y=0, Z=15". But in Ridley there is a second vocabulary that is often more natural: the turtle commands inside `attach`.

<!-- example-source: cad-vs-turtle -->
```clojure
; same bracket, local vocabulary
(register shelf-bracket
  (mesh-difference
    (attach (box 40 5 30) (f 15) (rt 20))
    (attach (cyl 3 25) (f 10) (rt 20) (u 1))))
```

`(f 15)` means "15 forward", `(rt 20)` means "20 to the right". If the turtle is in the default pose, right is X and up is Z, so the result is identical. The difference emerges when you compose rotated pieces: the turtle commands follow the rotation, world coordinates do not.

Inside `attach` you have the same complete set you have at the top level, but in local coordinates:

- **Movement**: `f`, `rt`, `u` (and their negatives `b`, `lt`, `d`)
- **Rotation**: `th`, `tv`, `tr`
- **Scaling**: `stretch-f`, `stretch-rt`, `stretch-u`

`stretch-f` scales the mesh along the direction the turtle is facing at that moment. If you did `(th 45)` first, the scaling axis is rotated by 45°.

<!-- example-source: stretch-local -->
```clojure
(register stretched-box
  (attach (box 20) (th 45) (stretch-f 2)))
```

At the top level, `translate`, `scale`, `rotate` always work in world coordinates. Inside `attach`, everything works in local coordinates. One rule, no exceptions.

The pivot of `scale` and `rotate` is the mesh's creation-pose, that is the turtle's position at the moment the primitive was created. For freshly built primitives it is at the center of the geometry, so you do not think about it. When you work with composed pieces or booleans the pivot may not coincide with the visual center: chapter 8 (Assembly) explains how to control it with `cp-*`.
