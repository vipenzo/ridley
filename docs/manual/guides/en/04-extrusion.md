<!--
=========================================================================
NOTE INTERNE PER L'AUTORE — NON DESTINATE AL LETTORE FINALE

Struttura del capitolo:
4.1  Il concetto (extrude base)
4.2  Percorsi curvi e composti (th, arc-h, arc-v, bezier-to)
4.3  Quick path ed estrusione chiusa (qp, extrude-closed)
4.4  Transizioni di forma e modalità giunzione (joint-mode)
4.5  Risoluzione e dettaglio (resolution)
4.6  Shell, woven shell e embroid (loft + shell/embroid shape-fn)
4.7  Raccordi sui cap (capped)
4.8  Revolve (asse = up della tartaruga, verificato 2026-05-20)
4.9  Chaining: extrude+, revolve+, transform->

Decisioni:
- revolve aggiunto al cap. 4 (non era nel piano originale)
- chaining aggiunto al cap. 4
- asse revolve = up (Spec corretta il 2026-05-20, era sbagliata)
- loft+ non esiste ancora, segnato in Roadmap §1.5
- embroid aggiunto al 4.6 come complemento di shell (2026-06-06); possibile futura migrazione di alcuni pattern di embroid (es. :pattern) a shell, da valutare
=========================================================================
-->

# Extrusion

## The concept

In chapter 3 we built shapes: 2D profiles that live in the turtle's plane. `extrude` turns them into solids by dragging them along a path.

<!-- example-source: extrude-tube -->
```clojure
(register tube (extrude (circle 15) (f 30)))
```

The first argument is the shape (a circle of radius 15). Everything that follows are turtle commands that define the path. `(f 30)` means "forward by 30": the circle is dragged forward, and the volume it sweeps through becomes a cylinder.

The result is identical to `(cyl 15 30)`. The difference is that with `extrude` the section can be any shape, not just a circle:

<!-- example-source: extrude-l-bar -->
```clojure
(register l-bar
  (extrude
    (shape (f 10) (th 90)
           (f 5)  (th 90)
           (f 5)  (th -90)
           (f 5)  (th 90)
           (f 5))
    (f 40)))
```

An L-shaped bar 40 long. The shape is the L profile from chapter 3, the path is a straight line.

`extrude` does not change the turtle's state: after the call the turtle is exactly where it was before. The mesh is born at the turtle's current position, with the section in the right-up plane and the path starting forward. It is the same convention as the primitives: `(box 10 20 30)` produces the same solid as `(extrude (rect 10 20) (f 30))`, apart from the anchor point (the box is centered, the extrusion starts from the base).

The path can also be a previously recorded path:

<!-- example-source: extrude-recorded-path -->
```clojure
(def my-path (path (f 20) (th 45) (f 20)))
(register bent-tube (extrude (circle 10) my-path))
```

`path` records a sequence of turtle commands as reusable data. We will see it in detail in chapter 5. For now the point is that a path can be defined once and used in several extrusions.

## Curved and compound paths

The path of an extrusion does not have to be straight. Any turtle command works inside `extrude`: rotations to change direction, arcs for smooth curves, beziers for free trajectories.

### Changes of direction

<!-- example-source: extrude-bent -->
```clojure
(register bent-tube
  (extrude (circle 10)
    (f 20)
    (th 45)
    (f 20)))
```

`(th 45)` turns the turtle by 45° halfway along the path. The tube starts straight, bends, and continues straight in the new direction. The bend is beveled (the default joint mode is `:tapered`). We will see in 4.4 how to change it.

You can chain several commands for articulated paths:

<!-- example-source: extrude-zigzag -->
```clojure
(register zigzag
  (extrude (rect 8 4)
    (f 15) (th 60)
    (f 15) (th -120)
    (f 15) (th 60)
    (f 15)))
```

A rectangular profile extruded in a zigzag. Each `th` changes direction, each `f` adds a straight stretch.

### Arcs

`arc-h` and `arc-v` produce smooth curves: horizontal or vertical circular arcs.

<!-- example-source: extrude-arc -->
```clojure
(register u-tube
  (extrude (circle 5)
    (f 20)
    (arc-h 15 180)
    (f 20)))
```

A U-shaped tube: straight stretch, horizontal semicircle of radius 15, straight stretch. `arc-h` internally generates a sequence of small segments and rotations, so the curve is smooth (the default resolution is 64 segments per arc; you can lower it with `:steps` or with `resolution` if you need less detail).

`arc-v` works the same way but curves in the vertical plane (around the turtle's right axis). Combining the two, you can build tubes that rise, fall, and turn in 3D space.

<!-- example-source: extrude-s-curve -->
```clojure
(register s-pipe
  (extrude (circle 6)
    (arc-h 20 90)
    (arc-h 20 -90)))
```

An S-curve: two consecutive arcs with opposite angle.

### Bezier

For trajectories that are not circular arcs, there are Bezier curves. `bezier-to` moves the turtle toward a target point with a smooth curve:

<!-- example-source: extrude-bezier -->
```clojure
(register curved-bar
  (extrude (rect 6 3)
    (bezier-to [30 0 20])))
```

A rectangular bar that rises gently from where the turtle is toward the point `[30 0 20]` (30 forward and 20 up relative to the origin, if the turtle is in the default pose). The control points are generated automatically, tangent to the turtle's current direction.

For more controlled curves, `bezier-to` accepts one or two explicit control points. And `bezier-as` takes an existing path and traverses it as a smooth Bezier curve, useful for smoothing a segmented path. The details are in chapter 5 (Paths) and in the Reference.

### Combining everything

Straight segments, arcs, and beziers mix freely in the same path. The extrusion chains them seamlessly:

<!-- example-source: extrude-mixed -->
```clojure
(register handle
  (extrude (circle 4)
    (f 30)
    (arc-h 10 90)
    (f 20)
    (arc-h 10 90)
    (f 30)))
```

A handle: straight stretch, 90° curve, straight stretch, another 90° curve, straight stretch. The circular section stays constant along the whole path.

## Quick path and closed extrusion

### Quick path

For straight paths with changes of direction, writing `(f 20) (th 45) (f 30) (th -60) (f 10)` becomes verbose. `quick-path` (alias `qp`) compacts the notation: numbers and angles alternate without command names.

<!-- example-source: extrude-quick-path -->
```clojure
(register bracket
  (extrude (rect 8 4)
    (qp 20 90 30 -45 10)))
```

`(qp 20 90 30 -45 10)` is equivalent to `(f 20) (th 90) (f 30) (th -45) (f 10)`: the odd-position values are distances, the even ones are angles. Handy for quick prototypes and simple paths; for complex paths with arcs and beziers, the extended form stays more readable.

### Closed extrusion

`extrude` produces a solid with two caps (the start face and the end face). `extrude-closed` instead connects the last ring to the first, producing a solid with no caps, like a torus.

<!-- example-source: extrude-closed-torus -->
```clojure
(def square-loop (path (dotimes [_ 4] (f 20) (th 90))))

(register torus (extrude-closed (circle 5) square-loop))
```

The path is a square (four sides of 20, four 90° turns) that returns to the starting point. `extrude-closed` extrudes the circle along that square and closes the mesh by connecting the end with the start. The result is a torus with a circular section on a square trajectory.

For `extrude-closed` to work, the path must return reasonably close to the starting point. Perfect precision is not needed (small numerical errors are tolerated), but a path that ends far from where it started produces a mesh with a visible jump at the join.

<!-- example-source: extrude-closed-round -->
```clojure
(register ring
  (extrude-closed (rect 3 6)
    (path (arc-h 25 360))))
```

A ring with a rectangular section: the path is a full circle (a 360° arc), the section is a rectangle. With `extrude` we would get an open tube; with `extrude-closed` the two ends weld together.

## Shape transitions and joint mode

When the path changes direction, the extrusion has to decide how to treat the corner. The joint mode controls the geometry at those points.

<!-- example-source: joint-tapered -->
```clojure
(register tapered-bend
  (extrude (rect 12 6)
    (f 20) (th 90) (f 20)))
```

With the default mode (`:tapered`), the joint is beveled: an oblique cut that links the two stretches. For most situations it is the right compromise: visually clean, geometry kept low.

`joint-mode` changes the mode for all subsequent extrusions:

<!-- example-source: joint-round -->
```clojure
(joint-mode :round)
(register round-bend
  (extrude (circle 8)
    (f 20) (th 90) (f 20)))
```

With `:round` the joint is filled with intermediate segments that follow the curve. The corner becomes soft, as if the tube bent instead of breaking.

<!-- example-source: joint-flat -->
```clojure
(joint-mode :flat)
(register flat-bend
  (extrude (circle 8)
    (f 20) (th 90) (f 20)))
```

With `:flat` the bend is a sharp edge: the two sections meet at an angle and the joint is a clean cut. Useful when the edge is intentional (boxes, frames, technical objects).

The three modes:

`joint-mode :tapered` (default) produces a linear joint (a bevel). A good compromise between visual quality and amount of geometry.

`joint-mode :round` inserts intermediate rings that curve the section around the corner. The resolution of the curve depends on the `resolution` setting. It produces the softest joints, but with more geometry.

`joint-mode :flat` produces sharp edges. The geometry is minimal. The joint cuts the profile along the angle's bisecting plane.

`joint-mode` changes the turtle's state: once set, it applies to all subsequent extrusions until the next change. If you want a different mode for a single extrusion, use a `turtle` scope:

```clojure
(turtle
  (joint-mode :round)
  (register this-one (extrude (circle 8) (f 20) (th 90) (f 20))))
;; outside the turtle scope, joint-mode is back to what it was before
```

The `turtle` macro creates an isolated scope: everything that happens inside (state changes, movements, settings) does not affect the outer turtle. We will see it in detail in chapter 5; here it is enough to know that it is the way to limit a setting like `joint-mode` to a single operation.

## Resolution and detail

The curves in the path (arcs, beziers, `:round` joints) are approximated with straight segments. How many segments determines how smooth the curve looks. The `resolution` setting controls this globally.

<!-- example-source: resolution-low -->
```clojure
(resolution :n 8)
(register rough
  (extrude (circle 15)
    (arc-h 20 180)))
```

With 8 segments per arc the tube is visibly faceted, both in the circular section and in the path's curve.

<!-- example-source: resolution-high -->
```clojure
(resolution :n 64)
(register smooth
  (extrude (circle 15)
    (arc-h 20 180)))
```

With 64 segments the curves are smooth. The price is more geometry: more triangles in the mesh, more computation time for booleans.

`resolution` has three forms:

`(resolution :n 32)` fixes the number of segments. It is the most predictable way: you know how many segments you will get. The default is 64.

`(resolution :a 5)` fixes the maximum angle per segment (in degrees). Tight curves get more segments than wide ones: the resolution adapts to the curvature.

`(resolution :s 0.5)` fixes the maximum length of each segment. Large arcs get more segments than small ones: the resolution adapts to the size.

`resolution` affects everything: arcs, beziers, circles, spheres, cylinders, cones, `:round` joints, and revolution (`revolve`). Like `joint-mode`, it changes the turtle's state and applies to everything that comes after. To limit it to a single operation, use a `turtle` scope.

For one-off cases, many functions accept a direct override without changing the global resolution:

```clojure
(arc-h 10 90 :steps 32)      ; this arc only, at 32 segments
(circle 15 64)                ; this circle only, at 64 segments
```

A practical rule: the default (`:n 64`) produces curves that are already smooth for most uses. For quick prototyping you can lower it to `:n 16` or `:n 32` to reduce geometry and speed up booleans. There is no need to change the global resolution if only some curves need different treatment: one-off overrides like `(circle 15 128)` or `(arc-h 10 90 :steps 32)` are made for that.

## Shell, woven shell and embroid

So far we have extruded solid profiles. But many real objects are hollow: vases, lamps, containers, lampshades. `shell` is a shape-fn that turns a solid profile into a hollow shell with walls of controlled thickness, with the option of opening decorative windows in the walls.

Since `shell` is a shape-fn (the profile changes along the path, going from solid to hollow), it needs `loft` instead of `extrude`. The practical difference is minimal: where before you wrote `(extrude shape ...)`, now you write `(loft (shell shape ...) ...)`.

<!-- example-source: shell-solid -->
```clojure
(register cup
  (loft (shell (circle 20 64) :thickness 2 :style :solid)
    (f 40)))
```

A cup: a circle extruded as a shell with walls 2 thick. `:style :solid` produces solid walls, with no openings. The result is a hollow cylinder open at both ends: the shell has neither bottom nor lid. To close them, `shell` accepts the options `:cap-top` and `:cap-bottom`. A number produces a solid cap of the given thickness; a map produces a patterned cap (Voronoi, grid).

<!-- example-source: shell-cup-with-bottom -->
```clojure
(register cup-with-bottom
  (loft (shell (circle 20 64) :thickness 2 :style :solid :cap-bottom 2)
    (f 40)))
```

With `:cap-bottom 2` the cup has a closed bottom, 2 thick. The top stays open, as you would expect from a cup.

The wall is symmetric: half the thickness sticks outward, half inward relative to the original profile.

### Opening styles

The style controls the pattern of the openings in the walls.

<!-- example-source: shell-voronoi :warning slow -->
```clojure
(register lamp
  (loft-n 512 (shell (circle 20 512) :thickness 2 :style :voronoi
                :cells 8 :rows 6 :softness 0.6)
    (f 50)))

```

`:voronoi` distributes irregular cells over the walls. The cell edges are material, the interior is empty. `:cells` controls how many cells per ring, `:rows` how many rings along the path.

<!-- example-source: shell-lattice :warning slow -->
```clojure
(register vase
  (loft-n 512 (shell (circle 15 512) :invert? true :thickness 2 :style :lattice :openings 4 :rows 6)
    (f 60)))
```

`:lattice` produces a brick pattern: rows of solid blobs staggered along the circumference. `:openings` controls the number of bricks per row, `:rows` the number of rows. With `:invert? true` the pattern is inverted: the bricks become openings in a continuous shell. Without `:invert?`, the result is detached bricks (useful as a relief texture, less so as a shell).

The `:softness` option (for `:voronoi` and `:lattice`, default `0.6`) controls how the edges of the openings are cut. By default the cut is *isocontour*: the edge is sliced at the exact position where the wall meets the opening, between one vertex and the next, and the openings come out smooth (with a small tapered lip) even at moderate resolution. With `:softness 0` the cut goes back to *binary*: each triangle of the grid is kept or discarded as a whole, and the edges stay stepped along the grid of rings and segments (raising the resolution shrinks the teeth but does not eliminate them). A value around `0.5–0.8` is the sweet spot. It is the equivalent, for shells, of the `:edge-softness` of text relief (chapter 13). (`:lattice` with `:invert?` always uses the binary cut.)

The other available styles are `:checkerboard` and `:weave`. Each has its own specific options; the Reference documents them all. `:invert? true` works with all styles, including custom thickness-fns passed with `:fn`.

### Composing with other shape-fns

`shell` is a shape-fn like `tapered` or `twisted`, so it composes with `->`:

<!-- example-source: shell-composed :warning slow -->
```clojure
(register tapered-lamp
  (loft-n 512 (-> (circle 20 512)
                  (shell :thickness 2 :style :voronoi :cells 8 :rows 6)
                  (tapered :to 0.5))
    (f 60)))
```

A lamp that tapers: `shell` makes the profile hollow with Voronoi openings, `tapered` reduces it progressively along the path. The two transformations are applied in sequence to each ring of the loft.

### Woven shell

`woven-shell` is a variant of `shell` that does not just vary the wall thickness: it also shifts the wall center radially, so the strands pass in front of and behind one another as in a real weave.

<!-- example-source: woven-shell-basic :warning slow -->
```clojure
(register basket
  (loft-n 512 (woven-shell (circle 20 512) :thickness 3 :strands 8)
    (f 50)))
```

A woven basket. The diagonal strands cross with a true three-dimensional over/under, not just a pattern of holes. `:strands` controls the number of strands; `:mode :orthogonal` produces a warp-and-weft weave (wicker-like) instead of the default diagonal pattern.

Like `shell`, `woven-shell` composes with other shape-fns:

<!-- example-source: woven-lamp :warning slow -->
```clojure
(register woven-lamp
  (loft-n 512 (-> (circle 20 512)
                (woven-shell :thickness 3 :strands 6)
                (tapered :to 3.5))
    (f 50)))
```

### Embroid: perforating a wall

`shell` starts from a solid profile and hollows it out, leaving thin walls with a pattern of openings. `embroid` covers the complementary case: a wall that is already a thin surface, not a solid to hollow out, and cuts the same kind of windows into it. It is the case where `shell` does not apply, because there is nothing to hollow. Think of it as "making a slice of a shell".

Unlike the other shape-fns, `embroid` does not take a shape but the path that defines the wall's centerline, plus the thickness. It builds the two faces of the wall by offsetting `±thickness/2` perpendicular to the path at each point, so the perforation goes through the thickness whatever the curvature of the path. Each opening is a through hole finished between the two faces, and the result is watertight and manifold. Like `shell`, it is a shape-fn and is used with `loft`.

<!-- example-source: embroid-wall -->
```clojure
(register panel
  (loft (embroid (path (f 3) (arc-h 50 90) (f 70))
          3
          :resolution 400
          :wall {:style :honeycomb :cells 8 :border 4})
    (f 45)))
```

A curved wall perforated with a regular honeycomb and a solid 4-unit border on all sides. The first argument of `embroid` is the centerline path (a straight stretch, a 90° arc, another straight stretch), the second is the thickness. Both the straight and the curved stretch are perforated, because the pattern follows the path rather than a fixed direction.

The `:honeycomb` style is the default. With `:style :pattern` any shape can be used as the opening motif:

<!-- example-source: embroid-holes -->
```clojure
(register grille
  (loft (embroid (path (f 3) (arc-h 50 90) (f 70))
          3
          :wall {:style :pattern :pattern (circle 4)
                 :spacing 12 :grid :hex :inset 0.5})
    (f 45)))
```

Round holes on a hexagonal grid. `:spacing` is the grid pitch, `:grid` chooses between a square or a staggered hexagonal layout, `:inset` shrinks the motif to thicken the bridges between holes. There is also `:style :voronoi`, as for `shell`. The [embroid](ref:embroid) Reference card documents all the pattern, border and cap options.

Two practical points. The sharpness of the edges along the path is controlled by `:resolution` (samples along the path), independently of the number of loft steps, which only refines the sweep direction: usually you do not need a high `loft-n` once `:resolution` is set. And unlike `shell`, `embroid` does not compose with `->` like the other shape-fns: in the loft it stamps its own ready-made faces, so a `tapered` or a `twisted` added afterwards is silently ignored. To reposition it use embroid's `:offset` option, or translate the resulting mesh.

### Resolution and performance

Shell and woven-shell need a lot of resolution on both axes to render the patterns well. Two numbers matter: the number of points in the circle (the circumferential resolution) and the number of loft steps (the longitudinal resolution). With the defaults of 64 points and 64 steps, the simpler patterns are already legible, but for truly crisp results you need values on the order of 512 on both axes: `(circle 20 512)` for the profile and `loft-n 512` for the steps. The price is slow computation (several seconds) and a mesh with many triangles. During prototyping it is better to use lower numbers (128 or 256) to iterate quickly, and raise them for the final result.

The global `resolution` setting also affects the default number of loft steps. If explicit control is needed, `loft-n` with the desired number of steps always takes precedence.

Chapter 6 covers shape-fns in depth: composition, custom shape-fns, thickness-fns. Here the goal was to show that hollow extrusion is within reach without leaving the shape → loft flow.

## Fillets on the caps

An extruded solid has sharp edges where the section meets the end faces (the "caps"). In chapter 3 we saw `fillet-shape` for rounding the 2D corners of the profile (the edges along the extrusion direction). `capped` rounds the other set of edges: those where the profile meets the closing faces, at the two ends.

<!-- example-source: capped-basic -->
```clojure
(register rounded-bar
  (loft (capped (rect 30 15) 3) (f 50)))
```

`capped` takes a shape and a radius, and produces a shape-fn that fillets the ends: the profile starts from zero, grows to full size within the first 3 mm, stays constant along the path, and returns to zero in the last 3 mm. The transition follows a quarter-circle profile, so the fillet is smooth. The result is a box with rounded end edges.

Like `shell`, `capped` is a shape-fn and requires `loft` instead of `extrude`.

### Fillet and chamfer on the caps

`capped` has two modes: fillet (default) and chamfer.

```clojure
(loft (capped shape 3) (f 50))                ; fillet (quarter circle)
(loft (capped shape 3 :mode :chamfer) (f 50)) ; chamfer (linear transition)
```

With `:chamfer` the transition is a straight 45° cut instead of a curve.

### Controlling the ends

You can choose to fillet only one end:

```clojure
(loft (capped shape 3 :start false) (f 50))   ; only the end
(loft (capped shape 3 :end false) (f 50))     ; only the start
```

### Composing with fillet-shape and other shape-fns

`fillet-shape` rounds the 2D corners of the profile. `capped` rounds the 3D end edges. The two operations are orthogonal and compose:

<!-- example-source: capped-fillet-composed -->
```clojure
(register rounded-box
  (loft (-> (rect 40 20) (fillet-shape 5) (capped 3)) (f 50)))
```

A box with rounded 2D corners (radius 5) and filleted end edges (radius 3). The result is an object with all edges soft, obtained by composing two independent operations.

`capped` also composes with `tapered`, `twisted`, and any other shape-fn:

<!-- example-source: capped-tapered-foot -->
```clojure
(def foot
  (loft (-> (circle 15) (tapered :to 0.3) (capped -10)) (f 40)))

(register base
  (mesh-union
    (attach (box 30) (f (+ 40 15)))
    foot)
  )
```

`capped` is a useful tool, when used with negative values (-10 in this case), for blending the result of a loft into other objects. In the example above the foot widens to meet the cube.

The fraction of the path devoted to the transition is computed automatically by `capped` as the ratio between the radius and the path length. It can be forced with `:fraction` if the automatic result is not satisfactory.
It is not possible to have two different values for the two caps (consequently you cannot have one that tapers, with positive values, and one that widens, with negative values). To get that effect you have to use two separate lofts.

## Revolve

`revolve` rotates a 2D profile around the turtle's up axis, producing a solid of revolution. Where `extrude` drags the profile along a path, `revolve` spins it on itself.

<!-- example-source: revolve-bowl -->
```clojure
(def profile (path
               (bezier-as
                 (path
                   (f 20)
                   (th 60)
                   (f 30)
                   (th 120)
                   (f 2)
                   (th 60)
                   (f 20)
                   (th -30)
                   (f 10)
                   (th -20)
                   (f 20)))))

(register bowl
  (revolve
    (path-to-shape
      profile)))
```

A bowl. The profile is an angular path smoothed with `bezier-as` and converted to a shape with `path-to-shape`. `revolve` without a second argument makes a full turn (360°). The X coordinate (right) of the shape is the distance from the axis of revolution, the Y coordinate (up) is the position along the axis. The rotation happens around the turtle's up axis.

With a second argument you control the angle of revolution:

<!-- example-source: revolve-partial -->
```clojure
(register quarter-bowl
  (revolve
    (rect 20 10) 90))
```

A quarter revolution: the rectangle rotates only 90° instead of 360°. Useful for sectors, wedges, and partial geometries.

### Pivot

When you use `revolve` to create a curve (a tube elbow, a frame corner), you need to control which edge of the profile sits on the axis of revolution. `:pivot` shifts the profile so that a specific edge coincides with the axis:

<!-- example-source: revolve-pivot -->
```clojure
(register elbow
  (revolve (circle 8) 90 :pivot :left))
```

A 90° tube elbow: the left edge of the circle sits on the axis, so the revolution produces a curve. Without `:pivot` the circle would be centered on the axis and the revolution would produce a quarter sphere.

The pivot directions are relative to the profile's 2D plane: `:left`, `:right`, `:up`, `:down`.

### Shape-fns with revolve

Like `loft`, `revolve` accepts shape-fns too. The profile can vary during the rotation:

<!-- example-source: revolve-tapered -->
```clojure
(register horn
  (revolve (tapered (circle 10) :to 0.5) 270))
```

A horn: the circle shrinks progressively during the 270° of revolution. `tapered`, `twisted`, `noisy`, and all the other shape-fns from chapter 6 work with `revolve` exactly as with `loft`.
Note that when a shape-fn is used the profile does not rotate on itself; instead an implicit `:pivot` is imposed and it rotates on one side, to avoid self-intersections.

The rotation angle is silently clamped to 360 degrees if greater. Values greater than 360 or less than -360 behave like 360/-360.

<!-- example-source: i-like-this -->
```clojure
(resolution :n 512)
(register i-like-this
  (revolve (twisted (rect 2 10) :to 0.5) 600))
  
```


### Revolve vs extrude with an arc

An elbow can be made either with `revolve` or with `extrude` + `arc-h`. The difference: `extrude` + arc drags the profile along a curve, so the section stays perpendicular to the trajectory. `revolve` rotates the profile around an axis, so the section stays perpendicular to the plane of rotation. For small angles the results are almost identical; for large angles (90° and beyond) the geometry diverges. As a practical rule: `revolve` with `:pivot` for elbows and architectural corners, `extrude` + arc for tubes that follow a path.

## Chaining: extrude+, revolve+, transform->

Imagine building a frame: a straight stretch, a 30° curve, another straight stretch, a curve in the opposite direction, a final stretch. With ordinary `extrude` and `revolve` you would have to build each segment separately, position the turtle at the end of the previous segment, and then `mesh-union` all the pieces. It is doable, but verbose and fragile: if you change one angle, all the following segments have to be repositioned by hand.

`extrude+` and `revolve+` solve the problem by returning, besides the mesh, also the shape and the pose of the end face. That data becomes the input of the next segment.

### extrude+ and revolve+

<!-- example-source: chaining-manual -->
```clojure
(def profile (shape-difference (rect 40 40) (rect 30 30)))

(def seg1 (extrude+ profile (f 20)))

(def corner (turtle :pose (:pose (:end-face seg1))
              (revolve+ (:shape (:end-face seg1)) 30 :pivot :left)))

(def seg2 (turtle :pose (:pose (:end-face corner))
            (extrude+ (:shape (:end-face corner)) (f 30))))

(register frame
  (mesh-union (:mesh seg1) (:mesh corner) (:mesh seg2)))
```

`extrude+` returns a map `{:mesh ... :end-face {:shape ... :pose ...}}`. The `:mesh` is the solid, the `:end-face` is the profile and the pose at the end of the segment. The next segment uses `turtle :pose` to position itself at the pose of the end face, and from there extrudes or revolves. At the end, `mesh-union` merges everything.

The pattern works but is repetitive: each segment has to extract `:end-face`, open a `turtle` scope, and pass the shape. `transform->` automates all of it.

### transform->

<!-- example-source: chaining-transform -->
```clojure
(register frame
  (transform-> (shape-difference (rect 40 40) (rect 30 30))
    (extrude+ (f 20))
    (revolve+ 30 :pivot :left)
    (extrude+ (f 30))
    (revolve+ -30 :pivot :right)
    (extrude+ (f 20))))
```

A single expression. `transform->` takes an initial shape and a sequence of steps. At each step, the shape and the pose are passed automatically from the end-face of the previous step. All the meshes produced are merged with `mesh-union`. The result is a single solid.

Inside `transform->`, the operations do not take the shape as an argument (it is injected automatically). `(extrude+ (f 20))` receives the current shape and extrudes it forward by 20. `(revolve+ 30 :pivot :left)` receives the current shape and revolves it by 30° with the left edge on the axis.

### Continuing from an existing piece

`transform->` also accepts an end-face as its first argument, not only a shape. This lets you continue a chain from a segment built separately:

```clojure
(def base (extrude+ my-profile (f 10)))

(register result
  (mesh-union (:mesh base)
    (transform-> (:end-face base)
      (revolve+ 45 :pivot :left)
      (extrude+ (f 30)))))
```

The first segment is built separately; `transform->` restarts from its end face and adds the following segments.

### When to use chaining

Chaining is the right tool for geometries that develop as a sequence of connected segments: frames, bent tubes, moldings, rails. The profile stays constant (or varies in a controlled way) and each segment starts exactly where the previous one ends, with no gaps or overlaps.

For assemblies where the pieces are not connected in sequence but arranged freely in space, `attach` and the skeleton system from chapter 8 are more appropriate.

A note: at the moment `loft+` (the chaining variant of `loft`) is not yet implemented. To chain a segment with a varying profile you have to evaluate the shape-fn at `t=1` manually and reposition the turtle. The implementation of `loft+` is in the Roadmap (§1.5).
