# 11. Advanced curves

In the previous chapters you built paths with straight segments (`f`) and direction changes (`th`, `tv`). For many models that is enough, but not for all: a curved handle, an aerodynamic profile, a tube that follows a fluid trajectory require real curves. Ridley offers two families of curves: arcs (a circle of known radius and angle) and Beziers (free curves controlled by points).

## 11.1 Arcs

An arc is a segment of a circle. The turtle moves along the arc, progressively turning by a given angle with a given radius.

```clojure
(arc-h 10 90)       ;; horizontal arc: radius 10, 90°
(arc-h 10 -90)      ;; opposite direction
(arc-v 10 90)       ;; vertical arc
```

`arc-h` turns around the turtle's up axis (an arc in the horizontal plane). `arc-v` turns around the right axis (an arc in the vertical plane). Positive angle = standard rotation direction, negative = opposite.

An arc is in fact a sequence of small `f` + `th` (or `tv`), where the number of steps depends on `resolution`. You can override it per call:

```clojure
(arc-h 10 90 :steps 32)    ;; 32 steps instead of the default
```

Arcs chain with one another and with straight segments. The S-curve is the classic pattern:

<!-- example-source: arc-s-tube
(register s-tube
  (extrude (circle 3)
    (arc-h 15 90)
    (arc-h 15 -90)))
-->

The junction between the two arcs is smooth because the turtle exits the first arc with the heading tangent to the curve, and the second arc starts from there.

### Arcs inside path and extrude

Arcs work everywhere turtle commands work: inside `path`, inside `extrude`, inside `loft`, inside `attach`. There is no syntactic difference: they are turtle commands like `f` and `th`.

<!-- example-source: arc-in-extrude
;; Curved path with markers
(def curved-skel
  (path
    (mark :x-start)
    (f 20)
    (arc-h 20 45)
    (mark :x-bend)
    (arc-h 20 45)
    (f 30)
    (mark :x-end)))
;; Extrusion along the path
(register pipe (extrude (circle 4) curved-skel))
;; Decorations distributed over the markers
(register decoration
  (on-anchors curved-skel
    "x" :align (cyl 10 2)))
-->

The marker `:x-bend` is placed in the middle of the curve, between the two arcs: markers can be anywhere along a path, even inside a curve. And `on-anchors` (§ 8.1) works on a curved path exactly as on one made of straight segments.

## 11.2 Bezier curves

Beziers are more flexible curves than arcs: instead of radius and angle, you define them with a destination point and (optionally) one or two control points that determine their curvature.

### Bezier-to: a curve toward a point

The simplest form is `bezier-to` with just the destination point:

```clojure
(bezier-to [30 0 20])    ;; curve from the current position to [30 0 20]
```

Without explicit control points, Ridley automatically generates the control points so that the curve starts tangent to the turtle's current heading. The result is a smooth curve that "exits" the direction you are looking in and arrives at the target point.

With one control point (a quadratic Bezier):

```clojure
(bezier-to [30 0 20] [15 0 30])    ;; the curve passes near [15 0 30]
```

With two control points (a cubic Bezier):

```clojure
(bezier-to [30 0 20] [10 0 25] [25 0 25])
```

The number of steps of the curve follows `resolution`, or you specify it:

```clojure
(bezier-to [30 0 20] :steps 32)
```

### World or local coordinates

By default the destination point and the control points are world coordinates: `[30 0 20]` is that point in space, wherever the turtle happens to be. With the `:local` flag the same coordinates are read in the turtle's local frame, that is in the `[right up heading]` triple with origin at the current position:

```clojure
(bezier-to [0 0 40] [10 0 15] [10 0 25] :local)
```

Here `[0 0 40]` means "40 units forward along the heading", not a fixed point in space. The difference matters when you want the same curve to work wherever you place the turtle: a `bezier-to` in world coordinates is pinned to those numbers, a `:local` one follows the pose. It is also the form that `edit-bezier` produces, and it is what lets it rewrite a curve that is independent of its start position.

### edit-bezier: drawing the curve instead of computing it

The control points of a cubic Bezier are powerful but not very intuitive: to make the curve follow the profile of an object you would have to compute them by hand, and the only exact way is to solve the cubic equation. `edit-bezier` flips the problem: instead of computing the control points, you draw them.

It is used in place of `bezier-to`, anywhere that goes, and it is launched from the definitions code (Cmd+Enter), not from the REPL:

<!-- example-source: edit-bezier-draw :no-run
(register handle
  (extrude (circle 3) (edit-bezier)))
-->

When you evaluate, `edit-bezier` draws a valid default curve, so that `extrude` and `register` still run, and opens a modal session. The start point is the turtle pose at that point in the code: it is not editable and is never written to source, it is recomputed on each evaluation. The other three points, the end point and the two control points, are movable: you see them in the viewport together with the control polygon and the preview curve, which updates as you move them. Tab cycles through the three points, the arrows move the selected one (with the third axis reachable from the keyboard), Enter confirms and Esc cancels. The precise keys and the options are in the Reference.

On confirm the `(edit-bezier)` call is rewritten into the corresponding `bezier-to`, in local coordinates:

```clojure
(bezier-to [12 0 38] [4 0 14] [9 0 30] :local)
```

This is where `:local` becomes useful: the points are expressed in the frame of the start pose, so the curve stays correct even if you move the code that precedes it, and reopening it is an exact round-trip. To re-edit a curve you have already produced, pass its explicit points to `edit-bezier`.

There is also a `:shape` mode, meant for drawing a 2D profile to feed to `stroke-shape`: the curve is planar and the preview is aligned to the cross-section that will be extruded. It is the variant suited precisely to the case we started from, making an edge follow the profile of an object.

<!-- example-source: edit-bezier-profile :no-run
(register wall
  (extrude (stroke-shape (path (edit-bezier :shape)) 3) (f 20)))
-->

`edit-bezier` is a modal session like `tweak` and `pilot` (§ 15.3) and shares their limits: it opens once per evaluation and does not coexist with another interactive session already open.

### Bezier-to-anchor: a curve toward a marker

`bezier-to-anchor` builds a cubic Bezier that connects the turtle's current position to a named marker. The control points are generated automatically using the start and end headings, guaranteeing tangential continuity (the curve exits tangent to your heading and arrives tangent to the marker's heading):

<!-- example-source: bezier-to-anchor-demo
(def skel (path (mark :A) (f 30) (th 90) (f 20) (mark :B)))
(color 0xff0000)
(follow-path skel)
(color 0xffffff)
(reset)
(with-path skel
  (bezier-to-anchor :B :tension 0.5))
(color 0x0000ff)
(reset)
(u -30) (th 45) (f 20)
(register curve (extrude (circle 2) (with-path skel (bezier-to-anchor :B :tension 0.5))))
-->

`bezier-to-anchor` is to be used inside `with-path`: it is the `with-path` that gives meaning to the reference `:B`, resolving it on the current path. The curve starts from the turtle's position at the moment of the call and arrives at `:B`. In the example: first we trace it in white (after `(reset)`, so it starts from the origin where `:A` is) overlaid on the angular skeleton in red, then we reuse it in blue as the spine of an extrusion, with the turtle repositioned elsewhere to show that the curve builds from the current pose.

`:tension` controls how far the control points are from the endpoints: low values produce tenser curves (close to a straight line), high values wider curves.

### Bezier-as: smoothing an existing path

Sometimes you already have a path made of straight segments and discrete curves, and you want to turn it into a smooth curve. `bezier-as` takes a path and approximates it with a sequence of cubic Beziers, one for each segment of the original path, with C1 continuity at the junctions:

<!-- example-source: bezier-as-smooth
;; Zig-zag path
(def zigzag (path (f 20) (th 45) (f 15) (th -90) (f 15) (th 45) (f 20)))

;; Smoothed version
(register smooth-tube
  (extrude (circle 3) (bezier-as zigzag)))
-->

The options:

```clojure
(bezier-as zigzag :tension 0.5)              ;; control points farther apart
(bezier-as zigzag :steps 32)                 ;; resolution per segment
(bezier-as zigzag :cubic true)               ;; Catmull-Rom tangents
(bezier-as zigzag :max-segment-length 20)    ;; subdivide long segments first
```

`:cubic true` uses Catmull-Rom tangents instead of the default linear tangents: the result is smoother but less predictable.

`bezier-as` works both in direct turtle mode and inside `path`.

## 11.3 A limit of loft: when the mesh is not manifold

`loft` builds the mesh by sweeping a shape along the path, but it does not check that the successive sections do not interpenetrate. On certain geometries this produces a self-intersecting mesh: topologically closed, but which `manifold?` rejects and which is not printable.

The risk directions are three, often combined:

- **shapes with holes** (rings, hollow profiles): the inner outline turns tighter than the outer one and self-intersects sooner;
- **tight angles** in the path: the tighter the curve, the more the sections overlap on the concave side;
- **bezier paths**: the smoothing distributes the curvature, but the local minimum radius can turn out tighter than it seems — which is why `bezier-as`, which usually *improves* a loft, on a shape with holes can worsen it.

The three things add up: a thin ring on an already modest angle is enough to make the loft fail.

<!-- example-source: holed-shape-on-curve
;; Ring on a contained angle: manifold
(register pipe-good
  (loft (shape-difference (circle 8) (circle 7)) identity
        (path (f 30) (th 90) (f 20))))

;; Same ring, a slightly tighter angle: it self-intersects
(register pipe-bad
  (attach (loft (shape-difference (circle 8) (circle 7)) identity
                (path (f 30) (th 100) (f 20)))
          (f 60)))

(println "Pipe good:" (if (manifold? (get-mesh :pipe-good)) "Good" "Bad"))
(println "Pipe bad:"  (if (manifold? (get-mesh :pipe-bad))  "Good" "Bad"))
-->

There are no automatic checks nor a knob that solves the case: the tool is to verify with `manifold?` and, where it fails, to look for a bypass. For an annular profile along a curve, two reliable bypasses:

- **Build the tube by difference**: extrude the full profile (`circle 8`) along the path, extrude the hole (`circle 7`) along the *same* path, and subtract the second from the first with `mesh-difference`. Slower, but each extrusion works on a full shape, much more tolerant.
- **Keep the path straight and curve with `attach`**, applying the rotations to the finished piece instead of during the extrusion.

The practical rule remains: if `manifold?` returns `nil`, the remedy is upstream, on the path or on the construction strategy.
