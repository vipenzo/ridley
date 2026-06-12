# 12. Working with SDFs

<!-- level: intermediate -->

Up to here you have built everything with meshes: vertices, faces, triangles. SDFs (Signed Distance Fields) are Ridley's alternative modeling system. Where meshes describe the surface of an object as a network of triangles, an SDF describes an object as a mathematical function that, given a point in space, says how far it is from the surface. Points outside the object have a positive distance, points inside a negative one, points on the surface have a distance of zero.

The practical difference is that some operations that with meshes are impossible or fragile become natural with SDFs: soft blends between volumes, uniform hollow shells, infinite repetitive patterns, sculptural deformations. The price is that an SDF stays abstract until you materialize it into a mesh to see it, export it, or print it.

## 12.1 Primitives and operators

### SDF primitives

```clojure
(sdf-sphere r)                    ;; sphere of radius r
(sdf-box size)                    ;; cube of side size
(sdf-box sx sy sz)                ;; box with dimensions sx × sy × sz
(sdf-rounded-box sx sy sz r)      ;; box with rounded edges of radius r
(sdf-cyl r h)                     ;; cylinder of radius r and height h, axis along heading
(sdf-cone r1 r2 h)                ;; cone/truncated cone, axis along heading
(sdf-torus R r)                   ;; torus: R major radius, r section radius
```

SDF primitives are pure data: building `(sdf-sphere 10)` computes nothing, it produces a tree of operations. The computation happens only when the mesh is materialized.

The example lays out all the SDF primitives in a row, and above each (where it exists) the corresponding mesh primitive. You can see the direct SDF↔mesh correspondence — and the two primitives that live only in the SDF world, `sdf-rounded-box` and `sdf-torus`, which stay without a companion above:

<!-- example-source: sdf-primitives-gallery
;; SDF row (at the bottom)
(register s-sphere    (attach (sdf-sphere 18)))
(register s-cube      (attach (sdf-box 30) (rt 50)))
(register s-box       (attach (sdf-box 20 30 36) (rt 100)))
(register s-rbox      (attach (sdf-rounded-box 30 30 30 6) (rt 150)))
(register s-cyl       (attach (sdf-cyl 15 36) (rt 200)))
(register s-cone      (attach (sdf-cone 4 16 36) (rt 250)))
(register s-torus     (attach (sdf-torus 16 6) (rt 300)))

;; Mesh row (at the top, +70 in up), aligned column by column
(register m-sphere (attach (sphere 18) (u 70)))
(register m-cube   (attach (box 30) (u 70) (rt 50)))
(register m-box    (attach (box 20 30 36) (u 70) (rt 100)))
;; (rt 150): no rounded-box mesh, empty column
(register m-cyl    (attach (cyl 15 36) (u 70) (rt 200)))
(register m-cone   (attach (cone 4 16 36) (u 70) (rt 250)))
;; (rt 300): no torus mesh, empty column
-->

The columns of `sdf-rounded-box` and `sdf-torus` have no mesh above: they are shapes that Ridley produces directly as SDFs and that in the mesh world would require more elaborate constructions.

Like mesh primitives, SDF primitives are born at the turtle's current pose. So:

<!-- example-source: sdf-pose-aware
(register original (sdf-sphere 10))   ;; sphere at the origin
(f 30)
(th 60)
(register turned (sdf-box 4 8 16))    ;; box at (30 0 0), rotated 60° around up
-->

This holds for `sdf-sphere`, `sdf-box`, `sdf-rounded-box`, `sdf-cyl`, `sdf-cone`, `sdf-torus`, and `sdf-formula`. The infinite structures (`sdf-gyroid`, `sdf-schwarz-p`, `sdf-diamond`, `sdf-slats`, `sdf-bars`, `sdf-bar-cage`, `sdf-grid`) stay aligned to the world axes: typically you clip them with `sdf-intersection` against a positioned volume.

A note on the arguments of `sdf-box`: the order is `(sdf-box x y z)`, which corresponds to `(sdf-box right up forward)`. The mesh primitive `box` uses the same order: `(box right up forward)`. The two forms produce the same geometry in the same pose, and for clarity you can think of the arguments as `(sdf-box r u f)` to keep the symmetry with `(box r u f)`.

### SDF booleans

```clojure
(sdf-union a b)           ;; union
(sdf-difference a b)      ;; subtraction: remove b from a
(sdf-intersection a b)    ;; intersection: keep only the common volume
```

Same semantics as the mesh booleans, but performed on the distance function, not on the triangles.

### Transformations

SDF transformations use the same polymorphic functions as meshes:

```clojure
(translate node dx dy dz)
(rotate node :z 45)
(rotate node [1 0 1] 30)
(scale node 2)
(scale node 2 1 0.5)
```

`attach` works identically: turtle commands, move-to, cp-*, everything as in chapter 8.

### Sculptural operations

These operations exploit the implicit representation and have no direct equivalents in the mesh world:

`(sdf-blend a b k)` merges two SDFs with a soft blend. `k` controls the blend radius: the larger it is, the wider the transition zone. It is the operation that makes SDFs interesting for organic shapes.

<!-- example-source: sdf-blend-intro
(register blob
  (sdf-blend
    (sdf-sphere 12)
    (attach (sdf-box 10 10 10) (f 12))
    4))
-->

`(sdf-blend-difference a b k)` subtracts b from a with a soft concavity, the dual of `sdf-blend`.

<!-- example-source: sdf-blend-difference-intro
(register scooped
  (sdf-blend-difference
    (sdf-rounded-box 30 30 20 3)
    (attach (sdf-sphere 15) (u 10))
    3))
-->

`(sdf-shell a thickness)` hollows out a solid, leaving a uniform shell of thickness `thickness`.

<!-- example-source: sdf-shell-intro
(register hollow
  (sdf-clip
    (attach (sdf-shell (sdf-sphere 15) 1) (f -11))))
-->

`(sdf-offset a amount)` expands (positive) or contracts (negative) the surface. We place an offset sphere and an original one side by side to see the difference:

<!-- example-source: sdf-offset-intro
(register grown (sdf-offset (sdf-sphere 10) 3))
(register original (attach (sdf-sphere 10) (rt 40)))
-->

A note: `sdf-offset` produces a field that is no longer a true SDF far from the surface. For rounded boxes prefer `sdf-rounded-box`; for shell operations the result might not combine correctly with `sdf-intersection` of other SDFs.

`(sdf-morph a b t)` interpolates between two shapes. `t` goes from 0 (= a) to 1 (= b).

<!-- example-source: sdf-morph-intro
(register half-way
  (sdf-morph (sdf-box 16 32 8) (sdf-sphere 11) 0.5))
-->

`(sdf-displace node formula)` deforms the surface with a spatial formula:

<!-- example-source: sdf-displace-intro
(register wavy
  (sdf-displace (sdf-sphere 10)
    '(* 1.5 (sin (* x 2)) (sin (* y 2)))))
-->

The formula is a quoted Clojure expression that uses `x`, `y`, `z` as spatial variables. Positive values push the surface inward, negative ones outward.

### Half-space and clip

`sdf-half-space` is a half-space defined by the turtle's pose. The cutting plane passes through the turtle's position with a normal equal to the heading. By default it keeps the half *behind* the heading (the part the turtle came from):

<!-- example-source: sdf-half-space-intro
;; Cut a cylinder in half
(tv 90)
(register half-cyl
  (sdf-intersection (attach (sdf-cyl 10 20) (tv 30)) (sdf-half-space)))
-->

`sdf-clip` is the shortcut for the common case:

<!-- example-source: sdf-clip-intro
(tv 90)
(register clipped
  (sdf-clip
    (sdf-blend
      (sdf-sphere 12)
      (attach (sdf-box 10 10 10) (f 12))
      4)))    ;; equivalent to (sdf-intersection ... (sdf-half-space))
-->

To keep the half in front of the turtle:

```clojure
(sdf-intersection shape (sdf-half-space :cut-ahead))
```

### Infinite patterns

SDFs can describe infinite structures, to be clipped later with an intersection:

<!-- example-source: sdf-grid-lattice
;; Lattice grid bounded by a sphere
(register ball-lattice
  (sdf-intersection (sdf-sphere 20) (sdf-grid 8 1.5)))
-->

<!-- example-source: sdf-bar-cage-intro
;; Cage of bars
(register cage
  (sdf-intersection
    (sdf-rounded-box 60 60 90 6)
    (sdf-bar-cage 60 60 90 5 1.5)))
-->

A third construct is the parallel slits, which are subtracted from a container:

<!-- example-source: sdf-slats-intro
;; Parallel slits carved into a box (period 8, thickness 2, perpendicular to X)
(register slotted
  (sdf-difference (sdf-rounded-box 50 30 30 4) (sdf-slats :x 8 2)))
-->

`sdf-slats`, `sdf-bars`, `sdf-bar-cage`, `sdf-grid` produce infinite structures; `sdf-difference` and `sdf-intersection` confine them to the region you care about.

A warning: the versions with blend (`(sdf-grid period thickness blend-k)`) use libfive's exponential blend, which does not produce a valid SDF. The gradient can invert at the junctions, causing flipped normals if combined with `sdf-intersection` or `sdf-difference`. For printable pieces, prefer the sharp-edged version (without `blend-k`).

### Custom formulas

<!-- level: advanced -->

`sdf-formula` compiles a quoted Clojure expression into an SDF tree. The variables `x`, `y`, `z` represent the spatial coordinates. Also available are `r` (distance from the origin), `rho` (cylindrical radius), `theta` (azimuthal angle), `phi` (polar angle):

<!-- example-source: sdf-formula-gyroid
;; Approximate gyroid
(register gyroid
  (sdf-intersection
    (sdf-sphere 20)
    (sdf-formula
      '(+ (* (sin x) (cos y))
          (* (sin y) (cos z))
          (* (sin z) (cos x))))))
-->

The operations available in the formula are: arithmetic (`+`, `-`, `*`, `/`), trigonometric (`sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`), mathematical (`sqrt`, `abs`, `exp`, `log`, `pow`, `mod`, `square`, `neg`), and comparison (`min`, `max`).

A more solid example of how `sdf-formula` lets you build primitives from scratch: it is exactly the principle by which a primitive like `sdf-cone` is built. A truncated cone with radius `r1` at the base (z = -h/2), `r2` at the top (z = +h/2), and height `h` has this distance field:

<!-- example-source: sdf-formula-cone
;; Definition of sdf-cone using sdf-formula
(defn my-cone [r1 r2 h]
  (let [half-h (/ h 2)
        slope  (/ (- r2 r1) h)
        max-r  (max r1 r2)]
    (-> (sdf-formula
          (list 'max
                ;; rho - r(z), where r(z) interpolates linearly from r1 to r2
                (list '- 'rho
                      (list '+ r1 (list '* slope (list '+ 'z half-h))))
                ;; |z| - h/2: clip between the two bases
                (list '- (list 'abs 'z) half-h)))
        ;; auto-bounds: :x-range is the radial extent, :y-range is the height.
        ;; The +1 on the y-range widens the meshing region beyond the planes
        ;; of the disks: without that margin libfive does not close the bases.
        (assoc :x-range [(- max-r) max-r]
               :y-range [(- (+ half-h 1)) (+ half-h 1)]))))

(register cone-from-formula (my-cone 16 4 36))
-->

The idea is that the cone is the intersection of two regions: the "inclined disk" `rho ≤ r(z)` and the slab `|z| ≤ h/2`. The maximum of the two distance functions gives the SDF of the intersection. It is not a perfect Euclidean SDF (the true distance outside the surface would be smaller), but the iso-zero contour coincides with the cone's surface — enough for booleans and meshing.

The final `assoc` serves the automatic meshing. `sdf-formula` does not know what your expression represents, so if you do not tell it where the solid lives `auto-bounds` falls back to a cube `[-10 10]` on each axis — with the result that our cone of radius 16 and height 36 would be clipped. `:x-range` is read as the radial extent (and applied both to X and Y, with a bit of padding); `:y-range` as the extent along Z. For the cone the numbers come straight from the parameters: the maximum radius of the two disks (`max r1 r2`) and half the height.

The `+1` added to the `y-range` is not cosmetic: the iso-zero surface of the two bases lies exactly at `z = ±h/2`, and if the meshing region ends there libfive does not see "outside" voxels beyond the cap, so it does not close it. By leaving a bit of margin (one unit is enough) the marching cubes finds the sign change and produces a closed mesh. It is a consequence of the "max of half-spaces" scheme — if you build a primitive with flat surfaces tangent to the bounding box, remember to leave a bit of air on the affected sides.

The "max of half-spaces" scheme generalizes: every convex solid can be described as the max of many functions `f_i(x,y,z) - 0` where `f_i` is positive outside and negative inside the i-th face. The more sophisticated primitives (`sdf-rounded-box`, the TPMS) use richer formulas, but the principle is the same.

A pitfall: `pow` with a negative base returns NaN (libfive computes `pow(a,b)` as `exp(b * log(a))`). To square an expression that can be negative, use `(* expr expr)` instead of `(pow expr 2)`.

## 12.2 Blend as a sculptural operation

Blend is the main reason SDFs exist in Ridley. `sdf-blend` merges two volumes with a soft transition that is not a geometric blend computed after the fact (like `fillet` on meshes) but a property of the distance function itself.

<!-- example-source: blend-levels
(def a (sdf-sphere 12))
(def b (attach (sdf-box 10 10 10) (f 12)))

(register k1 (sdf-blend a b 1))                  ;; tight blend
(register k3 (attach (sdf-blend a b 3) (rt 50))) ;; medium blend
(register k6 (attach (sdf-blend a b 6) (rt 100)));; wide blend
-->

The parameter `k` has no intuitive unit: it is a coefficient of the smooth-min function. In practice, start from 2-3 and adjust by eye. Values below 1 produce an almost invisible blend, values above 10 merge everything into a blob.

`sdf-blend-difference` is the dual: it subtracts with a soft concavity. It is the operation for carving organic hollows:

<!-- example-source: blend-difference-scoop
(register scooped
  (sdf-blend-difference
    (sdf-rounded-box 30 30 20 3)
    (attach (sdf-cone 2 10 30) (u 10) (tv 90))
    3))
-->

## 12.3 Resolution and auto-meshing

An SDF is a tree of operations, not a mesh. The mesh is generated only when needed: at the moment of the `register`, when you combine an SDF with a mesh in a boolean, or at export. This process is called materialization.

### Global resolution

`sdf-resolution!` sets the global resolution of SDF meshing:

```clojure
(sdf-resolution! 15)     ;; default: fast draft
(sdf-resolution! 40)     ;; good quality
(sdf-resolution! 80)     ;; high quality, slow
```

The number is in "turtle units" (like `resolution :n` for curves). Internally it is converted into voxels per unit, taking into account the object's bounds. The total voxel count is limited by a cap to avoid too-costly meshing.

### Auto-boost for thin features

When the SDF tree contains small `sdf-shell` or `sdf-offset`, the resolution is automatically increased to guarantee at least 3 voxels across the thinnest feature. You do not have to do anything: the system detects the minimum thickness and adapts.

### Smoothness of curves

For curved primitives (`sdf-cyl`, `sdf-cone`, `sdf-torus`) the visual smoothness does not depend on `sdf-resolution!` but on the current **turtle resolution** at the moment of construction — exactly as for the mesh primitives (`cyl`, `cone`, `sphere`). The default `:n 64` gives ~64 voxels around the most curved perimeter; just raise it to smooth:

<!-- example-source: sdf-curve-resolution
;; Smooth torus only inside the scope, default elsewhere.
;; Change :n and re-run to see the effect on smoothness.
(turtle (resolution :n 256)
  (register hi-ring (sdf-torus 16 6)))
-->

The `(turtle …)` scope lets you target the high resolution on a single primitive, leaving the rest of the model at the default. It is the same semantics as meshes, so a single `resolution` controls the whole scene consistently.

### Explicit control

For full control over bounds and resolution, use `sdf->mesh` directly:

```clojure
(sdf->mesh node)                                          ;; auto bounds and resolution
(sdf->mesh node [[-12 12] [-12 12] [-12 12]] 30)         ;; explicit bounds and resolution
```

The bounds are three intervals `[min max]`, one per axis. The resolution is in voxels per unit.

`sdf-ensure-mesh` is the conditional form: if the input is already a mesh, it returns it unchanged. If it is an SDF node, it materializes it. Useful in polymorphic code:

```clojure
(sdf-ensure-mesh x)                    ;; auto everything
(sdf-ensure-mesh sdf ref-mesh)         ;; extends the bounds to cover ref-mesh
(sdf-ensure-mesh sdf 30)               ;; resolution override
(sdf-ensure-mesh sdf ref-mesh 30)      ;; both
```

The form with `ref-mesh` serves a specific problem: **infinite or procedural SDFs** (gyroid, half-space, formulas without declared ranges) do not tell `auto-bounds` how big they are, and the system falls back to a default cube of about `[-10 10]` per axis. As long as you look at them on their own it is not a problem, but when you use them as a *cutter* in a boolean against a larger mesh, the cutter is materialized only in that central little cube: the rest of the host mesh stays intact, and the result is geometrically wrong (while remaining manifold).

But before reaching for `ref-mesh`, there is almost always a better path: **keep the boolean in SDF and materialize only the final result**. If the container can be an SDF, do not go through an intermediate mesh. `sdf-ensure-mesh` is useful exactly when an SDF branch has to coexist with mesh geometry in the same expression — here a loft (which is already a mesh) and a box carved by a gyroid (SDF) joined together:

<!-- example-source: sdf-ensure-mesh-union :warning slow
(def container (sdf-box 20 20 20))   ;; SDF, not a mesh box
(def pattern   (sdf-gyroid 8 1))

(register carved
  (mesh-union
    (loft (tapered (twisted (rect 30 2))) (f 50))
    (sdf-ensure-mesh (sdf-difference container pattern))))
-->

`sdf-ensure-mesh` converts the SDF branch to a mesh so that `mesh-union` can join it to the loft. The boolean `(sdf-difference container pattern)` stays in SDF up to that point: `auto-bounds` sizes it starting from the box (which knows its dimensions), and libfive evaluates the difference directly during meshing, triangulating only the portions of gyroid inside the box — there never exists a standalone gyroid-mesh of millions of faces. It is the idiomatic pattern: keep the work in SDF as long as possible, and convert to mesh only where you have to meet mesh geometry.

`ref-mesh` remains the way when the host *must* be a mesh — a loft, an imported mesh, a mesh with anchors to preserve — and you want to use an infinite SDF cutter on it. In that case the cutter has to be materialized for the boolean, and `ref-mesh` gives it the right bounds:

```clojure
;; Mesh host, infinite SDF cutter: ref-mesh sizes the cutter to its extent
(mesh-difference some-loft (sdf-ensure-mesh pattern some-loft))
```

The final bounds are the union of the SDF's auto-estimated ones and the mesh's (enlarged by 30%, so the surface is not cut at the edge). The `ref-mesh` only serves to size the region: it does not enter the geometry of the result. Watch the cost: a dense cutter (a fine-pitch gyroid) on large bounds produces a great many triangles and the meshing becomes slow. To contain it, widen the pattern's period, lower the resolution with `sdf-resolution!`, or restrict the `ref-mesh` to only the sub-volume to be carved.

When the SDF is "finite" (spheres, boxes, tori, cylinders, cones, and their compositions) none of this is needed: `auto-bounds` sizes it on its own. And if you control a formula's definition, the alternative is to declare `:x-range`/`:y-range` on the node (as for the cone in 12.1).

## 12.4 Sdf-offset: offset as a first-class operation

`sdf-offset` expands or contracts the surface of an SDF by a uniform amount. It is conceptually simple (you add or subtract a value to the distance function), but it has practical implications worth a note.

```clojure
;; Expand a sphere by 2mm
(sdf-offset (sdf-sphere 10) 2)

;; Contract a box by 1mm (rounds the edges)
(sdf-offset (sdf-box 20 20 20) -1)
```

The offset is one of the four ways to offset in Ridley (together with `shape-offset` for 2D shapes, shell as a shape-fn on lofts, and `woven-shell` for revolution lofts). The thematic guide "Parallel surfaces" compares them.

## 12.5 The SDF-then-mesh pattern

The typical workflow with SDFs is: build the SDF tree, then materialize it into a mesh at the moment you combine it with mesh operations — booleans with other pieces, fillet, export. The materialization is implicit: it happens when an SDF node ends up inside a mesh operation or a `register`.

<!-- example-source: sdf-then-mesh
;; 1. Build in SDF (as a function, so it is born at the current pose)
(defn organic-base []
  (sdf-blend
    (sdf-sphere 15)
    (sdf-cyl 8 30)
    3))

;; 2. Position the turtle and materialize (automatic at register)
(f 30) (tv 30)
(register final
  (mesh-difference
    (organic-base)
    (attach (cyl 4 100))))
-->

The SDF → mesh passage is irreversible: once materialized, the object is a mesh and the SDF operations are no longer available. There is no inverse `mesh->sdf` function. This means it is advisable to do all the "organic" work (blend, shell, offset, pattern) in SDF, and then move to mesh for the "mechanical" operations (holes, attachments, assembly).

`sdf-node?` distinguishes the two types:

```clojure
(sdf-node? (sdf-sphere 10))    ;; => true
(sdf-node? (box 20))           ;; => false
```

## 12.6 When SDF, when mesh

There is no "better" system: meshes and SDFs do different things well.

Use SDFs when you want soft blends between volumes (blend), uniform hollow shells (shell), repetitive patterns (grid, slats, bars), sculptural deformations (displace), or shapes defined by mathematical equations (formula). Blend in particular has no mesh equivalent: `mesh-smooth` rounds the edges, but does not merge two volumes with a soft transition.

Use meshes when you work with precise shapes (extruded profiles, lofts, revolves), when you need faces accessible via id/name, when you assemble components with skeletons and markers, or when speed matters (meshes are faster for most operations).

The most common pattern is the one in section 12.5: build the organic part in SDF, materialize, and complete in mesh. The two systems coexist in the same project without conflicts.
