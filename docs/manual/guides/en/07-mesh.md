<!--
Convenzioni accertate per il capitolo 7:
- Una mesh Ridley è una mappa Clojure con :vertices, :faces, :creation-pose, e campi opzionali (:primitive, :face-groups, :material, ::raw-arrays).
- :vertices è un vettore di vettori [x y z], :faces è un vettore di vettori [i j k] (indici nei vertici).
- ::raw-arrays è una cache di sola uscita per Three.js e export; non viene usata nel path inverso (mesh → Manifold).
- :creation-pose registra la posa della turtle al momento della creazione.
- mesh-diagnose è puro ClojureScript, niente Manifold WASM.
- manifold? e mesh-status richiedono Manifold WASM.
- merge-vertices è il fix più comune per mesh non-manifold dopo CSG.
- inset-face e scale-face NON esistono come binding SCI.
- Riorganizzazione 2026-06-12: diagnosi/riparazione spostate dalla 7.1 alla 7.7 (in coda), framing dalla rarità; la 7.2 definisce manifold inline.
- Codice esempi in inglese, prosa in italiano. No em-dash.
-->

# 7. Mesh

<!-- level: advanced -->

## 7.1 What a mesh is

Up to here you have built objects in several ways: calling a primitive, extruding a 2D shape along a path, revolving a profile, or composing shapes with shape-fns. In all these cases the final result is the same: a *mesh*.

A mesh is the 3D representation that Ridley uses for everything that appears in the viewport, gets exported to STL or 3MF, and takes part in boolean operations. It is the format into which every construction technique converges.

In Ridley a mesh is a Clojure map. You can inspect it, print it in the REPL, compare it with `=`, pass it to a function, save it in a variable with `def`. It is not an opaque object, it is not a pointer to native memory: it is data, like everything else.

The minimal structure is this:

```clojure
{:type :mesh
 :vertices [[x y z] [x y z] ...]
 :faces    [[i j k] [i j k] ...]}
```

`:vertices` is a vector of points in space. Each point is a vector `[x y z]`. `:faces` is a vector of triangles, where each triangle is a vector of three indices that point into `:vertices`. A cube, for example, has 8 vertices and 12 triangular faces (two triangles for each square face of the cube).

Besides these two essential fields, a mesh carries other data. `:creation-pose` records the position and orientation of the turtle at the moment of creation: it is what `attach` uses to reposition the mesh correctly when you attach it to another. `:primitive` remembers which primitive generated it (`:box`, `:sphere`, `:cylinder`...), and `:face-groups` maps symbolic names like `:top`, `:bottom`, `:front` to the corresponding groups of triangles. Not all meshes have these fields: a mesh produced by a boolean operation has no predefined `:face-groups`, for example.

When `:creation-pose` does not appear, the default applies: `{:position [0 0 0] :heading [1 0 0] :up [0 0 1]}`, that is, the turtle at the origin, looking along X with up along Z. This is the case of a primitive built without moving the turtle, like a `(box 20)`.

The rest of the chapter works on this data: booleans combine meshes (7.2), fillets and chamfers refine them (7.3), faces are found and modified (7.4), sections bring a mesh back to 2D (7.5), `warp` deforms it in space (7.6). At the end, 7.7 gathers diagnosis and repair for the rare cases where a mesh is not healthy.

## 7.2 3D boolean operations

In chapter 2 you already used booleans to compose primitives: union, subtraction, intersection. Here we take them up again in more detail, because working with meshes inevitably means working with booleans, and knowing their patterns and limits makes the difference between a model that builds in a few seconds and one that costs you an hour.

### The three operations

`mesh-union` merges two or more meshes into a single solid. The overlapping parts are resolved: the result has a clean outer surface, with no internal faces.

`mesh-difference` subtracts one or more meshes from a base mesh. The first mesh is the piece you remove from, the following ones are the tools that carve.

`mesh-intersection` keeps only the volume shared among the meshes.

All three accept two or more arguments, or a vector:

```clojure
;; Two arguments
(mesh-union a b)
(mesh-difference base tool)
(mesh-intersection a b)

;; Variadic
(mesh-union a b c d)
(mesh-difference base t1 t2 t3)

;; From a vector (handy with for)
(mesh-union [a b c d])
(mesh-difference [base t1 t2])
```


The vector form is handy when the meshes come from a `for` or a `map`: you can pass the result directly without having to unpack it.

When you have a mixed case — a single mesh on one side and a vector of meshes on the other, as typically happens with `mesh-difference` when the tools come from a `for` — the vector form alone is not enough: you first have to put the single mesh in front of the vector. The most direct way is `into`:

<!-- example-source: bool-into-vector -->
```clojure
(def base (box 40 40 20))
(def cuts
  (for [i (range 4)]
    (attach (cyl 5 30) (th (* i 90)) (f 12))))

(register drilled (mesh-difference (into [base] cuts)))
```

For `mesh-difference`, the order matters: the first element is always the base, the others are the tools. For `mesh-union` and `mesh-intersection` the order is irrelevant.

### Checking a mesh's status

Booleans require *manifold* (or *watertight*) input: meshes that represent a closed solid, with no holes and no anomalous edges. This is the normal case: everything that comes out of primitives, extrusions, lofts, revolves, and booleans is manifold by construction. If a mesh is not, the operation fails. Two tools to check beforehand:

```clojure
(manifold? m)     ;; => true / nil
(mesh-status m)   ;; => detailed information
```

<!-- example-source: bool-status-check
(register cube (box 20))
(println "manifold?" (manifold? cube))
(println "status:" (mesh-status cube))
-->

If a boolean fails and you do not understand why, section 7.7 at the end of the chapter has the diagnosis and repair tools.

### Convex hull

`mesh-hull` computes the convex hull of one or more meshes: the smallest convex solid that contains them all. It is a generative operation, not a boolean in the classic sense: it does not cut and does not merge, it builds a new shape *around* the starting meshes.

```clojure
;; Capsule from two spheres
(register s1 (sphere 10))
(f 30)
(register s2 (sphere 10))
(register capsule (mesh-hull s1 s2))

;; From a vector
(mesh-hull [s1 s2 s3])
```

<!-- example-source: bool-hull-capsule
(def s1 (sphere 10))
(def s2 (attach (sphere 10) (f 30)))
(register capsule (mesh-hull s1 s2))
-->

You already saw it in chapter 2 to make feet with soft edges. In general, `mesh-hull` is useful whenever you want a shape that wraps a set of pieces with a smooth, convex surface.

### Concat-meshes: combining without booleans

You do not always need a boolean to put several meshes together. `concat-meshes` joins the vertices and faces of several meshes into a single mesh, without doing any intersection computation:

```clojure
(concat-meshes m1 m2 m3)        ; variadic
(concat-meshes [m1 m2 m3])      ; from a vector
```

The result is a mesh that contains several pieces in the same object, without resolving the intersections between them. It works as input to a boolean only if the pieces do not interpenetrate: if two cylinders overlap in space, `concat-meshes` produces a mesh with self-intersections that Manifold rejects. For separate pieces (like the 12 cylinders arranged in a circle in the example below), `concat-meshes` is perfect.

The advantage is speed. Consider a common case: drilling a disk with 12 cylinders arranged in a circle.

```clojure
;; Slow: 11 sequential unions, then one difference
(register plate-slow
  (mesh-difference
    (cyl 30 5)
    (mesh-union
      (for [i (range 12)]
        (attach (cyl 2 20) (tr (* i 30)) (rt 20) )))))

```

<!-- example-source: bool-concat-perf
;; Fast: one concat, then one difference
(register plate-fast
  (mesh-difference
    (cyl 30 5)
    (concat-meshes
      (for [i (range 12)]
        (attach (cyl 2 20) (tr (* i 30)) (rt 20))))))
-->

In the slow version, `mesh-union` performs 11 boolean operations in sequence to merge the 12 cylinders, and then a twelfth to subtract them from the disk. In the fast version, `concat-meshes` glues the cylinders in linear time, and `mesh-difference` does a single boolean operation. For grid or ring patterns with dozens of pieces, the difference is an order of magnitude.

The same trick works in addition: if you have to add many pieces to a base, pass `(concat-meshes (for ...))` as the second argument of `mesh-union` instead of doing N sequential unions.

### When booleans are slow

Boolean operations in Ridley go through Manifold WASM. Every call converts the operand meshes into native Manifold objects, performs the operation, and converts the result back into a Clojure map. Three booleans in a row on the same mesh rebuild the Manifold object three times.

In practice this is not a problem for most models. Booleans become slow when you chain many in sequence on the same piece, typically inside a loop. The `concat-meshes` pattern described above is the main solution. If the model is still slow, the other strategy is to reduce the resolution of the meshes involved: a cylinder with 16 segments costs much less than one with 64 in a boolean.

For variadic unions, Manifold internally uses a tree-union algorithm that reduces in O(n log n) instead of in sequence. This means `(mesh-union [a b c d e f])` is faster than `(-> a (mesh-union b) (mesh-union c) (mesh-union d) (mesh-union e) (mesh-union f))`: prefer the vector form when merging many pieces.

## 7.3 3D fillets and chamfers

After a series of booleans, a model typically has sharp 90° edges where the volumes meet. In a traditional CAD you would apply a fillet or a chamfer by selecting the edges one by one. In Ridley you work by direction with `fillet` and `chamfer`.

An honest preface: chamfering the edges of an arbitrary mesh is a notoriously hard problem, which even commercial CADs solve only partially (Fusion 360, Onshape, and others regularly fail with messages like "fillet/chamfer encountered a non-manifold edge"). `chamfer` and `fillet` in Ridley work well on the common cases — primitives and simple booleans — but they can produce wrong results on complex geometries, in particular when the edges are near other parts of the model. For these cases the manual shows how to recognize the problem, how to limit it with the `:angle` parameter, and when it is better to change strategy using SDFs (chapter 12), which produce soft blends by construction.

### Chamfer: a flat bevel

`chamfer` cuts the sharp edges with an inclined plane. The result is a flat bevel, like a filing.

<!-- example-source: chamfer-basic :warning slow -->
```clojure
(register A (-> (box 30 30 20) (chamfer :top 5)))
(f 60)
(register B (-> (mesh-union
                  (box 30 30 20)
                  (attach (cone 10 2 50) (f -10) (tv 50)))
                (chamfer :all 4.5)))
(f 60)
(register C (-> (mesh-union
                  (box 30 30 20)
                  (attach (cone 10 2 50) (f -10) (tv 50)))
                (chamfer :all 4.5 :angle 90)))
```


The first argument after the mesh is a *direction selector* that chooses which edges to work on: `:top`, `:bottom`, `:left`, `:right`, `:up`, `:down`, or `:all` for all of them. The second is the cut distance.

A shows the simplest case: the top edges of a box are beveled cleanly.

B and C show what happens when the geometry is more complex. A cone is joined to the box with its tip attached at the top and its base suspended in the air. Without the `:angle` parameter, `chamfer` works on many edges, including those around the cone's tip (where the cone meets the box's top plane), and the result is visually wrong. With `:angle 90`, `chamfer` excludes exactly those edges and limits itself to the 90° of the box and the wide base of the cone, producing a correct result.

`:angle` specifies the *minimum fold* (in degrees) for an edge to be considered. The fold is the angle between the outer normals of the two adjacent faces: 0° on a flat surface (no edge), 90° on the right edges of a box, tending to 180° on knife-edges. Edges with a fold *greater than or equal to* `:angle` are touched. The default `:angle 80` catches all edges from 80° up, including the 90° of the box and the ~81° generated by the cone-box union. Raising the threshold excludes the edges with a gentler fold and protects the zones where `chamfer` would make a mess: it is the first tool to try when a chamfer produces a strange result.

### Fillet: a rounded blend

`fillet` does the same thing as `chamfer` but produces a curved blend instead of the flat cut. All the considerations on `chamfer` (the direction selectors, the `:angle` parameter, the limits on complex geometries) apply identically to `fillet`. Internally both operations build "cutters" and subtract them from the mesh with `mesh-difference`: the difference is only in the shape of the cutter (flat for `chamfer`, rounded for `fillet`).

`fillet` has one more parameter, `:segments`, which controls how many steps it uses to approximate the curve:

```clojure
(-> (box 30 30 20) (fillet :top 3 :segments 8))
(-> (box 30 30 20) (fillet :all 2 :segments 8))
```

<!-- example-source: fillet-box
(register filleted (-> (box 30 30 20) (fillet :top 3 :segments 8)))
-->

More segments produce a smoother blend but with more triangles. For 3D printing, 6-8 segments are usually enough.

### When chamfer and fillet are not enough

The situations in which `chamfer` and `fillet` produce wrong results or fail are typically three:

- **Concave edges.** Both operations subtract material along the edge. On a concave (inner) edge this digs a groove instead of beveling the blend. `chamfer` and `fillet` refuse to operate on concave edges.
- **Edges too close to other geometry.** If the cutter generated for an edge approaches or crosses other parts of the model, the result can be malformed geometry. The `:angle` parameter helps exclude the problematic edges. In the case of the cone's tip in the example above, the problem is due to the many triangle vertices, very close to one another, that approximate the cone's point.
- **Self-intersecting geometries or those with very tight tolerances.** The underlying booleans can fail or produce small artifacts.

If you find yourself in one of these cases and no combination of `:angle` or directional selection satisfies you, the alternative strategy is to build the object directly as an SDF (chapter 12). `sdf-blend`, `sdf-blend-difference`, and the like produce soft blends *by construction*: the blending is built into the combination operations, not added as post-processing. They do not have the limits of `chamfer`/`fillet` because they do not operate on the topology of an existing mesh, but on the implicit representation.

### Chamfer-edges and chamfer-prisms: the low level

`chamfer` and `fillet` use the direction selectors to choose the edges. If you need finer control, `chamfer-edges` works directly on the topology of the mesh: it finds all the edges whose dihedral angle exceeds a threshold and bevels them via CSG.

```clojure
(chamfer-edges mesh 2)                         ; default: angle 80
(chamfer-edges mesh 2 :angle 60)               ; lower threshold
(chamfer-edges mesh 2 :where #(pos? (first %))) ; only edges with x > 0
```

<!-- example-source: chamfer-edges-where
(register half-chamfered
  (chamfer-edges (box 30 30 20) 2 :where #(pos? (second %))))
-->

`:where` is a predicate that receives the coordinates `[x y z]` of both endpoints of the edge: only the edges for which both points satisfy the predicate are beveled. With this you can work selectively on half of a piece, on a specific region, or on edges that satisfy any geometric condition.

`chamfer-prisms` returns the intermediate triangular prisms (one per edge) without applying the boolean cut. Useful to inspect where the bevel would fall before applying it:

```clojure
(def prisms (chamfer-prisms (box 30) 2))
;; => [prism1 prism2 ...] or nil
```

You can filter the prisms, transform them, or use them to build asymmetric bevels.

One step further down there is `find-sharp-edges`, which bevels nothing: it inspects the mesh and returns the list of the edges whose dihedral angle exceeds a threshold (`:angle`, default 30°), each described by a map with vertices, positions, angle, midpoint, and normals. It accepts the same `:where` predicate as `chamfer-edges` to filter by position. It serves to inspect where a bevel would fall before applying it, or as input for custom pipelines: the list it produces is exactly the format that `chamfer-edges` and `chamfer-prisms` accept.

### Mesh-refine: subdivision without smoothing

`mesh-refine` subdivides the triangles without moving the vertices: the shape stays identical, but the mesh becomes denser.

```clojure
(mesh-refine m 2)    ; each triangle → 4 sub-triangles
(mesh-refine m 3)    ; each triangle → 9 sub-triangles
```

<!-- example-source: mesh-refine
(register sparse-box (warp (box 20) (sphere 40) (roughen 3 2)))

(register not-so-dense-box
  (attach
    (warp (mesh-refine (box 20) 2) (sphere 40) (roughen 3 2))
    (f 30)))

(register dense-box
  (attach
    (warp (mesh-refine (box 20) 5) (sphere 40) (roughen 3 2))
    (f 60)))
-->

On its own it changes nothing visually. It is useful as a preparatory step when a later operation needs more vertices to work on (typically `warp`, which deforms by moving the existing vertices).

### Mesh-laplacian: topology-preserving smoothing

`mesh-laplacian` is a Taubin smoothing: it moves the vertices on the sharper edges to soften them, without changing the topology of the mesh. It does not add vertices and does not require manifold input.

```clojure
(mesh-laplacian m)
(mesh-laplacian m :iterations 20 :feature-angle 120)
```

<!-- example-source: mesh-laplacian
(register rough (warp (sphere 20 32 16) (sphere 40) (roughen 5 4)))
(register smoothed
  (attach (mesh-laplacian rough :iterations 10) (f 50)))
-->

The behavior is selective: it moves only the vertices on the edges whose dihedral angle is below `:feature-angle` (default 150°), leaving the flat surfaces intact. This makes it ideal for softening aesthetically rough meshes: irregular surfaces from `roughen` or from noise displacement, low-resolution heightmaps, imported STLs, or pieces assembled with `concat-meshes`. It works even on non-manifold meshes, where `fillet` and the booleans cannot operate. (For perforated shells it is not needed: the outlines of the openings come out smooth already thanks to the `:softness` parameter of chapter 6.)

The parameters of the Taubin cycle (`:lambda` 0.5, `:mu` -0.53) are calibrated to smooth without shrinking the volume. More iterations produce a more marked effect.

`mesh-laplacian` works well on dense meshes (hundreds of vertices or more). On sparse meshes like a box of only 8 vertices, each vertex has too few neighbors for the average to make sense, and the filter distorts instead of smoothing: to round a box use `fillet` or `chamfer`, not `mesh-laplacian`.

### Mesh-simplify: reducing triangles

`mesh-simplify` reduces the number of triangles of a mesh by edge-collapse, trying to preserve its shape:

```clojure
(mesh-simplify m 0.5)    ; target: 50% of the original triangles
(mesh-simplify m 0.25)   ; target: 25%
```

<!-- example-source: mesh-simplify :warning slow
(def heavy
  (-> (mesh-difference (box 40 40 20) (cyl 12 30))
      (fillet :all 2)))
(register light (mesh-simplify heavy 0.3 :max-passes 50))
-->

The ratio is a fraction of the original count. The result is approximate: the simplified mesh loses fine detail but keeps the overall shape. Useful to reduce the weight of very dense meshes before export.

`mesh-simplify` works well on isotropic meshes (triangles of similar size) like those coming out of `fillet`, `chamfer`, or booleans on curved geometries. On meshes with very non-uniform triangles — typically UV spheres (`sphere`), which have very dense fans at the poles, or the cap fans of `cyl` — the preferential collapse of the short edges distorts the shape (sphere flattened at the poles, collapsed cylinder): in these cases it is better to rebuild the primitive at a lower resolution than to simplify it after the fact. If the target is not reached, raise `:max-passes` (default 20).

### Choosing the right tool

The choice among these tools depends on what you want to achieve:

If you want to round specific edges (the top ones, the right ones, the ones in a region), use `fillet` with a direction selector or `chamfer-edges` with `:where`. You have precise control over where the operation acts.

If you want to soften the visible facets without changing the topology, use `mesh-laplacian`. It does not add geometry and works on any mesh, even non-manifold.

If you need a simple flat bevel to break the sharp edges (common in 3D printing to avoid the "elephant foot" effect), use `chamfer`. It is the lightest operation.

For soft, global roundings typical of organic shapes, see the SDFs in chapter 12: they produce continuous blends by construction (`sdf-blend`) instead of as post-processing.

## 7.4 Modifying the faces of a mesh

Ridley's primitives are born with named faces: a `box` has `:top`, `:bottom`, `:front`, `:back`, `:left`, `:right`; a `cyl` has `:top`, `:bottom`, `:side`; a `sphere` has `:surface`. These names let you select a face and work on it: move it, extrude a copy of it, extract its outline as a 2D shape, or simply highlight it to understand where you are.

This section covers the whole cycle: finding the faces, inspecting them, modifying them, and re-extracting their shape.

A note on the name: `attach-face` should not be confused with `attach`. `attach` positions a whole mesh in space (chapter 8). `attach-face` moves the vertices of a single face of a mesh. Different operations, different scale, but the principle is the same: you select the object (mesh or face) and then operate on it with turtle commands.

### Named faces and discovered faces

The primitives have predefined face groups. To see them:

```clojure
(def b (box 20))
(face-ids b)        ;; => (:top :bottom :front :back :left :right)
(list-faces b)      ;; => [{:id :top :normal [...] :center [...] ...} ...]
```

<!-- example-source: face-ids-box :no-run
(register b (box 20))
(println (face-ids b))
-->

`face-ids` returns the list of names. `list-faces` returns a list of maps with all the information: normal, center, heading, and the `:id` field that identifies the face.

For detailed information on a single face:

```clojure
(get-face b :top)       ;; basic info
(face-info b :top)      ;; full info: area, edges, vertex positions
```

The meshes produced by boolean operations have no predefined face groups: the merge/subtraction recomputes the geometry and the original names are lost. For these meshes you need the geometric queries.

### Finding faces by direction

`find-faces` selects the faces whose normal is aligned with a direction. The directions are relative to the creation-pose of the mesh, not to the world:

```clojure
(find-faces mesh :top)                          ; faces aligned with heading
(find-faces mesh :bottom)                       ; opposite to heading
(find-faces mesh :up)                           ; aligned with up
(find-faces mesh :all)                          ; all faces grouped
```

<!-- example-source: find-faces-direction :no-run
(register result (mesh-difference (box 40 40 20) (cyl 10 30)))
(println "top faces:" (count (find-faces result :top)))
-->

`:threshold` controls how tight the alignment must be (default 0.7, where 1.0 is perfect). `:where` adds a predicate on the face's map:

```clojure
(find-faces mesh :top :threshold 0.9)           ; only very aligned faces
(find-faces mesh :top :where #(> (:area %) 100)) ; only large faces
```

### Finding faces by position

When you know *where* the face you are looking for is, but not which direction it faces:

```clojure
(face-at mesh [0 0 10])          ;; face whose plane passes nearest the point
(face-nearest mesh [10 0 0])     ;; face whose centroid is nearest the point
```

`face-at` reasons by plane (projection), `face-nearest` by centroid distance. In practice the difference rarely matters: use `face-nearest` as the default.

### Finding the largest face

```clojure
(largest-face mesh)              ;; the largest face of the mesh
(largest-face mesh :top)         ;; the largest in the :top direction
```

<!-- example-source: largest-face :no-run
(register b (box 30 20 10))
(println "largest face:" (:id (largest-face b)))
-->

All the selection functions return maps with an `:id` field that you can pass to `attach-face`, `clone-face`, `face-shape`, and the other operations.

### Auto face groups

If a mesh has no face groups (typical after a boolean), you can generate them automatically:

```clojure
(auto-face-groups mesh)     ;; groups the triangles by coplanar adjacency
(ensure-face-groups mesh)   ;; adds :face-groups only if missing
```

`auto-face-groups` analyzes the topology and groups the adjacent triangles that lie on the same plane. The result is a mesh with `:face-groups` populated, on which you can use `find-faces` and the other selection functions.

`ensure-face-groups` is the idempotent version: if the mesh already has face groups, it returns it unchanged.

### Highlighting faces

To understand visually which face you are selecting:

```clojure
(highlight-face mesh :top)                  ; permanent highlight (orange)
(highlight-face mesh :top 0xff0000)         ; custom color (red)

(flash-face mesh :top)                      ; highlight for 2 seconds, then gone
(flash-face mesh :top 3000)                 ; custom duration (ms)
(flash-face mesh :top 2000 0x00ff00)        ; duration + color

(clear-highlights)                          ; removes all highlights
```

<!-- example-source: flash-face
(register b (box 20))
(flash-face b :top 2000 0x00ff00)
-->

`flash-face` is especially useful in the REPL: you highlight, you look, and the highlight disappears on its own.

### Attach-face: moving a face

`attach-face` moves the vertices of a face along its normal or transforms them in place. It does not create new geometry: the existing vertices move.



<!-- example-source: attach-face-move
(register a (box 20))
(u 50)
(register b (attach-face (box 20) :top (f 5)))

;; multiple operations
(u 50)
(register c (attach-face (box 20) :top (f 10) (inset 3)))

(u 50)

(register d (clone-face (box 20) :top (inset 3) (f 10)))
-->

The operations available inside `attach-face` (and inside `clone-face`) include the turtle commands and three operations specific to faces:

`(f dist)` moves the face along its normal. Positive values move it away from the mesh, negative ones move it closer.

`(th angle)`, `(tv angle)`, `(tr angle)` change the direction of the normal, exactly as they would with the turtle. Useful for tilting a face during a stepped extrusion.

`(inset dist)` shrinks the face toward its center. It is the equivalent of a negative 2D offset applied to the face's outline.

`(scale factor)` scales the face uniformly relative to its center.

Note: these single-argument forms — `(inset dist)` and `(scale factor)` — act on the selected face and only make sense inside `attach-face` and `clone-face`. At top level there is still a distinct `scale`, `(scale mesh factor)` (and in the variants for SDF and 2D shapes), which scales the whole object around its creation-pose, not a single face. For `inset`, instead, there is no standalone function that takes a mesh as an argument.

### Clone-face: extruding a face

`clone-face` duplicates a face and connects it to the mesh with new side geometry. It is the local extrusion: the original face stays where it was, a copy moves along the normal, and the side walls close the gap.



<!-- example-source: clone-face-step
(register b (box 20))
(register b
  (-> b
      (clone-face :top (f 5))
      (clone-face :top (inset 3) (f 5))))
-->

The `inset` + `f` pattern is the most common: you shrink the face and then extrude it, creating a recessed step. By chaining several `clone-face` in a `->` threading you can build multi-level profiles directly on the mesh, without going through separate extrusions and booleans.

### Face-shape: extracting a face's outline

`face-shape` extracts the outline of a face as a 2D shape, ready to be used in an extrusion or in any other operation that accepts a shape. It returns a map with the shape and the pose (position and orientation) of the face in space:


<!-- example-source: face-shape-extrude
(register b (box 30 30 10))
(def top-info (face-shape b (:id (largest-face b :top))))
(turtle (:pose top-info)
  (color 0xffffff)
  (register tower (extrude (:shape top-info) (f 40))))
-->

(:pose top-info) returns the position and direction of the face; open a `turtle` scope with the face's pose and extrude the shape from there:

This pattern is useful for building on top of the result of a boolean: you find the face, extract its shape, and use it as the profile for a new extrusion positioned exactly where you need it.

## 7.5 Sections

Sometimes you need to come back from 3D to 2D: extract the section of a mesh at a certain height, capture the silhouette of an object seen from a direction, or derive the outline of a face to reuse it as a profile. Ridley offers three operations for this, all driven by the turtle's pose.

### Slice-mesh: a section on a plane

`slice-mesh` cuts a mesh with the plane defined by the position and direction of the turtle. The heading vector is the plane's normal. The result is a vector of 2D shapes (a mesh can have several disjoint outlines on the same plane).


<!-- example-source: slice-mesh-cup
(register cup (revolve (shape (f 20) (th 90) (f 30) (th 90) (f 15))))
(tv 90) (f 15)
(def shp (slice-mesh :cup))
(f 30)
(stamp shp)
-->

The coordinates of the resulting shapes are in the local system of the cutting plane: the X axis is the turtle's right, the Y axis is its up. The shapes have `:preserve-position? true`, which means `stamp` renders them in the correct position in the viewport:

```clojure
(f 30)
(stamp shp)    ; displays the section on a plane at distance 30 to make it visible
```

`slice-mesh` accepts either a mesh or a registered name (keyword), or an SDF node (which is materialized automatically).

A typical use is the reverse engineering of a profile: you have a mesh, you want to reuse a section as the base for a new piece. For example, an O-ring that follows the rim of a container, or a reinforcement that wraps an irregular profile.

### Slice-mesh :on: recovering the generative profile

With the `:on` key, `slice-mesh` does something different from the geometric cut: instead of intersecting the mesh with a plane, it returns the generative profile that was swept to build it, with its marks attached. It works on meshes born from `extrude` or `loft` from a marked profile (chapter 5.7).

```clojure
(slice-mesh :mesh :on :mark-name)   ; at the position of a rail mark
(slice-mesh :mesh :on 0.5)          ; halfway along the path (t between 0 and 1)
```

The argument after `:on` is the name of a `mark` along the loft's rail, or a fraction `t` between 0 and 1. For an extrusion the profile is constant, so `:on` always returns the same shape. For a morphing loft (`tapered`, `twisted`, any shape-fn) it returns the cross-section at that `t`: the shape actually scaled and rotated at that height, with the marks riding the deformed points.

```clojure
;; recover the profile halfway along a tapering loft, and rebuild from there
(register horn (loft (tapered (path-to-shape marked-profile) :to 0.4) (f 50)))
(def waist (slice-mesh :horn :on 0.5))
(register collar (extrude waist (f 3)))
```

This is the key difference from the cut. `slice-mesh :mesh` without `:on` gives you the geometric outline where a plane intersects the mesh: a closed curve, with no memory of how the mesh was built. `slice-mesh :mesh :on t` gives you the generative profile, the same 2D data that, re-extruded or re-lofted, reproduces a mesh with the same marks. The first is for the reverse engineering of any section, the second for recovering a profile you know was generated, with its notable points in the right place.

### Slice-at-plane: cutting without the turtle

If the cutting plane comes from a computation and not from the turtle's pose, `slice-at-plane` is the explicit form:

```clojure
;; horizontal cut at Z=90
(slice-at-plane :cup [0 0 1] [0 0 90])

;; vertical cut at X=0 with explicit basis
(slice-at-plane :cup [1 0 0] [0 0 0] [0 1 0] [0 0 1])
```

<!-- example-source: slice-at-plane
(register cup (revolve (shape (f 20) (th 90) (f 30) (th 90) (f 15))))
(tv 90) (f 15)
(def shp (slice-at-plane :cup [0 0 1] [0 0 15]))
(f 30)
(stamp shp)
-->

The first argument is the mesh (or keyword), the second is the plane's normal, the third is a point on the plane. Optionally you can pass the right and up vectors to control the local basis of the resulting shapes. Without them, Ridley chooses an orthogonal basis automatically.

It returns the same kind of result as `slice-mesh`: a vector of 2D shapes with `:preserve-position? true`.

### Project-mesh: silhouette

`project-mesh` projects a mesh onto the plane perpendicular to the turtle's direction, returning the outline of the silhouette as a 2D shape. The difference from `slice-mesh` is that `slice-mesh` gives the section *on* a plane, while `project-mesh` gives the shadow *of* the object seen along a direction.

```clojure
(project-mesh mesh)           ; silhouette along heading
(project-mesh :neck)          ; by registered name
(project-mesh (sdf-blend a b 5))  ; also on an SDF
```

<!-- example-source: project-mesh-silhouette
(register b (box 30 20 10))
(tv 40) (th 30) (f 50)
(stamp (project-mesh :b))
-->

Same conventions as `slice-mesh`: heading is the projection direction, right/up become the local axes of the shape, holes are preserved.

The classic use case is extracting a 2D footprint from a 3D object to build on top of it. For example, taking the top silhouette of a piece, widening it slightly, and using it as a negative to carve a housing pocket:

```clojure
(def footprint
  (turtle (tv 90)
    (let [s (project-mesh my-piece)
          bigger (scale-shape s 1.05)]
      (extrude bigger (f 5)))))
```

## 7.6 Warp: spatial deformation

The operations seen so far work on the topology of the mesh: they cut, merge, round, extract sections. `warp` does something different: it deforms the vertices of a mesh by moving them in space, without changing the connectivity of the triangles. It is the tool for organic effects, dents, bulges, twists, and in general for any shape that does not come from a precise geometric profile.

### How it works

`warp` takes three arguments: a mesh, an influence volume, and a deformation function. Only the vertices that fall inside the volume are touched. The vertices at the edge of the volume have an attenuated effect (hermite falloff), those outside stay put.

```clojure
(warp mesh volume deform-fn)
```

The volume is an ordinary mesh used only for its bounds: `(sphere 25)`, `(box 30 30 20)`, `(cyl 12 40)`. You can position it with `attach`:



<!-- example-source: warp-inflate-basic
(register bumpy
  (warp (box 40) (attach (sphere 25) (f 20) (u 10)) (inflate 5) :subdivide 6))
-->

### Deformation presets

Ridley offers seven presets that cover the most common cases:

`(inflate amount)` pushes the vertices outward along their normals. It creates bulges, bubbles, reliefs.

`(dent amount)` is the opposite: it pushes inward. It creates dents, hollows, imprints.

`(attract strength)` pulls the vertices toward the center of the volume. Strength ranges from 0 (no effect) to 1 (all at the center).

`(twist angle)` rotates the vertices around an axis, with the angle growing linearly along the axis. For cylindrical and conical volumes the axis is detected automatically; for other volumes (box, sphere) you have to specify it: `(twist 90 :x)`, `(twist 90 :y)`, `(twist 90 :z)`. Two conditions for the twist to show: the section must be *not* axisymmetric (a many-sided cylinder twisted around its own axis stays identical to itself, whereas a box or a few-faced prism shows the torsion), and the mesh must have enough vertices along the axis (use `mesh-refine` or `:subdivide`, otherwise the torsion applies only to the ends).

`(squash axis)` squashes the vertices toward a plane perpendicular to the axis. `(squash :z)` flattens in Z. With a second argument you can control how much: `(squash :z 0.5)` is halfway between flat and unchanged.

`(roughen amplitude)` adds noise along the normals, creating an irregular surface. With two arguments, `(roughen amplitude frequency)`, you also control the spatial frequency of the noise.



<!-- example-source: warp-presets
(defn transp [m]
  (-> m
    (color 0xffff00)
    (material :opacity 0.2)))


(def bump_control (attach (sphere 49.5) (u 30)))
(register BC (transp bump_control))
(register bumpy (warp (box 36) bump_control (inflate 7.5) :subdivide 3.3))

(f 100)
(def dent_control (attach (sphere 19) (f 13.5) (u 9)))
(register DC (transp dent_control))
(register dented (warp (sphere 22) dent_control (dent 12.9)))

(f 100)
(def twist_control (attach (box 40 70 40) (u 40) (f 5)))
(register TC (transp twist_control))
(register twisted (warp (mesh-refine (box 15 60 15) 32) twist_control (twist 90 :z)))

(f 100)
(def roughen_control (attach (sphere 22) (u 10)))
(register RC (transp roughen_control))
(register rough (warp (sphere 20 128 128) roughen_control (roughen 8 4)))
-->
For some presets the example shows the mesh that expresses the influence volume in transparent yellow and the deformed mesh in light blue.

### Chaining deformations

You can pass several deformation functions to a single call of `warp`:

```clojure
(warp mesh volume (inflate 3) (roughen 1 4))
```

### Subdivide: adding resolution before deforming

`warp` moves the existing vertices. If the mesh is low-resolution (a box has only 8 vertices), the deformation does not have enough points to work on and the result looks faceted. The `:subdivide` option adds vertices before deforming:

```clojure
(warp (box 40) (sphere 25) (inflate 5) :subdivide 2)
```

Each subdivision step divides each triangle into 4 sub-triangles (split at the midpoints of the edges). Two steps produce 16 sub-triangles for each original triangle. The cost grows quickly, but for low-resolution primitives one or two steps make the difference.

The subdivision is limited to the triangles that fall inside the volume: the triangles outside stay unchanged. Note that `:subdivide` removes the `:face-groups` from the resulting mesh.

Alternatively, you can use `mesh-refine` (section 7.3) as a separate step before the warp. The difference is that `mesh-refine` subdivides the whole mesh, `:subdivide` only the affected zone.

### Custom deformations

The presets cover the common cases, but `warp` accepts any function with the right signature:

```clojure
(fn [pos local-pos dist normal vol] -> new-pos)
```

`pos` is the position of the vertex in world space `[x y z]`. `local-pos` is the normalized position in the volume, with each axis in `[-1, 1]`. `dist` is the normalized distance from the center of the volume (0 at the center, 1 at the edge). `normal` is the estimated normal of the vertex. `vol` is a map with the bounds of the volume.

You do not have to use all the parameters. A custom deformation that moves the vertices upward as a function of their distance from the center:


<!-- example-source: warp-custom
(register gaussian-bump
  (warp (mesh-refine (box 40 40 5) 5) (sphere 25)
    (fn [pos _ _ _ _]
      (let [[x y z] pos
            r (sqrt (+ (* x x) (* y y)))]
        [x y (+ z (* 3 (exp (- (* r r 0.01)))))]))))
-->

The hermite falloff (from `smooth-falloff`) is already applied by the system before calling your function: the vertices at the edge of the volume receive a reduced effect automatically. You do not have to handle the blend yourself.

### When to use warp vs other techniques

`warp` is the right choice when the effect you want does not express itself with a geometric profile. An organic bulge on a mechanical piece, an irregular surface that simulates natural material, a localized twist: these effects are described more easily as "move the vertices like this" than as "extrude this shape along that path".

For global, uniform deformations (twisting a whole piece, tapering), the shape-fns of chapter 6 are often more natural: the deformation is part of the shape's definition, not a post-hoc operation. `warp` is at its best when the deformation is *localized* to a specific region of an already-built piece.

## 7.7 Diagnosis and repair

We close with the tools for the cases where a mesh is not healthy. Let us say it right away: in real use these cases are rare. Primitives, extrusions, lofts, revolves, and the results of boolean operations produce correct meshes by construction; you can model for months without ever meeting a broken one. The exceptions almost always come through three doors: geometries juxtaposed without booleans (`concat-meshes`), meshes imported from outside, and extreme combinations of shape-fns. For those moments, Ridley has a complete diagnostic toolkit.

### Looking inside a mesh

The most direct way to understand a mesh is to ask Ridley to describe it for you. `mesh-diagnose` analyzes the topology of a mesh and returns a map with the key information:

```clojure
(def m (box 20))
(mesh-diagnose m)
;; => {:n-verts 8
;;     :n-faces 12
;;     :n-edges 18
;;     :edge-incidence-distribution {2 18}
;;     :open-edges 0
;;     :non-manifold-edges 0
;;     :degenerate-faces 0
;;     :euler-characteristic 2
;;     :is-watertight? true}
```

<!-- example-source: mesh-diagnose-box
(register m (box 20))
(println (mesh-diagnose m))
-->

The numbers tell the story of the mesh. A cube has 8 vertices, 12 (triangular) faces, 18 edges. The `:edge-incidence-distribution` says how many edges are shared by how many faces: `{2 18}` means all 18 edges are shared by exactly 2 faces. This is the healthy case: each edge has a face on one side and one on the other, no holes, no overlaps.

`:open-edges` counts the edges that belong to a single face: they are the borders of a hole. `:non-manifold-edges` counts the edges shared by three or more faces: they are T-junctions, double walls, geometry that cannot exist as a physical object. `:is-watertight?` is `true` when both are zero.

The Euler characteristic (`:euler-characteristic`) is a topological invariant: for a closed sphere it is 2, for a torus it is 0. If the number does not match what you expect, the mesh has some structural problem.

`mesh-diagnose` is pure ClojureScript: it does not need Manifold, it runs everywhere, and it is light enough to call freely during development.

### Manifold and non-manifold

The word "manifold" already appeared with the booleans (7.2); here we clarify it in full, because it determines what you can do with an object.

A mesh is *manifold* (or *watertight*) when it represents a closed solid: no holes, no edge shared by more than two faces, no degenerate triangle. It is the digital equivalent of a physical object you could fill with water without any leaking out.

The primitives (`box`, `sphere`, `cyl`, `cone`), the closed extrusions, the lofts, the revolves, and the results of boolean operations are all manifold. This is the normal case.

Not all meshes are. Distinct but spatially coincident vertices confuse the topology: the edge between two near-coincident vertices is counted as shared by the wrong number of faces. This happens above all when combining geometry without a boolean (`concat-meshes`, which juxtaposes meshes without resolving their boundaries), importing a poorly stitched external mesh, or as a residue of long chains of floating-point operations. `merge-vertices` (below) merges them and usually restores the mesh to manifold. When a mesh does not come out manifold, `mesh-diagnose` helps you understand where to look.

Two sources of uncertainty are worth mentioning. The first is the very nature of composable shape-fns (chapter 6): their expressiveness is their strength, but it also means it is impossible to guarantee in advance that every combination of shell, custom thickness-fn, and extreme parameters produces a manifold mesh. In practice you try it, verify with `mesh-diagnose`, and if needed step in with `merge-vertices` or `solidify`. The second is that Ridley is a complex system and residual bugs are always possible: if a construction that according to this manual should produce a manifold mesh does not, it is worth reporting the case.

The distinction matters because some operations require manifold input. `mesh-union`, `mesh-difference`, and `mesh-intersection` work only on watertight meshes: Manifold (the WASM library that performs these operations) rejects the others. If you try a boolean on a non-manifold mesh, you get an error.

The important point is that "non-manifold" does not mean "broken". A mesh that Manifold rejects can still render correctly in the viewport, export to STL, and most slicers (Bambu Studio, OrcaSlicer, PrusaSlicer, Cura) accept it without trouble: they automatically repair the small gaps and slice the result. That mesh simply cannot take part in certain operations.

To quickly check the status of a mesh there are the two tools already seen in 7.2:

```clojure
(manifold? m)     ;; => true / nil
(mesh-status m)   ;; => detailed status information
```

<!-- example-source: mesh-manifold-check
(register solid (box 20))
(println "manifold?" (manifold? solid))
(println "status:" (mesh-status solid))
-->

`manifold?` is the quick check: it returns `true` if the mesh is watertight, `nil` otherwise (not `false`, because under the hood Manifold WASM throws an exception that gets caught). `mesh-status` gives more detail. If a mesh does not pass and you do not understand why, `mesh-diagnose` is the triage tool: it tells you exactly how many open-edges and non-manifold-edges there are, and from there you can decide how to step in.

### Repairing a mesh

Shells with openings (`:lattice`, `:voronoi`, `:checkerboard`, or thickness-fns that go to zero) do **not** fall among these cases: the loft seals them itself, because where the thickness goes to zero the outer wall and the inner one collapse onto the same point, and the mesh comes out manifold already. What remains at risk are meshes assembled with `concat-meshes` or imported from outside.

3D booleans (`mesh-union`, `mesh-difference`, `mesh-intersection`) normally produce already-manifold output: Manifold handles the near-duplicates internally. When a boolean fails, the problem is usually in the **input**, not the output.

`merge-vertices` collapses the vertices that are less than an epsilon apart (by default `1e-6`), welding the joints that remained open and often restoring manifoldness:

<!-- example-source: mesh-merge-vertices :no-run
;; m is a mesh with coincident but distinct vertices
;; (assembled with concat-meshes, imported, …)
(def fixed (merge-vertices m))
(println (boolean (manifold? fixed)))
-->

It is not a universal guarantee, but it is the first attempt always worth making. If the mesh has more serious structural problems (real holes, overlapping geometry), different strategies are needed, judged case by case: intervening upstream in the construction, or rebuilding the geometry of the problem area.

`mesh-diagnose` remains the triage tool. If after `merge-vertices` the mesh is still non-manifold, look at `:open-edges` and `:non-manifold-edges` in the diagnosis: they tell you whether the problem is holes (open) or anomalous junctions (non-manifold), and from there you choose the right path.
