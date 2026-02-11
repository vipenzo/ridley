
# Ridley — Examples

Working examples demonstrating Ridley's features.

---

## Basic Turtle Drawing (2D)

```clojure
;; Simple square
(f 50)
(th 90)
(f 50)
(th 90)
(f 50)
(th 90)
(f 50)

;; Triangle
(f 60)
(th 120)
(f 60)
(th 120)
(f 60)

;; Zigzag pattern
(f 20) (th 45) (f 20) (th -90) (f 20) (th 45) (f 20)
```

---

## 3D Primitives

```clojure
;; Box at origin
(box 30)

;; Rectangular box
(box 40 20 10)

;; Sphere
(sphere 25)

;; Cylinder
(cyl 15 40)

;; Cone
(cone 20 5 30)
```

### Positioned Primitives

```clojure
;; Move then place
(f 50)
(box 20)

;; Multiple primitives
(box 20)
(f 40)
(sphere 15)
(f 40)
(cyl 10 30)
```

---

## Generative Operations

### Extrude

```clojure
;; Extrude a path along Z
(def my-shape
  (shape
    (f 30)
    (th 90)
    (f 20)
    (th 90)
    (f 30)))

(extrude-z my-shape 15)
```

### Revolve

```clojure
;; Revolve a profile to create a vase
(def vase-profile
  (path
    (f 20)
    (th 90)
    (f 5)
    (th -45)
    (f 15)
    (th 45)
    (f 10)))

(revolve vase-profile [0 1 0] 360 32)
```

---

## Shape Extrusion (Phase 3 - New API)

The new shape-based modeling uses `extrude` to stamp and extrude shapes.

### Core Concept

Shapes are 2D profiles. Use `extrude` with a shape and movements:

```clojure
;; Shapes are data - they don't modify the turtle
(circle 15)              ; Returns a circular shape
(rect 40 20)             ; Returns a rectangular shape
(polygon [[0 0] ...])    ; Returns a polygon shape

;; Stamp and extrude in one call
(extrude (circle 15) (f 30)) ; Stamp circle, extrude 30 units
```

**Key insight**: The shape is stamped on the plane perpendicular to the turtle's
heading. Movements inside `extrude` create the 3D geometry.

### Basic Cylinder

```clojure
;; Turtle starts at origin, facing +X
;; Stamp circle (on YZ plane), extrude along +X
(extrude (circle 15) (f 30))
```

### Box via Rectangle

```clojure
(extrude (rect 40 20) (f 25))  ; Rectangle extruded 25 along X
```

### Custom Polygon Extrusion

```clojure
(extrude (polygon [[-10 -10] [10 -10] [15 0] [10 10] [-10 10] [-15 0]])
         (f 20))
```

### Positioned Extrusion

Move the turtle first, then extrude:

```clojure
(pen :off)                     ; Don't draw while moving
(f 50)                         ; Move to position
(tv 90)                        ; Point up (+Z)
(extrude (circle 10) (f 30))   ; Extrude cylinder along Z
```

### Diagonal Extrusion

```clojure
(tv 45)                        ; Point 45° up from X axis
(extrude (circle 10) (f 30))   ; Diagonal cylinder
```

### Multiple Shapes

```clojure
;; First cylinder at origin
(extrude (circle 10) (f 20))

;; Move and create second cylinder
(pen :off)
(th 90)                        ; Turn left
(f 50)                         ; Move
(extrude (circle 10) (f 20))
```

### Sweep (Multiple Movements)

Multiple movements inside `extrude` create a unified mesh:

```clojure
(extrude (circle 8)
         (f 20)                ; First segment
         (th 45)               ; Turn
         (f 20))               ; Second segment - joins seamlessly
```

All movements within a single `extrude` produce one unified mesh with proper connectivity.

### Loft (Shape Transformation)

`loft` extrudes a shape while applying a transformation function.
Works like `extrude` - uses turtle movements to define the path:

```clojure
;; Basic cone (scale down to 0)
(loft (circle 20) #(scale %1 (- 1 %2)) (f 30))

;; Cone that tapers to half size
(loft (circle 20) #(scale %1 (- 1 (* 0.5 %2))) (f 30))

;; Twist: rectangle rotating 90° during extrusion
(loft (rect 30 10) #(rotate-shape %1 (* %2 90)) (f 40))

;; Twist + scale (combined transforms)
(loft (rect 20 20)
      #(-> %1
           (scale (- 1 (* 0.5 %2)))      ; scale from 100% to 50%
           (rotate-shape (* %2 180)))    ; rotate from 0° to 180°
      (f 30))

;; Scale down to point + twist
(loft (rect 20 20)
      #(-> %1 (scale (- 1 %2)) (rotate-shape (* %2 90)))
      (f 40))

;; Witch hat: cone with a bend
(loft (circle 20) #(scale %1 (- 1 %2)) (f 15) (th 30) (f 15))

;; Star morphing to circle
(loft (star 5 20 8)
      #(morph %1 (circle 15 10) %2)  ; both need same point count
      (f 30))

;; With custom step count (smoother)
(loft-n 32 (circle 20) #(scale %1 (- 1 %2)) (f 30))
```

The transform function receives:
- `shape` - the current 2D shape
- `t` - progress from 0.0 to 1.0 (based on cumulative distance traveled)

Step count:
- Default: 16 steps (use `loft`)
- Custom: use `loft-n` with step count as first argument

Available shape transforms:
- `(scale shape factor)` - uniform scale
- `(scale shape fx fy)` - non-uniform scale
- `(rotate-shape shape angle)` - rotate (degrees)
- `(translate shape dx dy)` - translate shape
- `(morph shape-a shape-b t)` - interpolate between shapes
- `(resample shape n)` - resample to n points

### Pen Modes

`pen` controls line drawing mode (separate from extrusion):

```clojure
(pen :off)               ; Stop drawing lines
(pen :on)                ; Draw lines (default turtle mode)
(f 30)                   ; This draws a line when pen is :on
```

### Joint Modes

`joint-mode` controls how corners are rendered during extrusion:

```clojure
;; :flat (default) - direct connection, sharp corners
(joint-mode :flat)
(extrude (circle 5) (f 30) (th 90) (f 30))

;; :round - smooth arc at corners
(joint-mode :round)
(extrude (circle 5) (f 30) (th 90) (f 30))

;; :tapered - beveled corners with scaling
(joint-mode :tapered)
(extrude (circle 5) (f 30) (th 90) (f 30))
```

Comparison of joint modes side by side:

```clojure
;; Flat joint (default)
(reset [0 0 0])
(joint-mode :flat)
(extrude (circle 5) (f 30) (th 90) (f 30))

;; Round joint
(reset [40 0 0])
(joint-mode :round)
(extrude (circle 5) (f 30) (th 90) (f 30))

;; Tapered joint
(reset [80 0 0])
(joint-mode :tapered)
(extrude (circle 5) (f 30) (th 90) (f 30))
```

---

## Paths (Recorded Movements)

Paths record turtle movements for later replay. The code inside `path` executes
on a "recorder" turtle - you can use any Clojure code including loops.

### Basic Path

```clojure
;; Record a simple path
(def square-path (path (f 20) (th 90) (f 20) (th 90) (f 20) (th 90) (f 20)))

;; Use with extrude
(extrude (circle 5) square-path)
```

### Path with Loops

```clojure
;; Record a square using dotimes
(def square (path (dotimes [_ 4] (f 30) (th 90))))

;; Record a zigzag
(def zigzag (path (dotimes [_ 5] (f 10) (th 60) (f 10) (th -60))))

;; Use paths in extrude or loft
(extrude (rect 10 10) square)
(loft (circle 15) #(scale %1 (- 1 %2)) zigzag)
```

### Path with 3D Movement

```clojure
;; Helix path
(def helix (path (dotimes [_ 36] (f 5) (th 10) (tv 5))))

;; Spiral staircase
(extrude (rect 20 5) helix)
```

### Combining Paths

```clojure
;; Define reusable path segments
(def step (path (f 10) (tv 45) (f 10) (tv -45)))

;; Paths can be used multiple times
(extrude (circle 8) step)
(pen :off) (f 50) (th 90)
(extrude (circle 8) step)
```

---

## Closed Extrusion (Torus-like)

`extrude-closed` creates a closed mesh where the last ring connects back to the first.
Use this for paths that return to the starting point.

```clojure
;; Square torus
(def square-path (path (dotimes [_ 4] (f 20) (th 90))))
(extrude-closed (circle 5) square-path)

;; Triangular torus
(def tri-path (path (dotimes [_ 3] (f 30) (th 120))))
(extrude-closed (circle 8) tri-path)

;; Hexagonal torus
(def hex-path (path (dotimes [_ 6] (f 15) (th 60))))
(extrude-closed (rect 4 4) hex-path)
```

### Verifying Manifold Status

Closed extrusions create proper manifold meshes suitable for boolean operations:

```clojure
(def hex-path (path (dotimes [_ 6] (f 20) (th 60))))
(def square-torus (extrude-closed (circle 5) hex-path))

;; Check if it's a valid manifold
(mesh-status square-torus)
;; => {:manifold? true, :status :ok, :volume 1234.56, ...}

;; Boolean operations work on manifold meshes
(mesh-difference square-torus (box 30))
```

**How it works**: The path-based `extrude-closed` pre-processes the path to calculate
segment shortening at corners. Each segment is shortened by the shape radius at
corners, and all rings are collected into a single manifold mesh where the last
ring connects back to the first.

---

## Arc Commands

Draw smooth arcs by combining movement with rotation.

### Basic Arcs

```clojure
;; Quarter circle turning left (horizontal arc)
(arc-h 20 90)

;; Quarter circle turning right
(arc-h 20 -90)

;; Arc going up (vertical arc)
(arc-v 15 45)

;; Arc going down
(arc-v 15 -45)
```

### S-Curve

```clojure
;; S-curve from two opposite arcs
(arc-h 15 90)
(arc-h 15 -90)
```

### Spiral Extrusion

```clojure
;; Spiral tube: 8 quarter-turns
(extrude (circle 3)
  (dotimes [_ 8]
    (arc-h 20 90)))
```

### Curved Pipe

```clojure
;; Pipe with 90° bend
(extrude (circle 5)
  (f 30)
  (arc-h 15 90)
  (f 30))
```

### 3D Wavy Loop

```clojure
;; Alternating horizontal and vertical arcs create a 3D wavy closed shape
(extrude (circle 4)
  (dotimes [_ 4]
    (arc-h 25 90)
    (arc-v 25 90)))
```

### 3D Curved Path

```clojure
;; Combined horizontal and vertical arcs
(extrude (rect 6 6)
  (f 20)
  (arc-h 15 90)
  (arc-v 15 45)
  (f 20))
```

---

## Bezier Curves

Draw smooth bezier curves to target positions.

### Auto Control Points

```clojure
;; Bezier to a point (control points auto-generated)
;; The curve starts tangent to the current heading
(bezier-to [50 30 0])
```

### Quadratic Bezier (1 Control Point)

```clojure
;; Curve pulled toward the control point
(bezier-to [40 0 0] [20 25 0])
```

### Cubic Bezier (2 Control Points)

```clojure
;; S-curve with two control points
(bezier-to [50 0 0] [15 30 0] [35 -30 0])
```

### Bezier to Anchor

```clojure
;; Mark a destination
(pen :off)
(f 60) (tv 30) (f 30)
(mark :target)

;; Return to origin and draw bezier to anchor
(reset)
(pen :on)
(bezier-to-anchor :target)
```

### Bezier Extrusion

```clojure
;; Extruded bezier curve
(extrude (circle 4)
  (bezier-to [60 40 0]))
```

### Complex 3D Bezier Path

```clojure
;; 3D bezier with explicit control points
(extrude (rect 5 5)
  (bezier-to [40 20 30] [10 30 10] [30 -10 20]))
```

### Smooth Path Between Anchors

```clojure
;; Define two waypoints
(mark :start)
(pen :off)
(f 50) (th 90) (f 30) (tv 45) (f 20)
(mark :end)

;; Smooth bezier path from start to end
(goto :start)
(pen :on)
(extrude (circle 3)
  (bezier-to-anchor :end))
```

---

## Resolution Control

Control the smoothness of curves and circular primitives globally.

### Resolution Modes

```clojure
;; Fixed segment count (like OpenSCAD $fn)
(resolution :n 32)

;; Maximum angle per segment (like OpenSCAD $fa)
(resolution :a 5)

;; Maximum segment length (like OpenSCAD $fs)
(resolution :s 0.5)
```

### Low Resolution Draft

```clojure
;; Fast iteration with low resolution
(resolution :n 8)
(extrude (circle 10) (arc-h 30 180))
```

### High Resolution for Export

```clojure
;; High quality for final export
(resolution :n 32)
(extrude (circle 10) (arc-h 30 180))
```

### Resolution Affects Primitives

```clojure
;; Resolution affects circle segments
(resolution :n 8)
(extrude (circle 15) (f 20))  ; 8-sided "circle"

(reset [40 0 0])
(resolution :n 32)
(extrude (circle 15) (f 20))  ; smooth circle
```

### Override for Specific Calls

```clojure
;; Use global resolution
(resolution :n 16)
(arc-h 20 90)

;; Override for a specific call
(arc-h 20 90 :steps 48)

;; Explicit segment count for primitives
(circle 10 64)           ; 64-segment circle
(sphere 15 32 16)        ; 32x16 sphere
```

### Resolution-Based Workflow

```clojure
;; Draft mode: fast iteration
(resolution :n 6)

;; Design your model
(def spiral-path (path (dotimes [_ 8] (arc-h 20 45))))
(extrude (circle 5) spiral-path)

;; Preview mode: check details
(resolution :n 16)

;; Export mode: maximum quality
(resolution :n 32)
```

### Angle-Based Resolution

```clojure
;; Max 10° per segment
(resolution :a 10)

;; A 90° arc gets ceil(90/10) = 9 segments
(arc-h 20 90)

;; A 45° arc gets ceil(45/10) = 5 segments
(arc-h 20 45)
```

### Length-Based Resolution (for 3D Printing)

```clojure
;; Max 1mm per segment (good for 3D printing)
(resolution :s 1)

;; Larger arcs get more segments automatically
(arc-h 10 90)   ; ~16 segments (arc-length ≈ 15.7)
(arc-h 50 90)   ; ~79 segments (arc-length ≈ 78.5)
```

### Round Joints with Resolution

```clojure
;; Resolution affects round joint smoothness
(resolution :n 8)
(joint-mode :round)
(extrude (circle 5) (f 20) (th 90) (f 20))

(reset [50 0 0])
(resolution :n 24)
(joint-mode :round)
(extrude (circle 5) (f 20) (th 90) (f 20))
```

---

## Lateral Movement

Pure translations along local axes (no heading change):

```clojure
;; Position with lateral movement
(reset)
(f 50) (u 30) (th 180)    ; forward 50, up 30, face back

;; Slide a registered mesh
(attach! :gear (rt 10))    ; move gear right by 10
```

---

## Animation

Define timeline-based animations on registered meshes or the camera:

```clojure
;; Register a mesh first
(register gear (extrude (circle 8) (f 3)))

;; Simple spin (3 seconds, linear)
(anim! :spin 3.0 :gear
  (span 1.0 :linear (tr 360)))

;; Multi-span entrance with easing
(anim! :entrance 8.0 :gear
  (span 0.10 :out (f 6))        ; fast start, slow arrival
  (span 0.80 :linear (tr 720))  ; steady rotation
  (span 0.10 :in (f -6)))       ; slow start, fast exit

;; Camera orbital mode (automatic when target is :camera)
;; Commands reinterpreted as cinematic operations:
;;   rt/lt = orbit horizontally   u/d = orbit vertically
;;   f = dolly                    th/tv = pan/tilt
;;   tr = roll
;; Pivot = OrbitControls target at registration time

;; 360° horizontal orbit
(anim! :cam-orbit 5.0 :camera
  (span 1.0 :in-out (rt 360)))

;; Dolly in, orbit, elevate
(anim! :cam-dolly 3.0 :camera
  (span 0.5 :out (f 20))
  (span 0.3 :linear (rt 45))
  (span 0.2 :in (u 15)))

;; Cinematic: orbit + pan away from pivot
(anim! :cam-reveal 4.0 :camera
  (span 0.6 :in-out (rt 90) (u 20))
  (span 0.4 :out (th -15)))

;; Looping animation
(anim! :spin-forever 2.0 :gear :loop
  (span 1.0 :linear (tr 360)))

;; Slow visible rotation (ang-velocity 20 → 360° takes as long as (f 20))
(anim! :gear-turn 4.0 :gear
  (span 0.30 :out :ang-velocity 20 (f 10) (th 45))
  (span 0.70 :linear (f 20)))

;; Instantaneous rotation (ang-velocity 0 → rotation takes 0 frames)
(anim! :zigzag 3.0 :gear
  (span 1.0 :linear :ang-velocity 0 (f 10) (th 90) (f 10)))

;; Parallel: simultaneous commands within a span
(anim! :diagonal-orbit 5.0 :camera
  (span 1.0 :in-out (parallel (rt 360) (u 90))))

;; Mix parallel and sequential
(anim! :cam-complex 4.0 :camera
  (span 0.5 :out (parallel (rt 180) (u 45)))   ; simultaneous orbit
  (span 0.5 :in (f -30)))                       ; then dolly out

;; Target linking: child inherits parent's position delta
(register box-obj (box 20))
(anim! :box-move 5.0 :box-obj
  (span 1.0 :linear (f 100)))
(link! :camera :box-obj)                         ; camera follows box
(anim! :cam-orbit 5.0 :camera
  (span 1.0 :linear (rt 360)))
(unlink! :camera)                                ; remove link

;; Playback control
(play! :spin)           ; start
(pause! :spin)          ; pause
(stop! :spin)           ; stop and reset
(seek! :spin 0.5)       ; jump to 50%
(anim-list)             ; list all animations
```

### Procedural Animations

Procedural animations call a mesh-generating function every frame:

```clojure
;; Growing sphere
(register blob (sphere 1))
(anim-proc! :grow 3.0 :blob :out
  (fn [t] (sphere (+ 1 (* 19 t)))))
(play!)

;; Bending arm
(register arm (extrude (circle 2) (f 15) (f 12)))
(anim-proc! :bend 2.0 :arm :in-out
  (fn [t] (extrude (circle 2) (f 15) (th (* t 90)) (f 12))))
(play!)

;; Twisting bar
(register bar (extrude (rect 10 5) (f 40)))
(anim-proc! :twist 4.0 :bar :linear
  (fn [t] (loft-n 32 (rect 10 5)
            #(rotate-shape %1 (* %2 t 180))
            (f 40))))
(play!)
```

### Mesh Anchors and Enhanced Links

```clojure
;; Define skeleton with marks
(def arm-sk (path (mark :top) (f 15) (mark :elbow)))

;; Register meshes and attach skeleton
(register upper (cyl 3 15))
(register lower (cyl 2.5 12))
(attach-path :upper arm-sk)

;; Link with anchor and rotation inheritance
(link! :lower :upper :at :elbow :inherit-rotation true)

;; Animate — lower follows at elbow
(anim! :swing 1.0 :upper :loop
  (span 0.5 :in-out (th 30))
  (span 0.5 :in-out (th -30)))
(play!)
```

### Hierarchical Assemblies

```clojure
(def body-sk (path
  (mark :shoulder-l) (rt -7)
  (mark :shoulder-r) (rt 14)))

(def arm-sk (path (mark :top) (f 15) (mark :elbow)))

(with-path body-sk
  (register puppet
    {:torso (box 12 6 20)
     :r-arm (do (goto :shoulder-r)
                (with-path arm-sk
                  {:upper (cyl 3 15)
                   :lower (do (goto :elbow) (cyl 2.5 12))}))
     :l-arm (do (goto :shoulder-l)
                (with-path arm-sk
                  {:upper (cyl 3 15)
                   :lower (do (goto :elbow) (cyl 2.5 12))}))}))

;; Animate arms
(anim! :r-swing 1.0 :puppet/r-arm/upper :loop
  (span 0.5 :in-out (tv 30))
  (span 0.5 :in-out (tv -30)))

(hide :puppet/l-arm)             ; hide subtree by prefix
(play!)
```

---

## Text on Path

Place 3D text along a curved path. Each letter is oriented tangent to the curve.

```clojure
;; Define a curved path
(def curve (path (dotimes [_ 40] (f 2) (th 3))))

;; Visualize the path (optional)
(follow-path curve)

;; Reset turtle and place text along the curve
(reset)
(text-on-path "Hello Ridley" curve :size 10 :depth 3 :spacing 1 :align :center)
```

### Options

```clojure
(text-on-path "TEXT" path
  :size 10          ; font size (default 10)
  :depth 5          ; extrusion depth (default 5)
  :spacing 0        ; extra letter spacing (default 0)
  :align :start     ; :start, :center, or :end (default :start)
  :overflow :truncate  ; :truncate, :wrap, or :scale (default :truncate)
  :font custom-font)   ; optional custom font
```

### Alignment Examples

```clojure
(def arc (path (dotimes [_ 20] (f 5) (th 9))))

;; Text at start of path
(text-on-path "START" arc :size 8 :depth 2 :align :start)

;; Text centered on path
(text-on-path "CENTER" arc :size 8 :depth 2 :align :center)

;; Text at end of path
(text-on-path "END" arc :size 8 :depth 2 :align :end)
```

### Circular Text

```clojure
;; Create a full circle path
(def circle-path (path (dotimes [_ 36] (f 5) (th 10))))

;; Text wraps around the circle
(text-on-path "RIDLEY 3D CAD" circle-path :size 8 :depth 2 :overflow :wrap)
```

---

## Manifold Operations

Manifold operations validate meshes and perform boolean operations (CSG).
Requires manifold-3d WASM module (loaded automatically from CDN).

### Mesh Validation

```clojure
;; Create a mesh
(box 20)

;; Check if the mesh is valid (watertight, no self-intersections)
(manifold? (first (:meshes (get-turtle))))
;; => true

;; Get detailed status
(mesh-status (first (:meshes (get-turtle))))
;; => {:manifold? true, :status :ok, :volume 8000, :surface-area 2400}
```

### Boolean Union

Combine two meshes into one:

```clojure
;; Create first mesh
(box 20)
(def mesh-a (first (:meshes (get-turtle))))

;; Create second mesh (overlapping)
(f 15)
(sphere 12)
(def mesh-b (second (:meshes (get-turtle))))

;; Union: A + B
(mesh-union mesh-a mesh-b)
```

### Boolean Difference

Subtract one mesh from another:

```clojure
;; Box with spherical hole
(box 30)
(def base (first (:meshes (get-turtle))))

(sphere 18)
(def cutter (second (:meshes (get-turtle))))

;; Difference: A - B
(mesh-difference base cutter)
```

### Boolean Intersection

Keep only the overlapping region:

```clojure
;; Create two overlapping shapes
(box 25)
(def a (first (:meshes (get-turtle))))

(f 10)
(box 25)
(def b (second (:meshes (get-turtle))))

;; Intersection: A ∩ B
(mesh-intersection a b)
```

### Practical Example: Dice

```clojure
;; Definitions panel:
(def dice-size 20)
(def pip-radius 2.5)
(def pip-depth 1.5)

;; Create the base cube
(box dice-size)

;; Note: For a complete dice, you would create spheres at pip positions
;; and use mesh-difference to subtract them from the cube.
```

---

## Scene Registry

Register named meshes for show/hide control and easy export.
Named meshes persist across re-evaluations.

### Register Objects

```clojure
;; register creates a named object and shows it
;; On re-eval, updates the mesh but preserves visibility state
(def sq (path (dotimes [_ 4] (f 20) (th 90))))
(register torus (extrude-closed (circle 5) sq))
(register cube (box 30))
```

### List Objects

```clojure
(registered-names)   ;; => [:torus :cube] - all registered names
(visible-names)      ;; => [:torus :cube] - currently visible
```

### Show/Hide Objects

```clojure
(hide! :torus)       ;; Hide torus from viewport
(show! :torus)       ;; Show it again
(hide-all!)          ;; Hide all meshes
(show-all!)          ;; Show all meshes
(show-only-registered!)  ;; Show named, hide anonymous
```

### Toggle View Button

The "All" / "Objects" button in the viewport toolbar toggles between:
- **All**: Shows all meshes (named + anonymous from turtle)
- **Objects**: Shows only registered (named) meshes

### Export to STL

```clojure
(save-stl my-mesh)            ;; Downloads mesh as STL
(save-stl (visible-meshes))   ;; Export all visible meshes
```

---

## Shape Preview (Stamp)

Debug shape placement by visualizing 2D shapes as wireframe outlines at the current turtle pose.

### Basic Stamp

```clojure
;; Show a circle at the current position
(stamp (circle 10))

;; Move turtle and stamp at new position
(f 30)
(tv 45)
(stamp (circle 15))
```

### Stamp with Holes

```clojure
;; Washer shape shows both outer and inner contours
(def washer (shape-difference (circle 20) (circle 14)))
(stamp washer)

;; Move forward and stamp again to preview extrusion endpoints
(f 40)
(stamp washer)
```

### Multiple Stamps Along Path

```clojure
;; Preview shape at key positions along a path
(dotimes [i 5]
  (stamp (circle (+ 5 (* i 2))))
  (f 15)
  (th 20))
```

---

## 2D Shape Booleans

Combine 2D shapes before extrusion to create complex cross-sections with holes.

### Hollow Tube (Washer)

```clojure
;; Create a washer shape: outer circle minus inner circle
(def washer (shape-difference (circle 15) (circle 10)))

;; Extrude to create a hollow tube
(register tube (pure-extrude-path washer (quick-path 40)))
```

### L-Shaped Tube with Corner

```clojure
;; Washer extruded along a cornered path
(def washer (shape-difference (circle 15) (circle 10)))

;; L-shaped path: 30 forward, 90° turn, 30 forward
(register elbow (pure-extrude-path washer (quick-path 30 90 30)))
```

### Shape Union

```clojure
;; Merge two overlapping circles into a single shape
(def merged (shape-union
              (translate-shape (circle 10) -5 0)
              (translate-shape (circle 10) 5 0)))

(register merged-tube (pure-extrude-path merged (quick-path 30)))
```

### Shape Intersection

```clojure
;; Keep only the overlapping region of two circles
(def lens (shape-intersection
            (translate-shape (circle 15) -5 0)
            (translate-shape (circle 15) 5 0)))

(register lens-shape (pure-extrude-path lens (quick-path 20)))
```

### Shape Offset

```clojure
;; Expand or contract a shape
(def small-rect (rect 20 10))
(def rounded (shape-offset small-rect 3))           ;; expanded with rounded corners
(def shrunk (shape-offset small-rect -2))            ;; contracted

(register expanded (pure-extrude-path rounded (quick-path 15)))
```

### Complex Profile

```clojure
;; Rectangular tube with rounded outer, square inner
(def outer (shape-offset (rect 30 20) 3))    ;; rounded rectangle
(def inner (rect 24 14))                      ;; square hole
(def channel (shape-difference outer inner))

(register channel-tube (pure-extrude-path channel (quick-path 50)))
```

---

## Custom Shapes from Points

Create shapes from arbitrary 2D point vectors:

```clojure
;; L-shaped profile
(def my-L
  (make-shape [[0 0] [30 0] [30 10] [10 10] [10 30] [0 30]]))

(extrude my-L (f 15))

;; Arrow shape
(def arrow
  (make-shape [[0 0] [20 10] [15 10] [15 25] [5 25] [5 10] [0 10]]))

(extrude arrow (f 10))
```

**Note**: Points should be in counter-clockwise order for correct face normals.

---

## Complete Example: Ring with Hole

```clojure
;; Create a torus
(def ring-path (path (dotimes [_ 36] (f 5) (th 10))))
(def ring (extrude-closed (circle 3) ring-path))

;; Create a cylinder to cut a hole
(tv 90)  ; Point up
(def hole (extrude (circle 20) (f 20)))

;; Subtract to create ring with flat spot
(def final (mesh-difference ring hole))

;; Register and show
(register my-ring final)
```

---

## Face Inspection and Highlighting

Inspect faces of meshes and highlight them in the viewport.

### Inspecting Faces

```clojure
;; Create a box and inspect its faces
(def b (stamp (box 50 30 20)))

;; List all faces with basic info
(list-faces b)
;; => [{:id :top :normal [0 0 1] :center [0 0 10] ...}
;;     {:id :bottom :normal [0 0 -1] :center [0 0 -10] ...}
;;     {:id :front :normal [0 1 0] ...}
;;     {:id :back :normal [0 -1 0] ...}
;;     {:id :left :normal [-1 0 0] ...}
;;     {:id :right :normal [1 0 0] ...}]

;; Get just the face IDs
(face-ids b)
;; => (:top :bottom :front :back :left :right)

;; Detailed info on a specific face
(face-info b :top)
;; => {:id :top
;;     :normal [0 0 1]
;;     :center [0 0 10]
;;     :vertices [2 3 6 7]
;;     :vertex-positions [[25 15 10] [-25 15 10] [25 -15 10] [-25 -15 10]]
;;     :area 1500
;;     :edges [[2 3] [3 7] [6 7] [2 6]]
;;     :triangles [[2 6 7] [2 7 3]]}

;; Quick lookup of a single face
(get-face b :front)
;; => {:id :front :normal [0 1 0] :center [0 15 0] ...}
```

### Face Highlighting

```clojure
;; Create a cylinder
(def c (stamp (cyl 20 40)))

;; Flash a face temporarily (2 seconds, orange)
(flash-face c :top)

;; Flash with custom duration (5 seconds)
(flash-face c :bottom 5000)

;; Flash with custom color (green, 3 seconds)
(flash-face c :side 3000 0x00ff00)

;; Permanent highlight (stays until cleared)
(highlight-face c :top)
(highlight-face c :bottom 0xff0000)  ;; red

;; Remove all highlights
(clear-highlights)
```

### Exploring Meshes Interactively

```clojure
;; Create a shape and discover its faces
(def my-box (stamp (box 100)))

;; Flash each face one by one
(doseq [id (face-ids my-box)]
  (println "Faccia:" id)
  (flash-face my-box id 1500))

;; Find the face with the largest area
(let [faces (list-faces my-box)]
  (->> faces
       (map #(assoc % :area (:area (face-info my-box (:id %)))))
       (sort-by :area >)
       first
       :id))
```

### With Registered Objects

```clojure
;; Register an object
(register cube (extrude (rect 30 30) (f 30)))

;; Inspect and highlight
(list-faces cube)
(flash-face cube :top)

;; Highlight multiple faces
(highlight-face cube :top 0xff6600)
(highlight-face cube :front 0x00ff00)
(highlight-face cube :right 0x0066ff)

;; Clear when done
(clear-highlights)
```

### Primitive Face Groups

Different primitives have different face structures:

```clojure
;; Box: 6 named faces
(face-ids (stamp (box 20)))
;; => (:top :bottom :front :back :left :right)

;; Cylinder: 3 face groups
(face-ids (stamp (cyl 10 30)))
;; => (:top :bottom :side)

;; Cone: same as cylinder
(face-ids (stamp (cone 15 5 25)))
;; => (:top :bottom :side)

;; Sphere: single surface group
(face-ids (stamp (sphere 20)))
;; => (:surface)
```

---

## Editor Keybindings (Paredit)

The editor uses `@nextjournal/clojure-mode` for structural editing.

### Slurp & Barf

| Action | Mac | Windows/Linux |
|--------|-----|---------------|
| **Slurp forward** (pull next expr into parens) | `Ctrl+→` or `Cmd+Shift+K` | `Ctrl+→` or `Ctrl+Shift+K` |
| **Barf forward** (push last expr out of parens) | `Ctrl+←` or `Cmd+Shift+J` | `Ctrl+←` or `Ctrl+Shift+J` |
| **Slurp backward** | `Ctrl+Alt+←` | `Ctrl+Alt+←` |
| **Barf backward** | `Ctrl+Alt+→` | `Ctrl+Alt+→` |

### Semantic Selection

| Action | Shortcut |
|--------|----------|
| **Expand selection** (select larger form) | `Alt+↑` or `Cmd+1` |
| **Contract selection** | `Alt+↓` or `Cmd+2` |

### Other

| Action | Shortcut |
|--------|----------|
| **Run code** | `Cmd+Enter` |
| **Undo** | `Cmd+Z` |
| **Redo** | `Cmd+Shift+Z` |

### Example

```
;; Start: (foo |bar) baz   (cursor after foo)
;; Slurp forward (Ctrl+→): (foo bar baz)

;; Start: (foo bar| baz)
;; Barf forward (Ctrl+←):  (foo bar) baz
```

Full documentation: https://nextjournal.github.io/clojure-mode/#keybindings
