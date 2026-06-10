# 10. Analyzing and measuring

Modeling is not only building: it is also understanding what you have built. How wide is this piece? Are the two holes at the right distance? Is the mesh watertight or does it have holes? Did this section come out wrong somewhere?

Ridley offers tools to answer these questions both from code (in the REPL or in the program) and interactively (with the mouse in the viewport). This chapter gathers them all.

## 10.1 Measuring distances and dimensions

### Distance

`distance` computes the Euclidean distance between two references. The references can be registered meshes (by name), faces of a mesh, or arbitrary points in space:

<!-- example-source: distance-forms :no-run
(register box1 (box 30))
(register box2 (attach (box 30) (f 80) (u 20)))

;; Between the centroids of two meshes
(println (distance :box1 :box2))

;; Between the centers of two faces
(println (distance :box1 :top :box2 :bottom))

;; Between two points
(println (distance [0 0 0] [100 0 0]))

;; Mixed: center of a face and a point
(println (distance :box1 :top [0 0 50]))
-->

The result is a number: the distance in Ridley units (millimeters, if you are designing for 3D printing).

A reference can also be a named anchor of a mesh, with the `:at` form: `(distance mesh :at :name ...)`. The anchors are the marks a shape carries with it after `path-to-shape` and extrusion (see §5.7), so you can measure between notable points of the geometry without recomputing their coordinates. `ruler` (§10.2) accepts the same forms.

### Bounds

`bounds` returns the bounding box of a registered mesh:

<!-- example-source: bounds-basic :no-run
(register my-piece (attach (box 40 30 20) (f 50)))
(println (bounds :my-piece))
;; => {:min [x y z] :max [x y z] :size [x y z] :center [x y z]}
-->

`:size` is the dimension of the bounding box along each axis: useful to check that a piece fits inside a print volume, or to compute margins. `:center` is the geometric center of the box.

### Area

`area` computes the area of a face:

<!-- example-source: area-basic :no-run
(register box1 (box 30))
(println (area :box1 :top))    ;; area of box1's top face
-->

### Perimeter of a shape and length of a path

To measure the *outline* of a 2D shape there are `shape-perimeter` and `shape-perimeters`; for the length of an open path there is `path-length`.

`shape-perimeter` returns the length of the outer (closed) outline only. It is the measure you need, for example, to size a profile to a target circumference, or to know how much material is needed to border a shape:

<!-- example-source: shape-perimeter-basic :no-run
(println (shape-perimeter (circle 50)))           ;; ≈ 314 (circumference)
(println (shape-perimeter (rect 40 60)))          ;; => 200
-->

For a shape with holes (a ring, a shape with cutouts), `shape-perimeter` measures *only* the outer outline. If you also need the length of the inner outlines, `shape-perimeters` returns a vector `[outer hole1 hole2 ...]`; the first element coincides with `shape-perimeter`, and for the total cutting length you just sum them:

<!-- example-source: shape-perimeters-basic :no-run
(def washer (shape-difference (circle 50) (circle 30)))
(println (shape-perimeters washer))               ;; [outer inner]
(println (reduce + (shape-perimeters washer)))     ;; total cutting length
-->

`path-length` measures the length of an open path. Unlike `shape-perimeter`, it does not close the loop — it measures the path from start to end — and works in 3D: if the path rises into space, the length reflects the real route, not its planar projection:

<!-- example-source: path-length-basic :no-run
(def p (path (f 30) (tv 90) (f 40)))
(println (path-length p))    ;; => 70
-->

A couple of things to know about precision. The measures refer to the *sampled* shape: a circle is actually an `n`-sided polygon, so `shape-perimeter` slightly underestimates it (it is the perimeter of the inscribed polygon, not of the ideal circle). The gap is small — about 0.16% at 32 segments, 0.04% at 64 — but it is there: if you need the exact circumference, raise the resolution of the circle or compute it analytically. Similarly, `path-length` on a path with bezier sections measures the polyline of the waypoints (the control polygon), so it underestimates the curves; for a closed curved profile, `shape-perimeter` is more accurate.

## 10.2 Interactive measurement

### Ruler from code

`ruler` has the same argument forms as `distance`, but adds a visual overlay in the viewport: a line with markers at the two ends and a floating label with the distance.

<!-- example-source: ruler-basic
(register box1 (box 30))
(register box2 (attach (box 30) (f 80) (u 20)))
(ruler :box1 :box2)                ;; between centroids
(ruler :box1 :top [0 0 50])        ;; from face to point
(ruler [0 0 0] [100 0 0])          ;; between points
-->

The rulers persist between REPL commands (you can add more than one and inspect them together), but they are automatically cleared at the next Cmd+Enter. `(clear-rulers)` removes them manually.

With the `:at` form the ruler latches onto a named anchor of the mesh instead of a fixed point: since the marks follow the mesh even after `attach` and `mesh-union`, it stays attached to the right point. In §11.3 this is used to measure the real bulge of a curve with a `:center` to `:apex` ruler mounted on the mesh.

### Interactive rulers with Shift+Click

To measure without writing code:

1. **Shift+Click** on a point of a mesh's surface: places a measurement marker.
2. **Shift+Click** on another point: creates a ruler between the two points.
3. **Esc**: clears the pending marker and all the rulers.

It is the fastest way to check a dimension while working.

### Live rulers in tweak mode

The rulers update in real time when you use `tweak` on a registered mesh:

<!-- example-source: ruler-tweak :no-run
(register A (box 30))
(register B (attach (box 30) (f 140) (u 50)))
(ruler :A :B)
(tweak :all :B)    ;; the rulers follow B while you drag the sliders
-->

When you confirm the tweak, the rulers use the final mesh. When you cancel, they go back to the original position.

## 10.3 Identifying faces

`find-faces` is the tool for selecting faces of a mesh based on their direction. You already met it in 7.4 to modify faces; here we see it as an analysis tool.

The directions are relative to the creation-pose of the mesh, not to the world:

<!-- example-source: find-faces-basic
(register my-piece (attach (box 40 30 20) (rt 20) (tv 15)))
(doseq [face (find-faces (get-mesh :my-piece) :top)]
  (flash-face (get-mesh :my-piece) (:id face)))
-->

`:threshold` controls the tolerance of the alignment (default 0.7, where 1.0 is perfect):

```clojure
(find-faces mesh :top :threshold 0.9)    ;; only very aligned faces
```

`:where` adds a predicate on the face's map:

```clojure
(find-faces mesh :top :where #(> (:area %) 100))    ;; only large faces
```

The point-selection functions (`face-at`, `face-nearest`, `largest-face`) are covered in 7.4. The point here is that `find-faces` is useful not only for *doing* something with the faces, but also for *understanding* the structure of a mesh after a series of boolean operations.

## 10.4 Mesh diagnostics

The mesh diagnostic tools (`mesh-diagnose`, `mesh-status`, `merge-vertices`) are covered in section 7.1. Here we add one clarification about `manifold?`.

`manifold?` returns `true` if the mesh is watertight, `nil` otherwise. The reason for the `nil` (and not `false`) is that under the hood Manifold WASM throws an exception when the object is not manifold, and Ridley catches it, returning `nil`. In practice it changes nothing (both are falsy), but it is worth knowing if you use the result in a `cond` or in an expression that distinguishes `false` from `nil`.

## 10.5 AI describe

Ridley includes an AI description system to make the geometry accessible to those who cannot see it. `describe` starts an interactive session: Ridley captures 7 views of the model from different angles, sends them together with the source code to an AI provider (Gemini, Claude, or GPT-4o), and returns a textual description of the geometry.

```clojure
(describe :gear)         ;; describe a specific piece
(describe)               ;; describe all the visible geometry

(ai-ask "How thick are the gear teeth?")    ;; follow-up question
(ai-ask "Would this print well without supports?")

(end-describe)           ;; close the session
```

The session stays open for follow-up questions until you close it with `end-describe`. `cancel-ai` interrupts a call in progress without closing the session. `ai-status` shows the current configuration of the AI provider.

The feature requires an AI provider with visual capabilities, configurable from the Settings panel. The quality of the answers depends on the model and the type of geometry: simple shapes are described well, complex assemblies can be approximate.

## 10.6 Viewing in XR

Ridley supports viewing models in virtual and augmented reality through WebXR. The buttons to enter VR and AR mode are in the viewport. The scene you see in the headset is the same one you see in the browser, with the same objects, colors, and positions.

Interaction in XR is today only visual: you can explore the model in space, but not modify it from the headset. Editing continues in the browser.

The complete documentation of XR mode is in the application's dedicated guide.
