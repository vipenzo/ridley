# 11. Advanced curves

<!-- level: advanced -->

In the previous chapters you built paths with straight segments (`f`) and direction changes (`th`, `tv`). For many models that is enough, but not for all: a curved handle, an aerodynamic profile, a tube that follows a fluid trajectory require real curves. Ridley offers two families of curves: arcs (a circle of known radius and angle) and Beziers (free curves controlled by points).

## 11.1 Arcs

You have already used arcs in chapter 4: `arc-h` curves in the horizontal plane (around the turtle's up axis), `arc-v` in the vertical one (around the right axis); a positive angle goes in the standard direction of rotation, a negative one the opposite way. Under the hood an arc is a sequence of small `f` + `th` (or `tv`) steps: their number depends on `resolution` and can be overridden per call with `:steps`:

```clojure
(arc-h 10 90 :steps 32)    ;; 32 steps instead of the default
```

Two consecutive arcs join smoothly by themselves: the turtle leaves the first one with its heading tangent to the curve, and the second one starts from there. What chapter 4 did not say is that arcs are turtle commands in every respect, and this has a precious consequence for paths.

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

On **arrival**, by default the curve is tangent to the start→end chord: the turtle ends up facing the target, no longer its starting direction. The `:preserve-heading` flag instead makes the curve arrive tangent to the current heading, which therefore stays **unchanged** — a following movement welds on without a cusp.

```clojure
(bezier-to [20 30 0] :preserve-heading)   ;; arrives tangent to the current heading
(f 10)                                     ;; continues straight, no corner
```

This is the convenient form for closing a profile where one side is curved and the others straight: the curve joins the straight segments without you having to compute the control points. `:tension` (default `0.33`) sets how much the curve bows — higher means a wider belly. It is effectively a `bezier-to-anchor` toward an anchor with your own heading, but without having to define the anchor.

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

`edit-bezier` is a modal session like `tweak` and `pilot` (§ 15.3) and shares their limits: it opens once per evaluation and does not coexist with another interactive session already open. While the session is open the editor is read-only: confirming rewrites the source itself, and a hand-edit would break the replacement. Switching workspace closes the session.

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

`:tension` controls how far the control points are from the endpoints: low values produce tenser curves (close to a straight line), high values wider curves. `:tension-end` sets the arrival tension separately, for asymmetric curves.

There is also a path-first form that avoids `with-path`: by passing the path as the first argument, the marks are resolved on the fly. `(bezier-to-anchor ps :at :end :tension 0.5)` is equivalent to wrapping the call in `(with-path ps …)`, but more compact when the path is already at hand. It is the form we will use in §11.3.

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
(bezier-as zigzag :control true)             ;; vertices as control points
```

`:cubic true` uses Catmull-Rom tangents instead of the default linear tangents: the result is smoother but less predictable.

By default `bezier-as` interpolates the path's vertices: the curve passes through each vertex. With `:control true` it treats them as control points: the curve no longer passes through the vertices but through the midpoints of the segments, staying tangent there, and rounds the polygon instead of chasing it. It is the dual of interpolation, and it is handy when the polygon is your drawing tool and you want to control the bow by moving the vertices. We will see it at work in §11.3.

`bezier-as` works both in direct turtle mode and inside `path`.

## 11.3 The same profile, five ways

The functions in §11.2 look like alternatives, but they often solve the same problem from different angles. To make that concrete we take a single profile and build it in five ways, ordered along a spectrum: at one end the math does everything, at the other you draw it by eye and no math is left. Three of the five ways produce the identical curve by different routes; the other two show the edges of the family.

The profile is a rounded "L" corner: the curve goes from (0,0) to (a,a), starts tangent horizontal and arrives tangent vertical, and bulges toward the 45° diagonal. A single measure, D, says how much it bulges.

All the methods start from the same scaffold: the un-rounded "L", with two marks at the ends. From the marks, and from the headings at those points, come the endpoints and the tangent directions that every curve must respect.

```clojure
(def a 45)   ; side: the curve goes from (0,0) to (a,a)
(def D 51)   ; measure on the 45° diagonal
(def ps (path (mark :start) (f a) (th 90) (f a) (mark :end)))
```

At `:start` the heading is horizontal, at `:end` it is vertical (after the `th 90`).

To measure the real bulge we need a diagonal scaffold, independent of the curve: a `side-trip` (which adds no geometry, leaving only marks) places `:center` on the (0,a) corner and `:D`, the target at distance D on the 45° diagonal. The `th 90` after `:D` gives it the +45° heading, the curve's tangent at the apex: so `:D` is not just a position but an anchor with a direction, one to aim at with `edit-bezier`, as we will do at the end of Way 4 to draw the half.

```clojure
(def diag
  (path (mark :start)
        (side-trip (th 90) (f a) (th -135) (mark :center) (f D) (th 90) (mark :D))))
```

Then a helper `wall` to compare the curves on equal terms: it extrudes each one as a little wall and mounts a ruler beside it that measures the bulge on the diagonal.

```clojure
(defn wall
  ([curve] (wall curve 0))
  ([curve dx]
   (let [profile (add-mark (path (follow-path diag)
                                 (follow-path curve))
                           :apex 0.5)
         w (attach (extrude (stroke-shape profile 3) (f 10)) (rt dx))]
     (ruler w :at :center w :at :apex)
     w)))
```

`wall` prepends `diag` to the curve with `follow-path` (the side-trip travels with the curve without adding geometry), then `add-mark` marks the real apex at the curve's midpoint: `(add-mark … :apex 0.5)` puts `:apex` at half the length, the apex of the bulge. Finally it extrudes, places it at `dx`, and pulls the ruler from `:center` to `:apex`. The marks become anchors of the extruded mesh, and `ruler`'s `:at` form reads them back on the placed mesh: `(ruler w :at :center w :at :apex)` stays attached even after `attach` and `mesh-union`. It is the length that shrinks when a curve does not reach D.

### Way 1: the full math

The direct route: solve the cubic equation to find the handle length `p` that makes the curve pass exactly at distance D on the diagonal. It works, and it is exact, but you do the arithmetic.

```clojure
(def p (* (/ 4 3) (- (* (sqrt 2) D) a)))   ; ≈ 36.17
(def curve-1 (path (bezier-to [a a 0] [p 0 0] [a (- a p) 0])))
```

The two control points sit along the tangents, at distance `p` from the endpoints. All the effort is in that formula.

### Way 2: the math reduced to one number

`bezier-to-anchor` takes endpoints and tangents from the marks of `ps`, so only one parameter is left to decide: the tension. Here we derive it from `p`, but from then on it is the only number to touch.

```clojure
(def t (/ p (* a (sqrt 2))))   ; ≈ 0.568
(def curve-2 (path (bezier-to-anchor ps :at :end :tension t)))
```

The path-first form resolves the marks on the fly, without `with-path`. The curve is identical to Way 1's, but we described it with one parameter instead of two computed points.

### Way 3: no computation, by eye

`edit-bezier` removes even that last number: instead of computing the tension, you adjust it by sight until the curve touches the diagonal.

<!-- example-source: cinque-modi-edit :no-run
(def a 45)
(def D 51)
(def ps (path (mark :start) (f a) (th 90) (f a) (mark :end)))
(def diag
  (path (mark :start)
        (side-trip (th 90) (f a) (th -135) (mark :center) (f D) (th 90) (mark :D))))
(defn wall
  ([curve] (wall curve 0))
  ([curve dx]
   (let [profile (add-mark (path (follow-path diag)
                                 (follow-path curve))
                           :apex 0.5)
         w (attach (extrude (stroke-shape profile 3) (f 10)) (rt dx))]
     (ruler w :at :center w :at :apex)
     w)))
(register corner (wall (path (edit-bezier ps :at :end :symmetric))))
-->

You launch it from the definitions panel (Cmd+Enter): it opens the editor with a tension slider, which you drag or nudge with the arrows, and the `:center` to `:apex` ruler mounted by `wall` climbs toward D=51 as the curve touches the diagonal; Enter confirms. On confirm the call rewrites itself into exactly Way 2's form, `(bezier-to-anchor ps :at :end :tension …)`. So Way 3 is not one more curve: it is the interactive way to obtain Way 2 without arithmetic. With `:symmetric` there is a single slider; without it, two, start and end.

### Way 4: symmetric by construction

The corner is symmetric about the diagonal, and we can exploit that: you draw only the half, from O to the midpoint M, and you mirror it.

```clojure
(def half (path (bezier-to [36.06 8.94 0] [18.09 0 0] [29.34 2.21 0])))
(def curve-4
  (path (follow-path half)
        (follow-path (reverse-path (mirror-path half)))))
```

The four numbers of `half` are not computed: they are what `edit-bezier` writes to source after you draw the half by eye, exactly as in Way 3. We show them literal because they are the artifact of the drawing, not a formula to derive. Deriving them from Way 1's cubic would make Way 4 lose its identity: a symmetric construction that depends on none of the others.

`mirror-path` reflects the half on the plane of symmetry, `reverse-path` flips it so it chains with the first. There is no need to declare the axis: the path ends facing the true tangent, and `mirror-path` uses that heading as the plane's normal, that is the turtle's right/up plane at that point. The curve comes out symmetric on its own. `follow-path`, inside a `(path …)`, records rather than merely moving the turtle, and that is what lets the two halves be stitched into a single path.

And just as Way 3 produces the whole curve by eye, the half of Way 4 is authored the same way with `edit-bezier`: only, instead of aiming at `:end` you aim at `:D`, the apex. This is where a reachable `:D` is needed, and why `diag` gave it a heading: with fixed endpoints and tangents only one number is left, the tension. As you adjust it, the preview recompiles everything, half plus mirror, and the `:center` to `:apex` ruler climbs toward D=51; Enter rewrites it into `(bezier-to-anchor diag :at :D :tension …)`.

<!-- example-source: cinque-modi-bis-edit :no-run
(def a 45)
(def D 51)
(def diag
  (path (mark :start)
        (side-trip (th 90) (f a) (th -135) (mark :center) (f D) (th 90) (mark :D))))
(defn wall
  ([curve] (wall curve 0))
  ([curve dx]
   (let [profile (add-mark (path (follow-path diag)
                                 (follow-path curve))
                           :apex 0.5)
         w (attach (extrude (stroke-shape profile 3) (f 10)) (rt dx))]
     (ruler w :at :center w :at :apex)
     w)))
(def half (path (edit-bezier diag :at :D :symmetric)))
(def curve-4
  (path (follow-path half)
        (follow-path (reverse-path (mirror-path half)))))
(register corner (wall curve-4))
-->

So the interactive appears twice in the spectrum, once per strategy: Way 3 draws the whole curve aiming at `:end`, and this variant draws its half aiming at the apex. The same tool serves both the direct construction and the symmetric one.

### Way 5: the control polygon

The other four ways (with 3 collapsing onto 2) give the identical curve. The fifth is a cousin: same tangents at the ends, but a slightly different bow, because it comes from a control polygon instead of a cubic tuned on D.

`bezier-as :control` treats the path's vertices as control points: the curve passes through the midpoints of the segments, tangent there, and rounds the polygon.

```clojure
(def curve-5
  (let [x 24                     ; the only free parameter: the legs, coupled
        y (* (- a x) (sqrt 2))   ; 45° bevel derived: closes on (a,a)
        poly (path (f x) (th 45) (f y) (th 45) (f x))]
    (path (bezier-as poly :control true))))
```

The polygon is symmetric with a single free parameter, `x`, the length of the two legs; the 45° bevel `y` is derived so the curve closes exactly on (a,a). Changing `x` changes the bow while keeping symmetry and endpoints: you can put it on a slider with `tweak` and watch the `:center` to `:apex` ruler (mounted by `wall`) drop below D as you bulge less.

### The comparison

We place the four walls side by side, 80mm apart, each with its ruler:

<!-- example-source: cinque-modi-confronto
(def a 45)   ; side: the curve goes from (0,0) to (a,a)
(def D 51)   ; measure on the 45° diagonal
(def ps (path (mark :start) (f a) (th 90) (f a) (mark :end)))

;; diagonal scaffold: :center on the corner, :D target + apex heading
(def diag
  (path (mark :start)
        (side-trip (th 90) (f a) (th -135) (mark :center) (f D) (th 90) (mark :D))))

;; wall + :center → :apex ruler (the real bulge)
(defn wall
  ([curve] (wall curve 0))
  ([curve dx]
   (let [profile (add-mark (path (follow-path diag)
                                 (follow-path curve))
                           :apex 0.5)
         w (attach (extrude (stroke-shape profile 3) (f 10)) (rt dx))]
     (ruler w :at :center w :at :apex)
     w)))

;; 1. cubic solved by hand
(def p (* (/ 4 3) (- (* (sqrt 2) D) a)))
(def curve-1 (path (bezier-to [a a 0] [p 0 0] [a (- a p) 0])))

;; 2. a single tension (path-first, no with-path)
(def t (/ p (* a (sqrt 2))))
(def curve-2 (path (bezier-to-anchor ps :at :end :tension t)))

;; 4. drawn half + mirror
(def half (path (bezier-to [36.06 8.94 0] [18.09 0 0] [29.34 2.21 0])))
(def curve-4
  (path (follow-path half)
        (follow-path (reverse-path (mirror-path half)))))

;; 5. control polygon
(def curve-5
  (let [x 24
        y (* (- a x) (sqrt 2))
        poly (path (f x) (th 45) (f y) (th 45) (f x))]
    (path (bezier-as poly :control true))))

(register confronto
  (mesh-union
    [(wall curve-1 0)
     (wall curve-2 80)
     (wall curve-4 160)
     (wall curve-5 240)]))
-->

In the viewport the count reads on three levels, and the rulers confirm it with a number. Five methods, but four walls: Way 3 is not a wall of its own, it is the interactive route to Way 2. Four walls, but two distinct curves: Ways 1, 2 and 4 are pixel-identical and their rulers read D=51, while Way 5's control polygon bulges a little less and its ruler drops to about 48.8.

The whole spectrum is here: from Way 1, where the math does everything, to Way 3, where none is left, through intermediate degrees. The resulting curve is the same; only how much work you delegate to computation and how much to the eye changes.

## 11.4 A limit of loft: when the mesh is not manifold

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
