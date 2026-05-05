# 2D profiles

> How to define and place 2D profiles in Ridley.

## Profiles aren't anchored to a plane

In CAD systems built around sketches (Fusion, SolidWorks, FreeCAD), a 2D profile is a persistent entity *anchored to a plane*. You pick a face or a datum, you sketch on it, the sketch lives there, and you go back to it later to edit.

In Ridley there is no "sketch entity". A profile is a *function* that returns a 2D shape. The shape itself has no spatial position: it lives in the abstract right/up plane of the turtle. It only acquires a position and an orientation in the world when something *consumes* it — `extrude`, `loft`, `revolve` — and the consumer reads the turtle's pose at that moment to anchor it.

Three consequences:

- **Same profile, two positions** — call the consumer twice with the turtle in different poses.
- **Same profile, two orientations** — the turtle's heading and up at consumption time decide which way the profile faces.
- **Parametric profile = function returning a shape** — pass arguments, get a different outline each time. No "edit sketch" dialog needed.

This shift is the main thing to internalise. The rest of the guide builds on it.

## Five ways to build a profile

The first four idioms below build a profile *from scratch*: you describe the outline as vertices, turtle moves, primitives, or an existing path. The fifth is different in nature — it *extracts* a profile from an existing mesh by slicing it. Pick the one that fits the data you have or the way you naturally describe the shape.

### `poly` — explicit vertices

Best when you already have the corner coordinates, either as constants or computed from data.

```clojure
(def gusset
  (poly [[0 0] [40 0] [0 30]]))
```

A right triangle with cathetuses 40 (along right) and 30 (along up). Three points, one shape.

`poly` accepts either a flat sequence of `x y` values or a vector of `[x y]` pairs.

### `shape` — drawn with turtle moves

Best when the outline is naturally described as "go forward 10, turn 90, go forward 5, …" — typically lengths and angles, not coordinates.

```clojure
(def L
  (shape (f 10) (th 90)
         (f 5)  (th 90)
         (f 5)  (th -90)
         (f 5)  (th 90)
         (f 5)))
```

The 2D turtle starts at origin facing +X. Only `f` (forward) and `th` (turn horizontal) are available; `shape` closes the outline implicitly. Particularly comfortable for shapes built in a loop.

### Built-in primitives — alone or composed

Best when the profile is a standard primitive or a composition of primitives.

```clojure
(circle 20)                         ; circle
(rect 30 15)                        ; rectangle
(polygon 6 12)                      ; hexagon
(star 5 20 10)                      ; five-pointed star

(fillet-shape (rect 40 20) 4)       ; rectangle with rounded corners
(shape-difference (circle 20)
                  (circle 14))      ; washer
```

A whole family of 2D operators (`fillet-shape`, `chamfer-shape`, `shape-offset`, `shape-union`, `shape-difference`, `shape-intersection`) compose with the primitives. See the Spec, *2D Shapes* and *2D Booleans*, for the full list.

### `path-to-shape` — convert an existing path

Best when a single path serves a double role: as a *skeleton* (placing accessory components via marks and anchors) and as a *profile* (defining the outline of a solid). Using the same path for both keeps a single source of truth for the geometry, and the two consumers can never drift out of sync.

```clojure
;; A free-form rim path with marks where decorative feet will attach.
(def rim
  (path
   (mark :foot-1) (f 30) (th 60)
   (mark :foot-2) (f 30) (th 60)
   (mark :foot-3) (f 30) (th 60)
   (mark :foot-4) (f 30) (th 60)
   (mark :foot-5) (f 30) (th 60)
   (mark :foot-6) (f 30)))

;; Same path, two consumers:
;; - as a profile (via path-to-shape) to build the plate solid;
;; - as a skeleton (via anchors) to place feet around its rim.
(def plate-solid
  (extrude (path-to-shape rim) (f 4)))

(def feet
  (concat-meshes
   (for [m (filter #(re-find #"^foot-" (name %))
                   (keys (anchors rim)))]
     (attach (cyl 3 6) (move-to rim :at m :align)))))

(register plate (mesh-union plate-solid feet))
```

> **Current limitation.** `path-to-shape` works only on paths confined to the right/up plane (`f` and `th` moves). If the path contains `tv` or `tr` (out-of-plane rotations), they are silently ignored — the result is the path *as if* those moves had no effect, which is rarely what you want. A proper 3D-to-2D projection is on the roadmap. For now, keep your source path planar when feeding it to `path-to-shape`.

### `slice-mesh` — extract a section from an existing mesh

Best when the profile you need is already implicit in another mesh — its cross-section at some height, the silhouette of one of its faces, the contour of an irregular surface at a chosen plane.

```clojure
;; A bowl built by revolution.
(register bowl
  (revolve (shape (f 20) (th -90) (f 30) (th -90) (f 15))))

;; Position the turtle at the slice plane (heading = plane normal),
;; then extract the cross-section.
(tv 90) (f 15)
(def bowl-rim (slice-mesh :bowl))
```

The result is a vector of 2D shapes (a mesh can have multiple disjoint contours at the same plane), in plane-local coordinates relative to the turtle's pose at slice time. Each contour can be fed back to `extrude`, `loft`, or any 2D operator.

A typical use is reverse-engineering a feature: you have a mesh, you want to *reuse* a section of it as the basis for a new part — say, an O-ring matching the rim of a container, or a reinforcing band that hugs an irregular profile.

## Positioning the profile

The same shape, consumed in different turtle poses, produces meshes in different places and orientations. The profile is unchanged; the turtle decides where the mesh comes out.

```clojure
(def L
  (shape (f 10) (th 90)
         (f 5)  (th 90)
         (f 5)  (th -90)
         (f 5)  (th 90)
         (f 5)))

;; Two L-prisms, side by side along right:
(register a (extrude L (f 8)))
(rt 30)
(register b (extrude L (f 8)))

;; A third one, tilted: turtle pitches up by 30° before extruding.
(rt 30) (tv 30)
(register c (extrude L (f 8)))
```

When the placement should follow another mesh's anchor instead of being driven by raw turtle moves, use `move-to` to snap the turtle into the right pose first:

```clojure
(extrude my-profile
         (move-to other-mesh :at :slot :align)
         (f 8))
```

This is the same idiom as in skeleton-driven assembly: the profile is the "what", the turtle pose is the "where", and the two stay decoupled.

## Inspecting profiles

Coming from sketch-based CAD, you may miss seeing the outline in the viewport before extruding it. Use `stamp` to render any shape as a flat outline at the turtle's current pose, without producing a solid:

```clojure
(def my-profile
  (shape (f 10) (th 90) (f 5) (th 90)
         (f 5) (th -90) (f 5) (th 90) (f 5)))

(stamp my-profile)        ; visible 2D outline at the turtle pose
```

`stamp` is a debug aid, not a builder — the result has no thickness. Inspect, fix the profile if needed, then feed it to `extrude`, `loft`, or `revolve`. Toggle stamps from the viewport toolbar or with `(show-stamps)` / `(hide-stamps)`.

## Example: the gusset in the hinge pilot

The pilot example (`assembly/01-hinge-c-profile.cljs`) uses a triangular gusset built from a three-vertex `poly`, then placed at every bracket mark on the hinge skeleton:

```clojure
(defn arm [w d mark-name]
  (extrude
   (poly [[0 0] [w 0] [0 d]])
   (mark mark-name)
   (f bracket-thickness)))
```

The same `arm` function produces gussets of any size (parametric in `w` and `d`), and at any pose along the hinge — the position is decided not by the function but by the turtle pose at the moment `arm` is called inside the skeleton path. Profile and placement are cleanly separated.

## Common pitfalls

- **Thinking of profiles as sketches that "live somewhere".** A profile in Ridley is a value, not a placed object. Storing it in a `def` does not give it a position; it gets one only when consumed.

- **Wrong winding order in `poly`.** `poly` preserves the caller's winding order — clockwise input produces inverted faces, visually similar but wrong for downstream booleans. Pass vertices counter-clockwise in the right/up plane. (`shape`, primitives, and `path-to-shape` auto-correct to CCW, so this caveat does not apply to them.)

- **Self-intersecting outlines.** A profile whose edges cross itself is invalid. Symptoms: holes in the extruded mesh, missing faces, or boolean operations producing surprises. Inspect the outline with `stamp` before extruding when in doubt.

- **Reaching for `poly` when a built-in would do.** A rounded rectangle is `(fillet-shape (rect …) r)`, not a hand-rolled `poly` of arc-approximating points. Use built-ins and 2D operators where they fit; `poly` and `shape` are for outlines that don't compose naturally from primitives.

## See also

- Spec, *2D Shapes* — full reference for `poly`, `shape`, primitives, 2D operators, `path-to-shape`, and `slice-mesh`.
- Spec, *Extrude* / *Loft* / *Revolve* — the consumers of profiles.
- `skeleton-driven-assembly.md` — same idiom of "decouple the what from the where".
- `marks-as-discovery.md` — the bracket-loop pattern used in the `path-to-shape` example.
- `assembly/01-hinge-c-profile.cljs` — full pilot example using `poly` for the gusset.