<!--
=========================================================================
NOTE INTERNE PER L'AUTORE — NON DESTINATE AL LETTORE FINALE

Struttura del capitolo:
3.1  A cosa servono le shape (motivazione + esempio extrude)
3.2  Forme predefinite (circle, rect, polygon, star + stamp)
3.3  Forme personalizzate (poly, shape)
3.4  Profili come valori (concetto: shape è valore nel piano locale turtle)
3.5  Booleane 2D
3.6  Raccordi e smussi 2D (fillet, chamfer, offset)
3.7  Dove si usano le shape (mappa dei consumatori)
3.8  Generare shape da mesh (slice-mesh, project-mesh, face-shape)
3.9  Più forme alla volta (vettori di shape)
3.10 Forme di testo (cenno + rimando cap. 13)
3.11 Forme riutilizzabili (defn che restituisce shape)

Decisione strutturale: la 3.1 apre con un esempio motivazionale
(shape → extrude) per evitare che il capitolo risulti astratto
fino al cap. 4. La 3.7 è la mappa completa dei consumatori,
non un cenno. stamp introdotto nella 3.2 come strumento di
visualizzazione, non in una sezione dedicata.
=========================================================================
-->

# Working with 2D shapes

<!-- level: base -->

## What shapes are for

In the previous chapter we built every piece from a 3D primitive: `box`, `cyl`, `sphere`, `cone`. It works, but there is a limit: you cannot freely choose the cross-section of an object. A cylinder always has a circular section, a box always a rectangular one. If you want a tube with a hexagonal section, or an L-shaped bar, primitives are not enough.

**Shapes** are 2D profiles: circles, rectangles, polygons, hand-drawn outlines. On their own they do not produce a solid. They become a solid when you feed them to an operation that turns them into 3D, such as `extrude`:

<!-- example-source: shape-motivation -->
```clojure
(register c-channel
  (extrude
    (shape (f 20) (th 90)
      (f 20) (th 90)
      (f 20) (th 90)
      (f 5) (th 90)
      (f 15) (th -90)
      (f 10) (th -90)
      (f 15))
    (f 40)))
```

The first argument to `extrude` is a shape: a C-shaped profile drawn with the 2D turtle commands. The rest are movement commands that define the extrusion path, like the turtle commands you already know. `(f 40)` extrudes the profile forward by 40. The result is a C-channel 40 long, with exactly the section we drew.

The bridge with the previous chapter is direct: `(box 10 20 30)` produces the same solid as `(extrude (rect 10 20) (f 30))`. Primitives are shortcuts for the most common sections; shapes are the general tool.

Extrusion is only one of the consumers of shapes. There are others: `loft` (extrudes while deforming the profile along the path), `revolve` (rotates the profile around an axis), `stamp` (displays the 2D profile without producing a solid). We will see them all at the end of the chapter (§3.7). For now there is just one point: shapes are the *raw material*, and this chapter explains how to create and work it. The following chapters explain how to turn it into solids.

## Predefined shapes

Ridley has four ready-made 2D primitives: `circle`, `rect`, `polygon`, `star`. These are the shapes you use most often, on their own or as a starting point for more complex compositions.

To see them in the viewport without extruding, there is `stamp`: it draws the 2D outline of the shape at the turtle's current position, without producing a solid. It is the right tool for inspecting a profile before feeding it to `extrude`.

<!-- example-source: shape-circle -->
```clojure
(stamp (circle 20))
```

`(circle 20)` is a circle of radius 20. With a second argument you can control the number of segments: `(circle 20 128)` for a smoother circle, `(circle 20 6)` for a regular hexagon (though for that there is `polygon`, which is more expressive).

<!-- example-source: shape-rect -->
```clojure
(stamp (rect 30 15))
```

`(rect 30 15)` is a rectangle 30 wide (to the right) and 15 tall (upward), centered on the turtle. The two arguments follow the same convention as `box`: the first is the size to the right, the second upward. `(rect 30 15)` and `(box 30 15 ...)` have the same section.

<!-- example-source: shape-polygon -->
```clojure
(stamp (polygon 6 12))
```

`(polygon 6 12)` is a regular hexagon inscribed in a circle of radius 12. The first argument is the number of sides, the second the radius. It works for any regular polygon: `(polygon 3 10)` for an equilateral triangle, `(polygon 8 15)` for an octagon.

<!-- example-source: shape-star -->
```clojure
(stamp (star 5 20 10))
```

`(star 5 20 10)` is a five-pointed star with outer radius 20 and inner radius 10. The ratio between the two radii controls how pronounced the points are: if the inner radius approaches the outer one the star becomes almost a decagon, if it approaches zero the points become needles.

All 2D primitives are centered on the turtle. `stamp` is handy for checking them at a glance, and you can also toggle it from the "Stamps" button in the viewport toolbar, or with `(show-stamps)` / `(hide-stamps)`.

## Custom shapes

The primitives cover circles, rectangles, regular polygons, and stars. For any other profile there are two constructors: `poly` for those who think in coordinates, `shape` for those who think in lengths and angles.

### `poly`: explicit coordinates

`poly` builds a polygon from pairs of X/Y coordinates. X is the turtle's "right" direction, Y is "up".

<!-- example-source: poly-triangle -->
```clojure
(stamp (poly 0 0  40 0  0 30))
```

A right triangle: three vertices, six numbers. The vertices are read in pairs: `(0,0)`, `(40,0)`, `(0,30)`. The outline closes automatically from the last vertex to the first.

The coordinates can also be passed as a vector, useful when the points come from a computation or a variable:

<!-- example-source: poly-diamond -->
```clojure
(def diamond-pts [0 5  5 0  0 -5  -5 0])
(stamp (poly diamond-pts))
```

`poly` is the right tool when you already have the coordinates: vertices measured from a drawing, points generated by a formula, or simple profiles like triangles and trapezoids where writing the X/Y pairs is more direct than reasoning in angles.

One thing to watch out for: `poly` preserves the winding order you give it. If the vertices are clockwise the profile is reversed, and the extruded faces point the wrong way. Booleans behave strangely, the mesh looks intact but the results are wrong. The rule is: vertices counterclockwise in the right-up plane. The primitives and `shape` correct the winding automatically; `poly` does not. If you notice it after the fact, `reverse-shape` reverses the winding of a shape (or of a vector of shapes) and straightens the normals.

For the cases where even `poly` is not enough, because the points come from a computation, you need holes, or you need explicit control over the anchoring, there is the low-level constructor `make-shape`: it takes a vector of `[x y]` points (counterclockwise outer contour, optional clockwise holes) and wraps them into a shape. For everything else the constructors above are more convenient, because they set the winding and anchoring for you.

### `shape`: drawing with the turtle

`shape` describes an outline by moving a 2D turtle. It has only two commands: `f` (forward) and `th` (turn). The turtle starts at the origin facing right (+X), and the outline closes automatically from the last point to the first.

<!-- example-source: shape-l-profile -->
```clojure
(stamp (shape (f 10) (th 90)
              (f 5)  (th 90)
              (f 5)  (th -90)
              (f 5)  (th 90)
              (f 5)))
```

An L-shaped profile. The 2D turtle starts at the origin facing right (+X), so the first `(f 10)` traces a horizontal segment to the right. `(th 90)` turns left by 90°, and from there the turtle faces up (+Y), so the next `(f 5)` rises. Then left again, forward 5, right by 90° (for the step), forward 5, left, forward 5. The closing segment is added by `shape`.

`shape` is more natural than `poly` when the profile is made of straight segments with known angles: C, T, and U profiles, teeth, rails. There is no need to compute the vertex coordinates: you reason in "how long is this stretch" and "how much do I turn".

<!-- example-source: shape-right-tri -->
```clojure
(def right-tri (shape (f 4) (th 90) (f 3)))
(stamp right-tri)
```

A right triangle with legs 4 and 3. Two segments, one angle, and the closing segment makes the hypotenuse. The same triangle with `poly` would be `(poly 0 0  4 0  4 3)`: there you need to know the coordinates of the third vertex, with `shape` it is enough to know the lengths.

### When to use which

The choice is almost always obvious. If you have the coordinates (measured, computed, copied from another program), use `poly`. If you think in terms of "go forward, turn, go forward", use `shape`. For symmetric or standard profiles like circles, rectangles, and regular polygons, the primitives from the previous section are more expressive than either.

A practical rule: if you are about to write `poly` with a bunch of coordinates computed by hand using sines and cosines, `shape` with the right angles is probably more readable. If you are about to write `shape` with angles computed with `atan2` to reach a specific point, `poly` with the direct coordinates is probably simpler.

There is also a case where `shape` has no rival: procedural, Logo-style generation. When the profile is a repeating motif, a loop that emits turtle commands is far more direct than computing the vertices one by one. This sun comes from eighteen repetitions of the same tooth:

<!-- example-source: shape-sun -->
```clojure
(def sun
  (shape
    (dotimes [_ 18]
      (f 2)
      (th 80) (f 5) (th -160) (f 5) (th 80)
      (f 2)
      (th -20))))

(stamp sun)
```

With `poly` you would have had to compute every spike and valley vertex with sines and cosines; with `shape` you describe one tooth once and repeat it with `dotimes`. It is the same principle as the Logo turtle: the shape emerges from the movement, not from the coordinates.

### Importing from SVG

A shape can also come from SVG: `svg-shapes` reads an SVG string and returns a vector of shapes (one per geometric element), `svg-shape` takes just one by `:id` or `:index`. It is useful above all for reusing existing vector art. There is, however, a practical limit to know: the string passed to `(svg ...)` cannot contain the `"` character, because Clojure has no raw strings and that character would close the string. Hand-written SVG works around this by using single quotes in the attributes; SVGs exported from Inkscape or Illustrator use double quotes and therefore do not paste in directly, they have to be loaded as files. Loading from a file, which is the practical route, is in chapter 9 (Workspaces and Libraries).

## Profiles as values

If you come from a CAD with sketches (Fusion, SolidWorks, FreeCAD), you are used to thinking of 2D profiles as entities anchored to a plane: you pick a face, draw on it, the sketch stays there and you go back to edit it. In Ridley it does not work that way.

A shape is a value. It has coordinates in the turtle's 2D plane (right and up), but it has no position in the 3D world. A `(def L (shape ...))` does not place anything in the viewport: it produces a profile in memory, with its points in the local plane. The shape acquires a position and orientation in the world only when something consumes it: `extrude`, `loft`, `revolve`, `stamp`. The consumer reads the turtle's pose at that moment and projects the profile onto the plane orthogonal to the direction the turtle is facing.

Three practical consequences.

The first: the same shape, consumed in different poses, produces different meshes.

<!-- example-source: same-shape-two-poses -->
```clojure
(def L
  (shape (f 10) (th 90)
         (f 5)  (th 90)
         (f 5)  (th -90)
         (f 5)  (th 90)
         (f 5)))

(register a (extrude L (f 8)))
(rt 30)
(register b (extrude L (f 8)))
```

Two identical L-bars, offset by 30 to the right. The profile is the same value `L` consumed twice; the turtle moved between the two calls, so the two meshes end up in different positions. The profile does not know where it will end up; the turtle decides.

The second: the same shape, consumed with the turtle rotated, produces meshes oriented differently.

<!-- example-source: same-shape-tilted -->
```clojure
(register C (extrude (star 5 6 10) (f 4)))
(rt 25) (tv 40)
(register D (extrude (star 5 6 10) (f 4)))
```

The second bar is tilted because the turtle rotated before the extrusion. The profile did not change: it is the plane it is projected onto that is different.

The third: a parametric profile is simply a function that returns a shape.

<!-- example-source: parametric-profile -->
```clojure
(defn channel [w d wall]
  (shape-difference
    (rect w d)
    (rect (- w (* wall 2)) (- d wall))))

(register small (extrude (channel 10 8 2) (f 30)))
(rt 25)
(register large (extrude (channel 20 15 3) (f 30)))
```

`channel` is not a shape: it is a function that produces a different one each time, based on width, depth, and wall thickness. Two calls with different parameters, two different profiles, two different meshes. No "edit sketch dialog" needed.

This way of thinking about profiles, as values to pass around and not as entities that live on a plane, is the biggest conceptual difference from sketch-based CAD. Once it is internalized, the rest of the chapter flows: every operation on shapes (booleans, fillets, offsets) produces a new value that you can feed to any consumer.

## 2D booleans

Shapes combine with the same logical operations as 3D meshes: union, difference, intersection, exclusive or. The result is a new shape, ready to be extruded or worked further.

<!-- example-source: shape-washer -->
```clojure
(def washer (shape-difference (circle 20) (circle 14)))
(stamp washer)
```

`shape-difference` subtracts a smaller circle from a larger one: the result is a ring. Extruded, it becomes a hollow tube. It is the same "solid minus cavity" pattern as the pen holder in chapter 2, but applied in the 2D plane before extrusion instead of in the 3D volume after.

<!-- example-source: shape-l-tube -->
```clojure
(def outer (shape-union (rect 20 40) (rect 40 20)))
(def inner (shape-offset outer -3))
(def l-tube (shape-difference outer inner))
(stamp l-tube)
```

`shape-union` merges two overlapping rectangles into an L-shaped profile. `shape-offset` with a negative delta contracts the outline by 3 toward the inside, producing a smaller copy. The difference between the two leaves an L-shaped shell of uniform thickness 3. Here `shape-offset` appears early because it is the most natural way to build the inner profile; we will see it better in the next section.

The four operations:

`shape-union` merges two or more shapes into the outline that encloses them both. Where they overlap, the outer outline becomes a single one.

`shape-difference` subtracts the second shape from the first. Useful for creating holes, cavities, hollow profiles. The order matters: `(shape-difference A B)` is A with B carved out of it, not the other way around.

`shape-intersection` keeps only the region where both shapes are present. Useful for trimming a profile with a cutting shape.

`shape-xor` keeps the regions where only one of the two shapes is present, excluding the overlap. Unlike the other three, `shape-xor` returns a vector of shapes (because the result can be made of several disconnected pieces). The vector is accepted directly by `extrude`, `loft`, `revolve`, and `stamp`.

<!-- example-source: shape-boolean-xor -->
```clojure
(def a (circle 15))
(def b (translate-shape (circle 15) 12 0))
(stamp (shape-xor a b))
```

Two overlapping circles: `shape-xor` produces two crescents, one per side.

One thing to know: 2D booleans handle holes. If a `shape-difference` creates a hole in the profile (an inner island that does not touch the outer edge), the hole is preserved in the resulting shape, and the downstream operations (`extrude`, `loft`, `revolve`) respect it. There is no need to do anything special: holes are automatic.

## 2D fillets and chamfers

Profiles built with straight segments have sharp corners. Three operations soften or modify them before extrusion: `fillet-shape` rounds the corners, `chamfer-shape` cuts them at 45°, `shape-offset` expands or contracts the outline.

<!-- example-source: fillet-rect -->
```clojure
(stamp (fillet-shape (rect 40 20) 5))
```

`fillet-shape` replaces each corner of the rectangle with a circular arc of radius 5. The result is a rectangle with rounded corners, often called a "pill shape" when the radius is half the short side.

<!-- example-source: chamfer-hex -->
```clojure
(stamp (chamfer-shape (polygon 6 20) 3))
```

`chamfer-shape` cuts each corner of the hexagon with a straight segment at distance 3 from the vertex. The result is a dodecagon with alternating long and short faces.

Both accept an `:indices` option to work only on some vertices:

<!-- example-source: fillet-selective -->
```clojure
(stamp (fillet-shape (rect 30 15) 4 :indices [2 3]))
```

Only vertices 2 and 3 of the rectangle are rounded: the result is a tab with two right corners and two rounded ones. The indices start at 0 and follow the order of the shape's vertices.

For smoother arcs, `fillet-shape` accepts `:segments` (default 32, derived from the current resolution): `(fillet-shape (rect 40 20) 5 :segments 64)` produces softer curves.

### Offset

`shape-offset` expands or contracts an outline by a given distance.

<!-- example-source: offset-expand -->
```clojure
(stamp (shape-offset (rect 30 20) 3))
```

Positive delta: the rectangle grows by 3 on all sides. The result is a larger rectangle with rounded corners, because the offset uses `:round` joins by default. It is the fastest way to get a rectangle with rounded corners without having to choose a fillet radius.

<!-- example-source: offset-contract -->
```clojure
(stamp (shape-offset (rect 30 20) -3))
```

Negative delta: the rectangle contracts by 3 on all sides. Useful for generating the inner profile of a shell, as in the L-tube example from the previous section.

The type of join at the corners is controlled with `:join-type`:

- `:round` (default): rounded corners
- `:square`: squared corners, extended
- `:miter`: sharp corners, extended to a point

<!-- example-source: offset-miter -->
```clojure
(stamp (shape-offset (rect 30 20) 3 :join-type :miter))
```

With `:miter` the expanded rectangle stays a rectangle, with no rounding.

`shape-offset` is often the natural complement of the booleans: expand for the outer profile, contract for the inner profile, difference for the shell. We have already seen it in 3.5 with the L-tube, and it is a pattern that recurs frequently.

### Generative operators

One last family of operators does not modify an existing profile but derives a new one algorithmically, usually by perforating it. They produce shapes with holes, which extrude and compose with shape-fns like any other.

`voronoi-shell` perforates a profile with a Voronoi cell pattern: the cell edges become material, the interiors become holes.

<!-- example-source: voronoi-shell-tube -->
```clojure
(register voro-tube
  (extrude (voronoi-shell (circle 20) :cells 40 :wall 1.5) (f 50)))
```

A perforated tube: the cylinder wall is broken into 40 cells separated by ribs of 1.5. `:cells` and `:wall` control the density and thickness of the cells, `:seed` makes the pattern reproducible.

`pattern-tile` perforates instead with a pattern repeated on a grid: it tiles one shape over the bounding box of another and subtracts the copies that overlap.

<!-- example-source: pattern-tile-grid -->
```clojure
(register grid-plate
  (extrude (pattern-tile (rect 60 40) (circle 2 16) :spacing [6 6]) (f 3)))
```

A plate with a regular 6×6 grid of circular holes. The pattern can be any shape, not just a circle; `:spacing` controls the grid pitch, `:inset` shrinks each copy before subtracting it.

These are the first of a family that can grow: operators that generate 2D geometry from a rule instead of from coordinates or turtle commands.

## Where shapes are used

We opened the chapter showing `extrude` as motivation: shapes matter because they become solids. But `extrude` is not the only consumer. Here is the complete map of the places where a shape is accepted as input.

### extrude

Drags the profile along a straight or curved path. The section stays constant from one end to the other. The result is a mesh.

<!-- example-source: where-extrude -->
```clojure
(register tube (extrude (circle 10) (f 30) (th 45) (f 20)))
```

A circular tube that bends by 45° halfway along the path. The profile is a circle, the path is defined by the turtle commands after the shape. All of chapter 4 is dedicated to extrusion.

### loft

Like `extrude`, but the profile can change along the path: shrink, twist, deform. The change is described by a shape-fn (a function that maps `t ∈ [0,1]` to a shape) or by two shapes for start and end.

<!-- example-source: where-loft -->
```clojure
(register tapered-tube (loft (tapered (circle 15) :to 0) (f 30)))
```

A cone: the circle shrinks to zero along the path. `tapered` is a built-in shape-fn; there are many others (`twisted`, `fluted`, `morphed`, `shell`, etc.) and they compose with `->`. Chapter 6 covers them in detail.

### revolve

Rotates the profile around the turtle's axis, producing a solid of revolution. The profile lies in the right-up plane, the axis of revolution is the forward direction.

<!-- example-source: where-revolve -->
```clojure
(def profile (shape-difference
               (rect 20 20)
               (translate-shape (circle 18) -5 8.5)
               ))
(register bowl (revolve profile))
```

A bowl: the profile (a sort of open U) is rotated 360° around the up axis. `revolve` accepts shape-fns too, so the profile can vary during the rotation.

### stamp

Displays the profile as a 2D outline at the turtle's current pose, without producing a solid. We have used it throughout the chapter to inspect shapes.

<!-- example-source: where-stamp -->
```clojure
(stamp (fillet-shape (rect 30 20) 5))
```

### 2D operators

Shapes are also the input of other 2D operators: the booleans (`shape-union`, `shape-difference`, `shape-intersection`, `shape-xor`), the modifiers (`shape-offset`, `fillet-shape`, `chamfer-shape`, `shape-hull`, `shape-bridge`), the transformations (`scale`, `rotate`, `translate`, `morph-shape`, `resample-shape`), and the generative operators (`voronoi-shell`, `pattern-tile`). The result is always another shape, ready to be fed to one of the consumers above. We saw them in sections 3.5 and 3.6.

### Summary

| Consumer | What it produces | Chapter |
|---|---|---|
| `extrude` | mesh (constant section) | 4 |
| `loft` | mesh (variable section) | 6 |
| `revolve` | mesh (solid of revolution) | 4 |
| `stamp` | 2D preview (no solid) | — |
| 2D operators | another shape | 3 |

The typical flow is: build the shape (3.1-3.4), work it with booleans and modifiers (3.5-3.6), then feed it to a consumer. The shape is the raw material, the consumer is the tool that turns it into a solid.

## Generating shapes from meshes

So far we have built shapes from scratch: coordinates, turtle commands, primitives, compositions. But sometimes the profile you need already exists, hidden inside a 3D mesh: the section of a vase at a certain height, the outline of a piece seen from above, the contour of a face. Three operations extract it: `slice-mesh` cuts the mesh with the turtle's plane and returns the contour of the section, `project-mesh` projects its silhouette onto the plane, `face-shape` extracts the outline of a single face. The result is always a standard shape (or a vector of shapes), ready for `extrude`, `loft`, or any operator in this chapter: the 2D → 3D flow also works in reverse.

These operations work on meshes and their cutting planes, concepts of chapter 7: their home is section 7.5, with the examples and the special cases. Here it is enough to know they exist: when a mesh already contains the profile you need, you do not have to redraw it.

## Several shapes at once

Some operations return not a single shape but a vector of shapes: `shape-xor` (which can produce disconnected regions), `slice-mesh` (which can cut several contours), `text-shape` (which produces one shape per letter or per composite glyph). This is not a special case to handle: the main consumers (`extrude`, `loft`, `revolve`, `stamp`) accept a vector of shapes directly and treat it as a single profile with multiple contours.

<!-- example-source: vector-of-shapes-text -->
```clojure
(register hello (extrude (text-shape "Hello" :size 20) (f 3)))
```

`text-shape` returns a vector of shapes (one per letter, some with holes like the "e" and the "o"). `extrude` extrudes them all along the same path and merges the result into a single mesh. No `mesh-union` or `concat-meshes` needed: the vector is handled internally.

The same holds for `slice-mesh`:

<!-- example-source: vector-of-shapes-slice -->
```clojure
(register B (attach (box 10 20 30) (tv 30) (th 15)))
(register ring (extrude (slice-mesh :B) (f 30)))
```
The example creates a shape from the intersection of the box, slightly rotated, with the XY plane (the turtle is at the origin, facing +X) and extrudes that shape forward.

If the section contains several contours, they are all extruded together. If it contains only one, it works anyway: a vector with one element is handled as a single shape.

The 2D operators accept vectors too: `shape-offset`, `fillet-shape`, `chamfer-shape` apply the operation to each shape in the vector and return a vector. The booleans take single shapes as arguments, but the result of `shape-xor` (a vector) can be given directly to a consumer without unpacking it.

The rule is simple: where you accept a shape, you also accept a vector of shapes. There is no need to think about it unless you need to work on a single contour of the vector, in which case you just index it with `(nth shapes 0)` or `(first shapes)`.

## Text shapes

`text-shape` converts a string of text into a vector of 2D shapes, using OpenType font parsing. The letters become outlines with holes (the "o", the "e", the "B"), ready for extrusion.

<!-- example-source: text-shape-basic -->
```clojure
(register title (extrude (text-shape "Ridley" :size 15) (f 2)))
```

The default font is Roboto. `:size` controls the height of the letters. The result is a vector of shapes (one per glyph), which `extrude` handles directly as seen in the previous section.

Text in Ridley goes beyond simply extruding letters: chapter 13 covers custom fonts, text that follows a curve with `text-on-path`, and measuring the footprint with `text-width`. Here the point is only that text shapes are normal shapes: they can be transformed, combined with booleans, fed to `loft` or `revolve` exactly like any other shape.

<!-- example-source: text-shape-difference -->
```clojure
(register stamp-block
  (mesh-difference
    (box 80 20 5)
    (attach (extrude (text-shape "HELLO" :size 12) (f 30)) (f -10))))
```

Text engraved in a block: the extruded letters are moved back with `attach` so that they cross the front face, and then subtracted from the mesh with `mesh-difference`.

## Reusable shapes

In chapter 2 we saw `defn` for creating reusable 3D pieces. The same holds for shapes: a function that returns a shape is a parametric profile.

<!-- example-source: reusable-t-profile -->
```clojure
(defn t-profile [w h flange web]
  (shape-union
    (rect w flange)
    (translate-shape (rect web (- h flange)) 0 (/ (- h flange) -2))))

(register small (extrude (t-profile 20 30 4 3) (f 40)))
(rt 40)
(register large (extrude (t-profile 40 50 6 4) (f 60)))
```

`t-profile` is a function that produces a T-shaped profile: a horizontal flange (the wide rectangle) and a vertical web (the narrow rectangle, moved downward with `translate-shape`). Four parameters: total width, total height, flange thickness, web thickness. Two calls with different parameters, two different bars.

Composition goes beyond numeric parameters. A function can accept a shape as an argument and modify it:

<!-- example-source: reusable-hollow -->
```clojure
(defn hollow [shape wall]
  (shape-difference shape (shape-offset shape (- wall))))

(register hex-tube
  (extrude (hollow (polygon 6 20) 3) (f 40)))

(rt 50)
(register round-tube
  (extrude (hollow (circle 15) 2) (f 40)))
```

`hollow` takes any shape and makes a hollow version of it, contracting the outline by `wall` and subtracting it from the original. It works with circles, polygons, rectangles, custom profiles: the "solid minus contraction" pattern does not depend on the shape, it depends only on the operation.

This is the same principle seen in 3.4: a shape is a value, a function that returns a shape is a factory of values. By combining `defn` with the 2D operators of this chapter, you build a personal library of parametric profiles that you reuse throughout the model.
