# 8. Assembly

<!-- level: intermediate -->

Up to here you have built single pieces: a mesh, an extrusion, a shape modeled with shape-fns. This chapter explains how to put them together.

Assembling in Ridley means positioning components in space using the turtle as a positioning tool. The principle is the same one you have used so far to move and build: you give commands to the turtle (`f`, `th`, `tv`, `rt`...), and it moves. The difference is that now the turtle drags along an already-built piece instead of building a new one.

The chapter introduces four ideas that combine with one another: markers as a system to discover and name the attachment points of an assembly, the creation-pose as a mechanism to decide *where* on a component the attachment point falls, `attach` as the positioning operation, and the skeleton as the supporting structure that holds everything together.

## 8.1 Markers and on-anchors

A marker (`mark`) is a flag you plant inside a `path` to say "this point matters to me, I will come back to it". At creation time it records the position and orientation of the turtle and gives it a name. When you assemble later, the markers are the points on which you attach the components.

The idiomatic way to distribute pieces over the markers of a skeleton is the `on-anchors` macro. Imagine a frame with four decorative studs at the corners:

<!-- example-source: on-anchors-quadro
(def W 100) (def D 60)
(def W2 (/ W 2)) (def D2 (/ D 2))
(def plate (extrude (rect W D) (f 3)))
(defn stud [] (sphere 4))
(def stud-skel
  (path
    (rt W2) (u D2)  (mark :stud-tr)
    (u (- D))       (mark :stud-br)
    (rt (- W))      (mark :stud-bl)
    (u D)           (mark :stud-tl)))
(register decorated-plate
  (mesh-union
    plate
    (on-anchors stud-skel
      "stud-" (stud))))
-->

The skeleton `stud-skel` walks along the four corners of the rectangle and plants a marker at each one. The form of `on-anchors` is a table of clauses: on the left a *pattern* that selects markers, on the right a *body* that is run on each selected marker. Here the pattern is the string `"stud-"`, which matches all the markers whose name starts with that prefix (all four, in this case); the body is `(stud)`, which builds a new sphere on the current marker.

A note on why `stud` is a function and not a `def`. Inside `on-anchors` each body is run with the turtle positioned on the marker; the primitives (`sphere`, `box`, `cyl`, ...) built *inside* the body are born directly in that pose. A mesh created *before*, instead, has its coordinates fixed at the construction point, and must be moved explicitly — something you will see in § 8.2 when you meet `attach` and the *creation-pose*.

### Multiple roles with multiple patterns

The strength of `on-anchors` shows when the markers have different roles and each role receives a different piece. A fence with thick corner posts and thin intermediate posts:

<!-- example-source: on-anchors-staccionata
(def row-skel
  (path
    (side-trip (tv 90) (mark :end-post-start))
    (f 20)
    (doseq [i (range 5)]
      (side-trip (tv 90) (mark (keyword (str "mid-post-" i))))
      (f 20))
    (side-trip (tv 90) (mark :end-post-finish))))
(defn end-post [] (attach (cyl 8 40) (cp-f -20)))
(defn mid-post [] (attach (cyl 4 30) (cp-f -15)))
(register fence
  (on-anchors row-skel
    "end-post-" :align (end-post)
    "mid-post-" :align (mid-post)))
-->

Two new things in this example. The first is the programmatic generation of names: inside `path`, a `doseq` iterates five times and plants a marker with a computed name (`:mid-post-0`, `:mid-post-1`, ..., `:mid-post-4`). When the number of markers depends on a parameter, naming them by hand is tedious — generating them programmatically is the way.

The second is the `:align` modifier. By default `on-anchors` positions the turtle on the marker but does not rotate the body to align it to the marker's frame: the body keeps the orientation it would have in starting coordinates. By adding `:align` after the pattern, the orientation aligns too — in the case of the fence, the posts are rotated to stand upright. The distinction matters when a marker has a significant heading; when it does not (as for the studs of the frame, where every corner is equivalent), it can be omitted.

The example shows another useful pattern: the markers are planted inside a `side-trip`, each preceded by `(tv 90)` to rotate the orientation upward. `side-trip` saves the turtle's pose, runs the block, and restores the state — so the `(tv 90)` changes only the marker's frame, not the one the turtle will carry to the next step (the `(f 20)` that advances horizontally). You will see `side-trip` in detail in § 8.5; here it is enough to know that it lets you give a marker a custom frame without disturbing the rest of the path.

The functions `end-post` and `mid-post` each build a cylinder in its canonical frame (the cylinder's axis along the heading), with the creation-pose shifted to one end with `cp-f`. It will be the `:align` of `on-anchors` that brings those cylinders upright on the markers, rotating their frame onto the one the path has prepared.

### The pattern types

So far we have used the string-prefix. `on-anchors` recognizes four pattern types:

- **String** (`"end-post-"`): matches the markers whose name starts with that prefix.
- **Regex** (`#"-l$"`): matches on the marker's name with `re-find`. Useful for patterns more sophisticated than a simple prefix, for example markers ending with a certain suffix.
- **Keyword** (`:base`): exact match on a single marker.
- **Set** (`#{:front-left :front-right}`): matches if the marker is one of those listed. Useful when some markers have special roles but do not share a common prefix.

The patterns are evaluated in the order they are written, and for each marker the first that matches wins (no fallthrough). If no marker matches a pattern, `on-anchors` emits a warning with the list of the available markers — useful for spotting typos in the patterns. If a marker does not match any pattern it is silently skipped: filtering only a subset is a legitimate use.

### Watch out for naming patterns

A prefix that is too short can capture markers you did not want. The string `"bracket"` captures both `:bracket-1` and `:bracket-spec` or `:end-bracket`. Use distinctive prefixes (`"bracket-"` with the trailing dash) or anchored regexes (`#"^bracket-"`) when there is a risk of collision.

The iteration order over the markers is the insertion order in the path. If your body produces pieces that depend on the order (for example numbered progressively), keep that in mind. If the markers represent a set of equivalent pieces, the order does not matter.

### The mark as a through-line

It is worth pausing on something that ties this chapter to the previous ones. A `mark` does not live only inside a path: the same marker travels the whole pipeline. Planted on a profile, it survives `path-to-shape` as a reference to a point of the shape (chapter 5.7). It becomes an anchor of the mesh when that shape is extruded or lofted, and stays valid through boolean operations too. It rides the deformations of the shape-fns, staying on its point as the profile morphs along the loft (chapter 6). And it can be recovered with `(slice-mesh mesh :on t)` at any height of the path (chapter 7.5).

The practical upshot is that a piece built from a marked profile arrives at assembly already carrying its attachment points: the same `mark` you used to draw the shape is the one you attach components to, with no need to re-declare it. And the anchors the mesh carries are used like the markers of a skeleton: `move-to` to attach a single component, `on-anchors` to distribute many, because both accept indifferently a path or a mesh with anchors.

## 8.2 Creation-pose and creation-pose shifting

Every mesh has an anchor point: the position and orientation of the turtle at creation time. It is the *creation-pose*. Think of it as the piece's handle: when you later write `(attach mesh ...)`, the turtle grabs `mesh` right there, and from there it moves it, rotates it, attaches it elsewhere.

The same holds in the other direction: if it is your mesh that is "still" and someone else attaches to it (with `attach` + `move-to`), the guest piece lands on the creation-pose of the host mesh. It is the same mechanism seen from the opposite end: the creation-pose is the contact point.

The problem is that the default contact point is not always the one you need. The primitives (`box`, `cyl`, `sphere`, `cone`) have the creation-pose at the center of the geometry. Extrusions and lofts have it at the start of the path. If you want to grab the piece at a different point — a corner, a face, the end of a pin — you have to shift the creation-pose.

### How it works

The `cp-*` family slides the geometry under the anchor (which stays still) so that a chosen point comes to coincide with it:

`(cp-f n)` re-anchors at the point `+n` along the heading. The geometry slides backward by `n`.

`(cp-u n)` re-anchors at the point `+n` along the up. The geometry slides downward by `n`.

`(cp-rt n)` re-anchors at the point `+n` along the right. The geometry slides leftward by `n`.

The effect shows at assembly time:

```clojure
;; Box grabbed by the bottom face: it lays out upward
(def box-up
  (attach (box 40 40 40) (cp-u -20)))

;; Same box grabbed by the top face: it lays out downward
(def box-top-anchor
  (attach (box 40 40 40) (cp-u 20)))
```

<!-- example-source: cp-shift-box
(register box-up (attach (box 40 40 40) (cp-u -20)))
(color 0x00ff00)
(register box-top-anchor (attach (box 40 40 40) (cp-u 20)))
-->

Registered at the same point, the two cubes lay out in a column, meeting at the origin: `box-up` is hung by its bottom face and grows upward, `box-top-anchor` is hung by its top face and falls downward. Same geometry, different grab point.

The same shift also changes where what you attach *to* the box lands: anything attached to `box-top-anchor` arrives on its top face. The mechanism is one only; what changes is the side of the relationship you look at it from.

### Rotating instead of translating

`cp-f`, `cp-u`, `cp-rt` shift the *position* of the geometry under the anchor. The twin family `cp-th`, `cp-tv`, `cp-tr` rotates its *orientation*: the geometry turns under the anchor (by `-angle` around up, right, or heading respectively) while the orientation of the creation-pose stays still in the world. `(cp-th a)` rotates around the up, `(cp-tv a)` around the right, `(cp-tr a)` around the heading.

The difference from rotating the piece with an ordinary `th`/`tv`/`tr` inside `attach` is exactly this: with `cp-*` the anchor does not move. It matters when the orientation of the anchor will be read downstream, typically by a `move-to` that adopts its heading and up. By rotating the geometry with `cp-tv` instead of with `tv`, a later `move-to` of this piece behaves as if the rotation were not there: the anchor still points where it pointed before.

<!-- example-source: cp-rotation-bracket -->
```clojure
(register bracket
  (attach (extrude (rect 8 3) (f 15)) (cp-tv 30)))
```

The bar tilts by 30° under the anchor, but the anchor keeps pointing along +X. As with the position family, the `cp-*` rotations chain in the original frame of the creation-pose, not in the already-rotated one.

### The creation-pose as rotation pivot

A rotation applied inside `attach` rotates the piece around the creation-pose. This is the main reason it is worth shifting the creation-pose: not only to position correctly, but to rotate around the right point.


<!-- example-source: cp-shift-rotation
(def H 60)
(register default (attach (box 20 20 60) (tv 30)))
(rt 50)
(color 0xffff00)
(register shifted
  (attach
    (attach (box 20 20 60) (cp-f -30))
    (tv 30)))
-->
The yellow box (shifted) rotates on one end, the blue one (default) rotates on its center.

If a rotation looks wrong (the piece rotates around an unexpected point, flies off into space), check the creation-pose first. Almost always the problem is there.

### When to use cp-* and when not to

If your assembly has three or four pieces with obvious attachment points (the center of a cylinder, the start of an extrusion), you probably do not need to shift the creation-pose. If you are building a parametric assembly where the components attach to a skeleton with `move-to :align`, the creation-pose shift is almost always necessary to make each piece land at the right point.

The alternative (leaving the creation-pose at the default and compensating with `(f ...)` or rotations at assembly time) works, but it scatters the geometry through the code. With the creation-pose shifted to the right point, the assembly call stays clean: `(attach component (move-to skel :at :mark :align))` and nothing else.

## 8.3 Attach and move-to

`attach` is the fundamental positioning operation. It takes an already-built piece and transforms it using turtle commands: it moves, rotates, scales along local axes. The result is a new mesh (or SDF) with the transformed geometry; the original stays unchanged.

### The basic form

The commands inside `attach` are the same as the turtle's — `f`, `th`, `tv`, `tr`, `rt`, `u`, `lt` — and they apply to the *handle* of the piece (its creation-pose, see § 8.2):

<!-- example-source: attach-basic
(register b (box 20))
;; Move forward and rotate
(register b2 (attach b (f 30) (th 45)))
;; Chain as many commands as you need
(register b3 (attach b (f 50) (tv 30) (rt 5) (f 20)))
-->

The order matters: `(f 10) (th 45)` is not equivalent to `(th 45) (f 10)`. The first advances and then rotates; the second rotates and then advances in a different direction.

### Attach!: the imperative form

`attach!` directly modifies a registered mesh:

```clojure
(register b (box 20))
(attach! :b (f 30) (th 45))    ;; modifies :b in place
```

The name is a keyword (`:b`, not `b`). The piece in the registry is replaced with the transformed version. It is handy in the REPL to move a piece without rewriting everything, but in a model's code it is generally better to use the functional form `attach`: it mutates global state, and makes re-running the file less deterministic.

### Move-to: the bridge between two meshes

`attach` on its own moves a piece in its local coordinate system. When you want to position a piece *relative to another* — on top of a host, on a marker of a skeleton, inside the slot of a component — you use `move-to` as the first command inside `attach`.

`move-to` accepts as its target a **mesh** or a **path**, and has four forms that combine two independent decisions: *which point* of the target to go to, and whether the attached mesh should also *rotate* to align to the target's frame.

#### Target = mesh

The forms available when the target is a registered mesh:

```clojure
;; Go to the creation-pose of the target. The turtle's frame
;; adopts the target's heading/up; the attached mesh is NOT rotated.
(attach piece (move-to :host))

;; Go to the centroid of the target. The turtle's frame stays unchanged.
(attach piece (move-to :host :center))

;; Like the first, but in addition the attached mesh is rotated
;; to align its frame to the target's.
(attach piece (move-to :host :align))
```

The example shows the three variants side by side. The hosts are vertical cylinders; the attached piece is always a disk that starts vertical.

<!-- example-source: move-to-pose
(color 0x0000ff)
(register host-A (attach (cyl 5 40) (rt -30) (tv 90)))
(color 0xff8800)
(register guest-A (attach (cyl 10 4) (move-to :host-A) (f 20)))

(color 0xffffff)
(register host-B (attach (cyl 5 40) (tv 90)))
(color 0xff8800)
(register guest-B (attach (cyl 10 4) (move-to :host-B :center) (f 20)))

(color 0x000000)
(register host-C (attach (cyl 5 40) (rt 30) (tv 90)))
(color 0xff8800)
(register guest-C (attach (cyl 10 4) (move-to :host-C :align) (f 20)))
-->

In case A (blue cylinder): `(move-to :host-A)` brings the turtle to the creation-pose of `host-A` (by default at the center of the mesh). The following `(f 20)` advances in the *turtle's frame*, which now coincides with that of `host-A` — so the disk moves along the cylinder's axis (vertically, toward +Z). But the disk itself was not rotated: its vertices are still oriented as when it was built.

In case B (white cylinder): `:center` brings the turtle to the centroid of the mesh but does *not* update its frame. The `(f 20)` advances in the turtle's starting direction (+X by default), so the disk moves sideways, not along the cylinder's axis.

In case C (black cylinder): `:align` does both — it moves the turtle to the host's creation-pose *and* rotates the attached mesh so that its frame matches the target's. Result: the disk rests orthogonally to the cylinder's axis.

#### Target = path

When the target is a path, there is an important difference: the path's markers are *absolute positions* relative to the path's frame, and they ignore where the turtle is at the moment of the `attach`. The form `(move-to path)` on its own makes no sense (a path as a whole has no pose) and is rejected; you always need `:at :marker` to choose a specific marker.

The forms available are two:

```clojure
;; Go to the :base marker of the path. The turtle's frame adopts
;; the marker's heading/up; the attached mesh is NOT rotated.
(attach piece (move-to skel :at :base))

;; As above, but in addition the attached mesh is rotated
;; to align its frame to the marker's.
(attach piece (move-to skel :at :base :align))
```

The example defines a skeleton that changes direction, and shows three ways to use it: direct attachment, attachment after moving the turtle, and attachment inside `with-path`.

<!-- example-source: move-to-path
(color 0xffff00)
(def axle-skel
  (path (mark :base) (th -45) (f 30) (mark :mid) (th 90) (f 30) (mark :top)))
(def disk (cyl 15 4))
(register axle-stack
  (mesh-union
    (attach disk (move-to axle-skel :at :base))
    (attach disk (move-to axle-skel :at :mid))
    (attach disk (move-to axle-skel :at :top))))

(u 50)
(color 0x000000)
(def cn (cone 1 5 8))
(register axle-stack-offset
  (mesh-union
    (attach cn (move-to axle-skel :at :base))
    (attach cn (move-to axle-skel :at :mid :align))
    (attach cn (move-to axle-skel :at :top))))

(color 0xffffff)
(def cylinder (cyl 8 10))
(register axle-stack-replayed
  (with-path axle-skel
    (mesh-union
      (attach cylinder (move-to :base))
      (attach cylinder (move-to :mid :align))
      (attach cylinder (move-to :top)))))
-->

The first block (`axle-stack`) is the basic pattern: three disks attached to the three markers of the skeleton. Without `:align`, the disks keep their construction orientation (the +X axis); the fact that `:mid` and `:top` have different headings does not rotate them.

The second block (`axle-stack-offset`) is preceded by `(u 50)` (the turtle rises by 50 units), but the cones still end up where the disks were, not 50 units higher. When the target of `move-to` is a path, the markers are absolute: the turtle's current pose does not count. Note also the `:align` on the cone at `:mid`: this cone is rotated to align to the marker's heading (diagonal because of the `(th -45)` in the path), while the other two stay aligned to +X.

The third block (`axle-stack-replayed`) uses `with-path`, which "replants" the path in the turtle's current pose at the moment of entry. Now the `(u 50)` counts: the cylinders appear 50 units higher. Inside `with-path` we have to cite the markers by name (`(move-to :base)` instead of `(move-to axle-skel :at :base)`): the fact that we are working on axle-skel we have already said with the `with-path`.

The same third block can also be written with `turtle :anchor`. Inside `with-path`, `(turtle :base ...)` opens a scope with the turtle positioned and oriented on the `:base` marker; the body of that scope can be anything — an `attach`, a bare primitive, a composition of meshes, even a `scale` or a `mesh-difference`. In the example below each marker hosts a different disk, and the `attach` is gone:

```clojure
(u 50)
(register axle-stack-replayed
  (with-path axle-skel
    (mesh-union
     (turtle :base (scale (cyl 15 4) 1 1 1.5))
     (turtle :mid  (cyl 15 4))
     (turtle :top  (scale (cyl 15 4) 0.5 1 1)))))
```

(`scale` here works on world axes: `1 1 1.5` lengthens the base disk along Z, `0.5 1 1` squashes it in X on the top. The distinction between world scale and local `stretch-*` we will revisit below.)

The two forms — `move-to :base` inside `attach`, or `turtle :base` as a scope — produce geometries positioned the same way. The practical difference: `(move-to :base)` positions the turtle *inside* the current `attach`; `(turtle :base ...)` opens a scope you can stay in for several operations, even outside `attach`.

A warning on the semantics: the rule "the path's markers are absolute positions" holds specifically for `move-to ... :at ...`. Other tools you will meet later — `with-path`, `on-anchors`, `pin-path` — treat the markers instead as *relative* to the turtle's current pose, so that a path can act as a reusable template. The distinction is deliberate: `move-to` is for point attachments to a specific skeleton, the others are for composing assemblies.

### How markers end up on a mesh

So far `move-to` with `:at` has always looked for the markers in a path, but a mesh can carry them too, and in most cases it already has them with no effort on your part.

A mesh built from a marked profile (`path-to-shape`, `stroke-shape`, `embroid`) inherits the profile's marks as anchors (chapter 5.7): this is the most common case. Those anchors also survive boolean operations: the result of `mesh-union`, `mesh-difference`, or `mesh-intersection` keeps the anchors of the first operand mesh, because the boolean leaves the geometry of the first operand in place and the anchors are world poses. So you can drill or merge a piece and keep attaching components to its anchors. Building inside a `with-path` scope likewise leaves the path's marks on the mesh that comes out of it.

There is one case none of these covers: a mesh that has no generating path at all, like an imported STL or a bare primitive. There `attach-path` is the explicit way to declare anchors on top of an already-built geometry.

<!-- example-source: attach-path-mesh
(register column (cyl 5 60))
(attach-path :column (path (mark :base) (f 30) (mark :top)))
(register cap (attach (sphere 8) (move-to :column :at :top)))
-->

The path is attached to the mesh's current creation-pose, then `mark` records its points relative to that frame, and from there the mesh has anchors usable like those of a skeleton.

### Play-path: replaying a path

If you have a computed path — maybe returned by a function — and you want to use it to position a piece inside `attach`, `play-path` replays it in the right context:

<!-- example-source: attach-play-path
(defn branch-path [l]
  (path (tv 90) (f (/ l 8)) (tv -90) (f l)))
(register Y (attach (sphere 3) (play-path (branch-path 30))))
-->

`play-path` solves a technical problem: the functions that return paths capture the global bindings of `f`/`th`/`tv`, not the ones redefined inside `attach`. Without `play-path` the path would be ignored.

### Stretch: scaling along local axes

Inside `attach` (and only there) `stretch-f`, `stretch-rt`, `stretch-u` are available to scale the piece along the turtle's local axes:

<!-- example-source: attach-stretch
(register b (box 20))

;; Double along the heading
(register b2 (attach b (stretch-f 2)))

;; Rotate the turtle, then scale along the new heading
(register b3 (attach b (th 90) (stretch-f 2)))
-->

`stretch-*` is the local complement of `scale` (which works on world axes). Outside `attach` they are not available. If you write `(scale ...)` inside an `attach`, Ridley reports the error and points you to `stretch-*`.

### Attach on SDFs

`attach` works identically on meshes and SDFs. SDFs are Ridley's alternative modeling system, covered in chapter 12. If you have not met them yet, you can skip this paragraph: for now it is enough to know that `attach` is polymorphic, and works the same way on both types.

The turtle commands are executed one by one and each command transforms the SDF node directly. The markers registered with `mark` inside an `attach` on an SDF survive through the SDF booleans and through the materialization (SDF → mesh).

## 8.4 Skeleton-driven assembly

Skeleton-driven assembly is the complete pattern for assembling multiple components along a supporting structure. It combines the three ideas seen so far: markers (8.1), creation-pose (8.2), and attach with move-to (8.3).

### The pattern

**1. Build a skeleton.** A `path` that walks along the structure of the assembly, placing markers at every relevant point:

```clojure
(def axle-skel
  (path
   (mark :base)
   (f 30) (mark :mid)
   (f 30) (mark :top)))
```

**2. Model each component independently.** Each piece is born in its local frame, with the creation-pose (the handle, § 8.2) at the point where it will attach to the skeleton. Often this requires a shift with `cp-*`: the default — center for primitives, start of the path for extrusions — rarely coincides with the desired attachment point.

**3. Assemble by attaching to the skeleton.** You have three ways: `attach` + `(move-to skel :at :mark)` for a point attachment (§ 8.3), `with-path` + `(move-to :mark)` (or `turtle :mark`) to attach several pieces inside a scope (§ 8.3), and `on-anchors` (§ 8.1) when you want to distribute pieces over many markers according to a pattern.

The skeleton is the assembly's source of truth. Move a marker, and every attached component follows it.

### Proportional markers

Instead of fixed distances in the path, express the positions as fractions of a reference length:

```clojure
(def H 60)

(def axle-skel
  (path
   (mark :base)
   (f (* 0.5 H)) (mark :mid)
   (f (* 0.5 H)) (mark :top)))
```

Now changing `H` rescales the whole assembly. Useful when the same design has to exist in several sizes, or when a single dimension drives many derived offsets.

### Sub-paths and side-trip

Complex skeletons are built by composing sub-paths with `follow` and `side-trip`:

```clojure
(defn arm [side depth mname]
  (path
   (side-trip
    (th (if (pos? side) 90 -90))
    (f (abs side))
    (u (- depth))
    (mark mname))))

(def hinge-skel
  (path
   (mark :pin-axis)
   (doseq [[i k] (map-indexed vector bracket-axials)]
     (follow (bracket-pair i k)))))
```

`side-trip` runs a detour and returns to the starting point. `follow` inserts a sub-path into the skeleton. The markers placed inside a sub-path are visible from the whole skeleton.

### Hierarchical assembly with with-path and turtle

`with-path` and `turtle` are the alternative to `attach` + `move-to` when the components are extrusions that follow segments of the skeleton. Instead of building the pieces elsewhere and then attaching them, you build them directly in the skeleton's scope:

<!-- example-source: skeleton-with-path
(def arm-sk (path (mark :shoulder) (f 15) (mark :elbow) (f 12) (mark :wrist)))
(register upper
  (with-path arm-sk
    (extrude (circle 1.5) (path-to :elbow))))
(register lower
  (with-path arm-sk
    (turtle :elbow
      (extrude (circle 1.2) (path-to :wrist)))))
-->

`turtle :elbow` opens a scope in which the turtle is positioned at the `:elbow` marker (position, heading, up); `path-to :wrist` extrudes from there to the next marker. The pieces are born directly in world coordinates, with no need for post-hoc snapping.

This approach is natural when the components are extrusions that follow segments of the skeleton. The `attach` + `move-to` pattern is more suited when the components are pieces already built independently.

### When NOT to use a skeleton

If the assembly is two pieces glued together, or if there is no "backbone" connecting the parts, a skeleton is overkill. A pair of nested `attach` is enough. Use the skeleton when the components share an axis, a chain, or a supporting structure, and when the relative positions matter more than the absolute ones.

## 8.5 State stack and branching

When you build an assembly, every turtle command modifies the global state: position, heading, up. If you want to place two components that start from the same point but go in different directions, you need a way to "save" the state, explore a branch, and come back.

### Side-trip

`side-trip` (§ 5.4) is the main mechanism: it runs its block and brings the turtle back to the starting point. In an assembly it becomes the branching tool:

<!-- example-source: side-trip-branching
(def skel
  (path
   (mark :root)
   (side-trip (th 30) (f 20) (mark :branch-a))
   (side-trip (th -30) (f 20) (mark :branch-b))
   (f 40) (mark :tip)))
(follow-path skel)
-->

After the first `side-trip`, the turtle is back at `:root`. The second `side-trip` starts from the same point. The final `(f 40)` advances from the original position, not from a branch.

The classic pattern is the Y-shaped skeleton: a spine that forks. Each branch is a `side-trip` that places markers, then the spine continues straight.

### Nested branching

`side-trip` nests:

```clojure
(def tree-skel
  (path
   (mark :trunk)
   (f 30)
   (side-trip
    (th 30)
    (f 15)
    (mark :branch-1)
    (side-trip
      (th 20) (f 10) (mark :twig-1a))
    (side-trip
      (th -20) (f 10) (mark :twig-1b)))
   (f 30)
   (mark :top)))
```

Each nesting level saves and restores its state independently. Useful for tree structures, kinship graphs, or any topology that is not a straight line.

### With-path as a scope

`with-path` is a scope that binds the turtle to a registered path. Inside, `goto` and `path-to` navigate among the markers. On exit, the turtle's state returns to what it was before entry:

```clojure
;; The turtle's state here is X
(with-path my-skeleton
  ;; Inside: the turtle starts from the beginning of the path
  (turtle :some-mark
    ;; ... build something ...
    ))
;; The turtle's state here is X again
```

This makes it an implicit branching mechanism: you enter the path, do what you need, exit, and you are where you were before. Combined with several `with-path` in sequence, you can navigate different skeletons without their influencing one another.

## 8.6 A complete example: a wall key holder

So far you have seen the chapter's ideas one at a time. This section puts them together in a single, genuinely printable object: a key holder that fixes to the wall with two wall plugs and hosts a variable number of interchangeable tag-shaped keychains.

The object is made of two separate pieces. The **dispenser** (`D`) is a spiral ribbon, square in section, along which N sockets are welded: each socket hosts a single keychain and has a C-shaped ring at its center that keeps the key from coming off. At the two ends of the ribbon there are the holes for the wall plugs. The **keychain** (`SK`) is the interchangeable piece: a pill-shaped tag, with a hole at one end for the key. It slides into the socket by sliding it under the ring.

Two paths organize the geometry. `KP` ("key path") describes the geometric structure shared between keychain and socket: in front of the initial pose the key's markers (the tag, the two circles at the ends); behind — reached with a `side-trip` that rotates 180° on the plane — the socket's markers (the point where the half-ring rises, the body, the two side arms that extend it). It is the same path that drives the construction of the object's two halves. `KHP` ("key holder path") describes the spiral ribbon that hosts the sockets, with a marker for each socket and two special markers at the ends for the wall plugs.

<!-- example-source: portachiavi
(def tolerance 0.4)
(def label-length 40)
(def label-depth 3)
(def label-h 25)
(def trace-w 5)
(def socket-h (+ label-h (/ trace-w 2)))

(def KP
  (path
    (mark :key-label)
    (side-trip (u (/ label-length 2)) (mark :key-circle))
    (side-trip (u (- (/ label-length 2))) (mark :key-ring))
    (side-trip (th 180) (u (* label-length 0.2)) (mark :socket-up)
               (u (- (/ label-length 2))) (mark :socket-body)
      (rt (- (/ socket-h 2) (/ trace-w 4))) (mark :socket-bar-1)
      (rt (- (- socket-h (/ trace-w 2)))) (mark :socket-bar-2))))

(defn socket-ring [key-path]
  (with-path key-path
    (attach
      (mesh-difference
        (extrude (circle (/ socket-h 2)) (f trace-w))
        (extrude (circle (/ (- socket-h trace-w) 2)) (f trace-w))
        (attach (extrude (rect (inc socket-h) label-h) (f (+ trace-w 2)))
                (u (/ label-h -2)) (f -1)))
      (move-to :socket-up :align))))

(defn single-key [key-path]
  (with-path key-path
    (mesh-difference
      (mesh-hull
        (attach (extrude (rect label-h label-length) (f label-depth)) (move-to :key-label))
        (attach (extrude (circle (/ label-h 2)) (f label-depth)) (move-to :key-circle))
        (attach (extrude (circle (/ label-h 2)) (f label-depth)) (move-to :key-ring)))
      (attach (extrude (circle (/ (- label-h trace-w) 2)) (f (+ label-depth 2)))
              (move-to :key-ring) (f -1)))))

(defn tolerance-ratio [n]
  (/ (+ n tolerance tolerance) n))

(defn socket [key-path]
  (mesh-difference
    (mesh-union
      (socket-ring key-path)
      (on-anchors key-path
        "socket-bar"  :align (attach (extrude (rect (/ trace-w 2) label-length) (f trace-w)))
        "socket-body" :align (mesh-difference
                               (attach (box socket-h label-length (* trace-w 2)))
                               (attach (box (- socket-h trace-w) (inc label-length) (inc (* trace-w 2)))))))
    (attach (single-key KP) (f tolerance)
      (stretch-f (tolerance-ratio label-depth))
      (stretch-rt (tolerance-ratio label-h)))))

(register SK (attach (single-key KP) (tv 90) (f 50)))

(def KHP
  (path
    (mark :start)
    (th 90)
    (dotimes [i 8]
      (arc-h (* (+ i 3) 20) 80) (mark (keyword (str "key-" i))))
    (th -90)
    (mark :end)))

(defn washer []
  (mesh-difference
    (cyl 10 trace-w)
    (cyl 1.5 trace-w)))

(register D
  (mesh-union
    (attach (extrude (rect trace-w trace-w) (play-path KHP)))
    (on-anchors KHP
      "key-"           (attach (socket KP) (tv -90) (f (/ trace-w 2)))
      #{:start :end}   (attach (washer) (tv -90)))))
-->

The example also uses `mesh-hull`, `mesh-difference`, `stretch-f`/`stretch-rt`, `play-path`: these are operators you will see later; here I ask you to take them on trust and concentrate on the role of the two paths and of `on-anchors`.

### KP as a reusable template

`KP` is never used directly to build anything: it is a map of positions that different functions interpret in different ways. `single-key` uses its `:key-label`, `:key-circle`, and `:key-ring` to shape the keychain. `socket-ring` uses its `:socket-up` to attach the C-ring. `socket` uses its `:socket-bar-1`, `:socket-bar-2`, and `:socket-body` to build the socket's body.

This is the reusable-components pattern: a path describes *where* the parts of an object are, distinct functions build *what* goes in each part on top of it. The functions take `key-path` as an argument, open it with `with-path`, and then cite the markers by name.

It is worth noting that `KP` and `KHP` use paths in two qualitatively different ways. `KP` is the path of a *single object*: the positions of its markers are in tight relationship with the geometry that will be built on top of them, and the functions `single-key`/`socket-ring`/`socket` know specifically which markers exist and what to do with them. Changing the structure of `KP` (one marker fewer, a different orientation) means modifying the functions too: path and code are *intertwined*, because the geometric relationships between the object's parts are expressed by both together.

`KHP` instead is the path of a *layout*: a sequence of independent stations on which objects that have no geometric relationships with one another are arranged. The route is determined entirely by the path, and the code that distributes sockets and washers over it does not need to know anything else about the markers except their name. Replacing `KHP` with another path — a straight line, a circle, a grid — would lay out the sockets differently without requiring any change to the rest.

The same distinction will come back shortly, seen from another angle, when we look at the two `on-anchors`.

Another property worth noting is that all the measurements are expressed as `def` at the top of the file (`label-length`, `label-h`, `trace-w`, `tolerance`...) and the path reuses them to compute the marker positions. The object is thus entirely parametric: change `label-length` and everything — path, socket, key, holes, tolerances — resizes accordingly. It is the payoff of the parametric pattern applied to the markers: you do not modify numbers in twenty different places, you modify one `def` at the top.

### The two on-anchors

Inside `socket`, `on-anchors` distributes *sub-pieces within a single component*: two side arms (pattern `"socket-bar"`) that extend the half-ring, and a hollow body (pattern `"socket-body"`, which matches the only marker with the prefix) from which the key's shape will later be carved. The markers are anatomical parts of the socket. All the clauses use `:align` because the arms and the body must follow the local orientation of the marker, not the construction orientation.

Inside `D`, `on-anchors` distributes *complete components along a supporting structure*: a socket for each `"key-"` marker (eight sockets along the spiral), a washer on the two special markers `:start` and `:end` (the holes for the wall plugs). The set pattern `#{:start :end}` groups two markers that have a similar role ("end of the ribbon") but do not share a prefix, and it is exactly the case where the set is the natural choice.

The same construct, two uses that seem far apart and that are instead the same thing: for each marker of a path, run a body in a scope positioned on the marker. It is the composability that makes the difference.
