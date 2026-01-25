(ns ridley.manual.content
  "Manual content structure and internationalization.
   For Phase 10a, content is hardcoded. Later will load from EDN files.")

;; Page structure - defines hierarchy and code examples
;; Examples are language-agnostic (code is the same in all languages)
;; KEY PRINCIPLE: Every example must produce visible output in the viewport
(def structure
  {:sections
   [{:id :part-1
     :pages
     [{:id :hello-ridley
       :examples
       [{:id :first-cube
         :code "(register cube (box 30))"
         :auto-run true}
        {:id :first-tube
         :code "(register tube\n  (extrude (circle 10) (f 30)))"}]}
      {:id :ui-overview
       :see-also [:editor-commands]}
      {:id :editor-commands
       :see-also [:ui-overview :repl-usage]}
      {:id :the-turtle
       :see-also [:control-structures :extrude-basics]
       :examples
       [{:id :movement
         :code "(f 50)\n(th 90)\n(f 50)"}
        {:id :spiral-path
         :code "(dotimes [i 50]\n  (f i)\n  (th 90))"}]}
      {:id :clojure-basics
       :examples
       [{:id :define-value
         :code "(def size 20)\n(register cube (box size))"}
        {:id :define-function
         :code "(defn tube [r len]\n  (extrude (circle r) (f len)))\n\n(register t1 (tube 10 40))"}]}]}
    {:id :part-2
     :pages
     [{:id :extrude-basics
       :examples
       [{:id :circle-extrude
         :code "(register tube\n  (extrude (circle 15) (f 40)))"}
        {:id :rect-extrude
         :code "(register bar\n  (extrude (rect 30 20) (f 50)))"}
        {:id :bent-extrude
         :code "(register bent\n  (extrude (circle 8)\n    (f 30) (th 45) (f 30)))"}]}
      {:id :builtin-shapes
       :examples
       [{:id :circle-extrude
         :code "(register tube\n  (extrude (circle 15) (f 40)))"}
        {:id :rect-extrude
         :code "(register bar\n  (extrude (rect 30 20) (f 50)))"}
        {:id :polygon-extrude
         :code "(register hex\n  (extrude (polygon 6 15) (f 25)))"}
        {:id :star-extrude
         :code "(register star-bar\n  (extrude (star 5 20 10) (f 15)))"}]}
      {:id :custom-shapes
       :examples
       [{:id :triangle-shape
         :code "(register prism\n  (extrude\n    (shape (f 30) (th 120) (f 30) (th 120) (f 30))\n    (f 40)))"}
        {:id :l-shape
         :code "(register l-beam\n  (extrude\n    (shape\n      (f 30) (th 90) (f 10) (th 90)\n      (f 20) (th -90) (f 10) (th 90)\n      (f 10) (th 90) (f 20))\n    (f 50)))"}]}
      {:id :reusable-shapes
       :examples
       [{:id :def-shape
         :code "(def my-circle (circle 12))\n\n(register tube1 (extrude my-circle (f 30)))\n(tv 90) (f 50) (tv -90) (register tube2 (extrude my-circle (f 50)))"}
        {:id :def-path
         :code "(def corner (path (f 30) (th 90) (f 30)))\n\n(register pipe\n  (extrude (circle 8) corner))"}
        {:id :follow-path
         :code "(def curve (path (f 30) (tv 30) (f 20) (bezier-to [0 60 0] [0 20 30] [0 40 -30])))\n\n;; Draw the curve with the turtle\n(follow-path curve)"}]}
      {:id :extrude-closed
       :examples
       [{:id :square-torus
         :code "(register torus\n  (extrude-closed (circle 5)\n    (f 30) (th 90)\n    (f 30) (th 90)\n    (f 30) (th 90)\n    (f 30)))"}
        {:id :hex-ring
         :code "(register hex-ring\n  (extrude-closed (polygon 6 4)\n    (dotimes [_ 6]\n      (f 25) (th 60))))"}]}
      {:id :shape-transforms
       :examples
       [{:id :scale-shape
         :code "(register tapered\n  (loft (circle 20) (circle 10) (f 40)))"}
        {:id :morph-shapes
         :code "(register morph\n  (loft (rect 30 30) (circle 15) (f 40)))"}]}
      {:id :joint-modes
       :examples
       [{:id :joint-flat
         :code "(joint-mode :flat)\n(register flat-bend\n  (extrude (rect 15 15)\n    (f 30) (th 90) (f 30)))"}
        {:id :joint-round
         :code "(joint-mode :round)\n(register round-bend\n  (extrude (rect 15 15)\n    (f 30) (th 90) (f 30)))"}
        {:id :joint-tapered
         :code "(joint-mode :tapered)\n(register tapered-bend\n  (extrude (rect 15 15)\n    (f 30) (th 90) (f 30)))"}]}
      {:id :resolution-settings
       :examples
       [{:id :resolution-n
         :code "(resolution :n 20)\n(joint-mode :round)\n(register res-demo\n  (extrude (circle 15)\n    (f 50) (th 90) (f 50)))"}
        {:id :resolution-a
         :code "(resolution :a 15)\n(register angle-res\n  (extrude (circle 15)\n    (f 20) (arc-h 15 180) (f 20)))"}
        {:id :resolution-steps
         :code "(register custom-steps\n  (extrude (circle 15)\n    (f 20) (arc-h 15 180 :steps 32) (f 20)))"}]}]}
    {:id :part-3
     :pages
     [{:id :mesh-basics
       :see-also [:boolean-operations :attach-meshes]
       :examples
       [{:id :mesh-register
         :code "(register cube (box 30))"}
        {:id :mesh-info
         :code "(register tube\n  (extrude (circle 10) (f 40)))\n(println (info :tube))"}
        {:id :mesh-get-mesh
         :code "(register a (box 20))\n(register b (cyl 8 30))\n(hide :a) (hide :b)\n(register ab\n  (mesh-union (get-mesh :a) (get-mesh :b)))"}]}
      {:id :boolean-operations
       :examples
       [{:id :bool-union
         :code "(register a (box 30))\n(f 15) (tv 90) (f 15) (tv -90)\n(register b (cyl 12 40))\n(hide :a)\n(hide :b)\n(register ab\n  (mesh-union (get-mesh :a) (get-mesh :b)))"}
        {:id :bool-difference
         :code "(register a (box 30))\n(f 15) (tv 90) (f 15) (tv -90)\n(register b (cyl 12 40))\n(hide :a)\n(hide :b)\n(register ab\n  (mesh-difference (get-mesh :a) (get-mesh :b)))"}
        {:id :bool-intersection
         :code "(register a (box 30))\n(f 15) (tv 90) (f 15) (tv -90)\n(register b (cyl 12 40))\n(hide :a)\n(hide :b)\n(register ab\n  (mesh-intersection (get-mesh :a) (get-mesh :b)))"}
        {:id :bool-hull
         :code "(register p1 (sphere 10))\n(f 40)\n(register p2 (sphere 10))\n(f -20) (th 90) (f 30)\n(register p3 (sphere 10))\n\n(register hull-shape\n  (mesh-hull (get-mesh :p1) (get-mesh :p2) (get-mesh :p3)))"}
        {:id :bool-union-for
         :code "(register row\n  (mesh-union\n    (for [i (range 10)]\n      (attach (cyl 5 30) (f (* i 9))))))"}]}
      {:id :attach-meshes
       :examples
       [{:id :attach-basic
         :code "(register base (box 40 40 10))\n\n(register top\n  (attach (cyl 10 50) (f 31) (tv 90)))"}
        {:id :attach-boolean
         :code "(register drilled\n  (mesh-difference\n    (box 40)\n    (attach (cyl 10 50)\n      (f 20) (tv 90) (f 20))))"}
        {:id :attach-clone
         :code "(register original (box 10))\n(register copy1 (clone original (f 30)))\n(register copy2 (clone copy1 (tv 90) (f 30)))"}
        {:id :attach-clone-rotated
         :code "(register original (box 10 30 5))\n(register copy1 (clone original (f 30)))\n(register copy2 (clone copy1 (tv 90) (tr 90) (f 30)))"}]}
      {:id :attach-faces
       :examples
       [{:id :attach-face-frustum
         :code "(register frustum\n  (attach-face (box 30) :top\n    (inset 10) (f 30)))"}
        {:id :clone-face-spike
         :code "(register spike\n  (clone-face (box 30) :top\n    (inset 10) (f 30)))"}]}]}
    {:id :part-4
     :pages
     [{:id :mark-goto-pushpop
       :see-also [:the-turtle :arcs-beziers]
       :examples
       [{:id :mark-goto-basic
         :code "(register base (box 20 20 5))\n(mark :top)\n(f 40) (th 90) (f 30)\n(register beacon (cyl 5 30))\n(goto :top)\n(register tower (cyl 8 60))"}
        {:id :push-pop-branch
         :code "(register trunk (extrude (circle 5) (f 50)))\n(push-state)\n  (th 45) (register b1 (extrude (circle 3) (f 30)))\n(pop-state)\n(push-state)\n  (th -45) (register b2 (extrude (circle 3) (f 30)))\n(pop-state)\n(register top (extrude (circle 3) (f 20)))"}
        {:id :multiple-anchors
         :code "(register row\n  (mesh-union\n    (for [i (range 5)]\n      (do\n        (mark (keyword (str \"pos\" i)))\n        (f 15)\n        (sphere 5)))))\n(goto :pos2)\n(register selected (cyl 2 30))"}]}
      {:id :arcs-beziers
       :see-also [:mark-goto-pushpop :resolution-settings]
       :examples
       [{:id :arc-h-basic
         :code "(register curved-tube\n  (extrude (circle 8)\n    (f 20) (arc-h 30 180) (f 20)))"}
        {:id :arc-v-basic
         :code "(register arc-loop\n  (extrude (circle 5)\n    (f 20) (arc-v 25 180) (f 20)))"}
        {:id :bezier-auto
         :code "(mark :start)\n(f 60) (th 90) (f 40)\n(mark :end)\n(goto :start)\n(register curve\n  (extrude (circle 4)\n    (bezier-to-anchor :end)))"}
        {:id :bezier-control
         :code "(register s-curve\n  (extrude (circle 4)\n    (bezier-to [0 60 0] [0 20 30] [0 40 -30])))"}]}
      {:id :colors-materials
       :see-also [:extrude-basics :mesh-basics]
       :examples
       [{:id :color-basic
         :code "(color 0xff0000)\n(register red-box (box 20))\n(f 40)\n(color 0x00ff00)\n(register green-cyl (cyl 10 30))"}
        {:id :material-pbr
         :code "(material :color 0x8888ff :metalness 0.8 :roughness 0.2)\n(register shiny (sphere 15))\n(f 40)\n(material :color 0xffaa00 :metalness 0.1 :roughness 0.9)\n(register matte (sphere 15))"}
        {:id :material-reset
         :code "(color 0xff0000)\n(register red (box 15))\n(f 30)\n(reset-material)\n(register default (box 15))"}]}
      {:id :text-shapes
       :see-also [:extrude-basics :custom-shapes :curves-beziers]
       :examples
       [{:id :text-basic
         :code "(register title\n  (extrude (text-shape \"HI\" :size 30) (f 5)))"}
        {:id :text-large
         :code "(register sign\n  (extrude (text-shape \"RIDLEY\" :size 20) (f 3)))"}
        {:id :extrude-text
         :code "(register hello\n  (extrude-text \"Hello\" :size 25 :depth 4))"}
        {:id :text-on-arc
         :code "(def arc-path (path (arc-h 50 180)))\n\n(register curved\n  (text-on-path \"CURVED TEXT\" arc-path\n    :size 12 :depth 3 :align :center))"}
        {:id :text-on-spiral
         :code "(def spiral (path (dotimes [_ 20] (f 8) (th 18) (tv 3))))\n\n(register spiral-text\n  (text-on-path \"SPIRAL\" spiral :size 10 :depth 2))"}]}]}
    {:id :part-5
     :pages
     [{:id :debug-panels
       :see-also [:debug-techniques]
       :examples
       [{:id :panel-basic
         :code "(register P1 (panel 60 40))\n(out P1 \"Hello World!\")"}
        {:id :panel-attach
         :code "(register cube (box 30))\n(register P1\n  (attach (panel 25 8)\n    (f 25) (tv 90) (f 25)))\n(out P1 \"This is a cube\")"}
        {:id :panel-multiline
         :code "(register P1 (panel 80 60))\n(out P1 \"Line 1\")\n(append P1 \"\\nLine 2\")\n(append P1 \"\\nLine 3\")"}]}
      {:id :debug-techniques
       :see-also [:debug-panels :repl-usage]
       :examples
       [{:id :tap-function
         :code "(register cube\n  (box (T \"size\" (* 10 3))))"}
        {:id :inspect-turtle
         :code "(f 50)\n(println \"pos:\" (turtle-position))\n(th 45)\n(println \"heading:\" (turtle-heading))"}
        {:id :step-by-step
         :code "(println \"Step 1: create base\")\n(register base (box 40 40 10))\n(println \"Step 2: move up\")\n(f 10)\n(println \"Step 3: create top\")\n(register top (cyl 15 20))"}]}]}
    {:id :part-6
     :pages
     [{:id :control-structures
       :see-also [:clojure-basics :local-variables]
       :examples
       [{:id :dotimes-ignore
         :code "(dotimes [_ 4]\n  (f 20)\n  (th 90))"}
        {:id :for-comprehension
         :code "(register tubes\n  (for [size [10 20 30]]\n    (attach (extrude (circle size) (f 20))\n      (f size))))"}
        {:id :when-conditional
         :code "(dotimes [i 10]\n  (f 10)\n  (when (odd? i)\n    (th 30)))"}]}
      {:id :local-variables
       :see-also [:clojure-basics :control-structures]
       :examples
       [{:id :let-basics
         :code "(let [r 15\n      h 40]\n  (register cyl\n    (extrude (circle r) (f h))))"}
        {:id :let-computed
         :code "(let [base 20\n      half (/ base 2)\n      quarter (/ half 2)]\n  (register box1 (box base))\n  (f (+ half quarter))\n  (register box2 (box half)))"}]}
      {:id :repl-usage
       :examples
       [{:id :repl-quick-test
         :code "(+ 1 2 3)"}
        {:id :repl-println
         :code "(println \"Hello from REPL!\")\n(println \"2 + 2 =\" (+ 2 2))"}]}]}]})

;; Internationalization - text content for each language
(def i18n
  {:en
   {:sections
    {:part-1 {:title "Getting Started"}
     :part-2 {:title "Shapes & Extrusion"}
     :part-3 {:title "Mesh Manipulation"}
     :part-4 {:title "Advanced Features"}
     :part-5 {:title "Debug Help"}
     :part-6 {:title "Clojure Advanced"}}
    :pages
    {:hello-ridley
     {:title "Hello Ridley"
      :content "Welcome to Ridley, a 3D modeling environment based on turtle graphics.

In Ridley, you create 3D objects by writing code. The simplest way is with primitives like `box` and `cyl`. But the real power comes from **extrusion** — sweeping a 2D shape along a path to create 3D geometry.

Let's see both approaches:"
      :examples
      {:first-cube {:caption "A simple cube"
                    :description "The `register` command gives a name to your shape so it appears in the viewport. `box` creates a cube with the given size."}
       :first-tube {:caption "Your first extrusion"
                    :description "`extrude` sweeps a 2D shape (here, a circle) along a path (here, forward 30 units). This creates a tube."}}}

     :ui-overview
     {:title "The Interface"
      :content "Ridley has three main areas:

**Editor** (left) — Write your code here. This is where you define shapes, functions, and geometry.

**Viewport** (right) — The 3D view showing your models. You can rotate (drag), zoom (scroll), and pan (right-drag) to explore.

**REPL** (bottom) — An interactive command line. Type expressions and press Enter to evaluate them instantly.

**Editor Toolbar:**
- **▶ Run** — Execute your code (same as Cmd+Enter)
- **Save** — Download your code as a .clj file
- **Load** — Load a .clj file from disk
- **Manual** — Open this manual

**Viewport Toolbar:**
- **Grid** — Toggle the reference grid
- **Axes** — Toggle X/Y/Z axis lines
- **Turtle** — Toggle the turtle indicator (shows position/direction)
- **Lines** — Toggle construction lines (pen traces)
- **Normals** — Toggle face normal visualization
- **Reset** — Reset camera to default view
- **Export STL** — Download visible meshes as STL file"}

     :editor-commands
     {:title "Editor Commands"
      :content "**Editor:**
- **Cmd+Enter** (Mac) or **Ctrl+Enter** (Windows/Linux) — Run all code
- Click the **▶ Run** button for the same effect

**REPL:**
- **Enter** — Evaluate the current line
- **↑/↓** — Navigate command history

**Paredit** (structural editing):
Paredit keeps your parentheses balanced automatically. Key commands:

- **Tab** — Indent current line
- **(** or **[** or **{** — Auto-insert matching bracket
- **Backspace** on empty brackets — Delete the pair
- **Ctrl+K** — Kill (cut) to end of line
- **Ctrl+Shift+K** — Kill the enclosing expression
- **Shift+Cmd+K** — Slurp: pull next element into parentheses
- **Shift+Cmd+J** — Barf: push last element out of parentheses

Example — Slurp: with cursor in `(+ 1 2|) 3`, Slurp → `(+ 1 2 3)`
Barf: with cursor in `(+ 1 2 3|)`, Barf → `(+ 1 2) 3`

**Search:**
- **Cmd+F** / **Ctrl+F** — Find in editor
- **Cmd+G** / **Ctrl+G** — Find next

**Undo/Redo:**
- **Cmd+Z** / **Ctrl+Z** — Undo
- **Cmd+Shift+Z** / **Ctrl+Shift+Z** — Redo"}

     :the-turtle
     {:title "The Turtle"
      :content "Ridley uses a **turtle** — an invisible cursor that moves through 3D space. As it moves, it draws lines that you can see in the viewport.

The turtle has:
- A **position** in 3D space
- A **heading** (the direction it's facing)
- An **up** vector (which way is \"up\" for the turtle)

Basic movement commands:
- `(f distance)` - move forward
- `(th angle)` - turn horizontally (yaw)
- `(tv angle)` - turn vertically (pitch)
- `(tr angle)` - roll"
      :examples
      {:movement {:caption "Basic movement"
                  :description "Move forward 50 units, turn 90°, then move forward again. The turtle draws visible lines as it moves."}
       :spiral-path {:caption "Drawing a spiral"
                     :description "`dotimes` repeats commands. The variable `i` counts from 0 to 49, so each forward step gets longer, creating a spiral."}}}

     :clojure-basics
     {:title "Clojure Basics"
      :content "Ridley code uses Clojure syntax. Here's what you need to know:

**Parentheses** — Every command is wrapped in parentheses: `(command arg1 arg2)`

**def** — Create a named value: `(def name value)`

**defn** — Create a function: `(defn name [args] body)`

These let you build reusable pieces and keep your code organized.

**Learning More**

To learn more about Clojure, check out these resources:

- [Clojure for the Brave and True](https://www.braveclojure.com/) — A free online book that's both fun and comprehensive

- [ClojureDocs](https://clojuredocs.org/) — Quick reference with examples for all core functions"
      :examples
      {:define-value {:caption "Define a value"
                      :description "Use `def` to name a value. Here we define `size` and use it to create a cube."}
       :define-function {:caption "Define a function"
                         :description "Use `defn` to create reusable functions. This `tube` function takes radius and length parameters."}}}

     :extrude-basics
     {:title "Extrusion"
      :content "**Extrusion** is the core concept in Ridley. You take a 2D shape and sweep it along a path to create 3D geometry.

```
(extrude shape movements...)
```

The **shape** defines the cross-section. The **movements** define the path the turtle follows. As the turtle moves, the shape is swept along, creating a solid."
      :examples
      {:circle-extrude {:caption "Circle → Tube"
                        :description "A circle extruded forward becomes a tube (cylinder)."}
       :rect-extrude {:caption "Rectangle → Bar"
                      :description "A rectangle extruded forward becomes a rectangular bar."}
       :bent-extrude {:caption "Bent path"
                      :description "The path can include turns. Here we create a bent tube by turning 45° mid-extrusion."}}}

     :builtin-shapes
     {:title "Built-in Shapes"
      :content "Ridley provides several 2D shapes you can extrude:

- `(circle radius)` — a circle
- `(rect width height)` — a rectangle
- `(polygon n radius)` — regular polygon with n sides
- `(star points outer inner)` — a star shape

Each shape becomes the cross-section of your extruded solid."
      :examples
      {:circle-extrude {:caption "Circle"
                        :description "`circle` creates a circle with the given radius. Extruded, it becomes a tube."}
       :rect-extrude {:caption "Rectangle"
                      :description "`rect` creates a rectangle with width and height. Extruded, it becomes a bar."}
       :polygon-extrude {:caption "Polygon"
                         :description "`polygon` creates regular n-sided polygons. A hexagon (6 sides) extruded becomes a hex bar."}
       :star-extrude {:caption "Star"
                      :description "`star` creates star shapes. The parameters are: points, outer radius, inner radius."}}}

     :custom-shapes
     {:title "Custom Shapes"
      :content "The `shape` macro lets you draw your own 2D shapes using turtle movements. The turtle traces a closed path, and that path becomes your shape.

```
(shape movements...)
```

The shape automatically closes — the last point connects back to the first."
      :examples
      {:triangle-shape {:caption "Triangle"
                        :description "Three forward movements with 120° turns create an equilateral triangle. Extruded, it becomes a prism."}
       :l-shape {:caption "L-shape"
                 :description "More complex paths create more complex cross-sections. This L-shape becomes an L-beam when extruded."}}}

     :reusable-shapes
     {:title "Reusable Shapes"
      :content "Shapes are just data — you can store them in variables and reuse them.

```
(def my-shape (circle 10))
(extrude my-shape (f 30))
```

Similarly, paths can be captured with the `path` macro and reused across multiple extrusions."
      :examples
      {:def-shape {:caption "Reusing a shape"
                   :description "Define a shape once, use it multiple times. Both tubes use the same circle but different lengths."}
       :def-path {:caption "Reusing a path"
                  :description "Paths can also be stored and reused. The `path` macro captures turtle movements for later use."}
       :follow-path {:caption "Drawing a path"
                     :description "Use `follow-path` to make the turtle trace a stored path, drawing visible lines in the viewport."}}}

     :extrude-closed
     {:title "Closed Extrusion"
      :content "`extrude-closed` creates shapes where the path forms a closed loop — like a torus or a frame. The last ring connects back to the first ring, with no end caps.

```
(extrude-closed shape movements...)
```

The path must return to (approximately) the starting position and orientation."
      :examples
      {:square-torus {:caption "Square torus"
                      :description "A square path with a circular cross-section creates a torus with flat sides."}
       :hex-ring {:caption "Hexagonal ring"
                  :description "A hexagonal path with a hexagonal cross-section. The shape sweeps around the entire loop."}}}

     :shape-transforms
     {:title "Shape Transitions"
      :content "The `loft` command lets you transition between different shapes along the extrusion path. The shape smoothly morphs from start to end.

```
(loft start-shape end-shape movements...)
```

This creates tapered forms, organic transitions, and complex geometry."
      :examples
      {:scale-shape {:caption "Tapered cone"
                     :description "Loft from a large circle to a small circle creates a tapered cone."}
       :morph-shapes {:caption "Shape morphing"
                      :description "Loft can transition between completely different shapes — here, from a square to a circle."}}}

     :joint-modes
     {:title "Joint Modes"
      :content "When an extrusion path has corners (turns), the `joint-mode` command controls how the corner junction is generated.

```
(joint-mode :flat)    ; direct connection (default)
(joint-mode :round)   ; smooth rounded corner
(joint-mode :tapered) ; tapered intermediate ring
```

The joint mode affects how the shape transitions through corners, impacting both aesthetics and geometry."
      :examples
      {:joint-flat {:caption "Flat joint"
                    :description "`:flat` connects rings directly at corners. Simple but can show sharp edges."}
       :joint-round {:caption "Round joint"
                     :description "`:round` adds intermediate rings that arc smoothly through the corner."}
       :joint-tapered {:caption "Tapered joint"
                       :description "`:tapered` adds a scaled intermediate ring, creating a tapered transition at corners."}}}

     :resolution-settings
     {:title "Resolution"
      :content "The `resolution` command controls curve smoothness for arcs, beziers, and round joints.

```
(resolution :n 32)  ; fixed number of segments
(resolution :a 5)   ; max angle per segment (degrees)
(resolution :s 0.5) ; max segment length (units)
```

You can also override resolution per-command using the `:steps` argument:
```
(arc-h 20 90 :steps 16)
```"
      :examples
      {:resolution-n {:caption "Fixed segments"
                      :description "`:n` sets a fixed number of segments for curves. Lower values = faster but coarser."}
       :resolution-a {:caption "Angle-based"
                      :description "`:a` sets max degrees per segment. Good for consistent smoothness regardless of arc size."}
       :resolution-steps {:caption "Per-command steps"
                          :description "Use `:steps` to override global resolution for a specific command."}}}

     :control-structures
     {:title "Control Structures"
      :content "Clojure provides several ways to control program flow:

**dotimes** — Repeat n times. Use `_` when you don't need the index:
```
(dotimes [_ 4] body)   ; repeat 4 times
(dotimes [i 10] body)  ; i goes 0,1,2...9
```

**for** — Create a sequence by iterating over values:
```
(for [x [1 2 3]] (f x))
```

**when** — Execute only if condition is true:
```
(when (> x 5) (do-something))
```

**if** — Choose between two branches:
```
(if condition then-expr else-expr)
```"
      :examples
      {:dotimes-ignore {:caption "Repeat without index"
                        :description "Use `_` when you don't need the loop counter. This draws a square by repeating 4 times."}
       :for-comprehension {:caption "For comprehension"
                           :description "`for` iterates over a collection and returns a vector. Here we register 3 tubes with different radii. Use `(hide tubes 1)` to hide the second."}
       :when-conditional {:caption "Conditional execution"
                          :description "`when` executes its body only if the condition is true. Here we turn only on odd iterations."}}}

     :local-variables
     {:title "Local Variables"
      :content "The `let` form creates local variables that exist only within its body:

```
(let [name1 value1
      name2 value2]
  body)
```

Variables can depend on earlier ones in the same `let`. This keeps your code clean and avoids repetition."
      :examples
      {:let-basics {:caption "Basic let"
                    :description "Define local variables `r` and `h`, then use them to create a cylinder."}
       :let-computed {:caption "Computed values"
                      :description "Variables can be computed from earlier ones. Note: in Clojure, operators like `+`, `-`, `*`, `/` use prefix notation: `(/ base 2)` instead of `base / 2`."}}}

     :repl-usage
     {:title "The REPL"
      :content "The **REPL** (Read-Eval-Print Loop) at the bottom of the editor is your interactive playground. Type expressions and press Enter to evaluate them immediately.

**Why use the REPL?**
- Test small pieces of code without modifying your main definitions
- Inspect values and meshes
- Explore Clojure functions interactively
- Debug by evaluating parts of expressions

**Mechanics:**
- Type an expression and press **Enter** to evaluate
- Use **↑/↓** arrows to navigate command history
- Results appear above the input line
- The REPL shares state with your definitions — you can access registered meshes

**Useful commands:**
```
(info :name)      ; show mesh info (vertices, faces)
(get-mesh :name)  ; retrieve a mesh by name
(hide :name)      ; hide a mesh from viewport
(show :name)      ; show a hidden mesh
```"
      :examples
      {:repl-quick-test {:caption "Quick calculations"
                         :description "Use the REPL as a calculator. Try arithmetic, string operations, or any Clojure expression."}
       :repl-println {:caption "Print output"
                      :description "`println` output appears in the REPL history. Use it for debugging your scripts."}}}

     :mesh-basics
     {:title "What is a Mesh"
      :content "A **mesh** is a 3D object made of vertices (points in space) and faces (polygons connecting vertices). In Ridley, meshes are the final output of your modeling operations.

**The mesh data structure:**
- **vertices** — a list of 3D points `[x y z]`
- **faces** — a list of vertex indices forming triangles

**Registering meshes:**
The `register` command gives a mesh a name and adds it to the scene:
```
(register name mesh-expression)
```

**Working with meshes:**
```
(get-mesh :name)   ; retrieve mesh by name (for boolean ops)
(info :name)       ; show mesh statistics in REPL
(hide :name)       ; hide from viewport
(show :name)       ; show again
```

All primitives (`box`, `cyl`, `sphere`) and extrusions produce meshes."
      :examples
      {:mesh-register {:caption "Register a mesh"
                       :description "`register` creates a named mesh that appears in the viewport. The name becomes a keyword you can use later."}
       :mesh-info {:caption "Inspect with info"
                   :description "`info` returns mesh statistics. Wrap with `println` to see the output in the REPL."}
       :mesh-get-mesh {:caption "Use get-mesh for operations"
                       :description "`get-mesh` retrieves a registered mesh by name. Use it to pass meshes to boolean operations."}}}

     :boolean-operations
     {:title "Boolean Operations"
      :content "Boolean operations combine meshes in different ways. Use `get-mesh` to retrieve a mesh by its registered name.

```
(mesh-union a b)        ; combine A and B
(mesh-difference a b)   ; subtract B from A
(mesh-intersection a b) ; keep only where A and B overlap
(mesh-hull a b c ...)   ; convex hull of all meshes
```

These operations require **manifold** meshes (watertight, no self-intersections). The result is a new mesh that you can register."
      :examples
      {:bool-union {:caption "Union"
                    :description "Combine two meshes into one. Overlapping regions are merged."}
       :bool-difference {:caption "Difference"
                         :description "Subtract one mesh from another. Creates holes, cuts, and carving effects."}
       :bool-intersection {:caption "Intersection"
                           :description "Keep only the volume where both meshes overlap. Creates lens-like shapes from spheres."}
       :bool-hull {:caption "Convex Hull"
                   :description "Create the smallest convex shape that contains all input meshes. Like wrapping them in shrink-wrap."}
       :bool-union-for {:caption "Union with for"
                        :description "Boolean operations accept vectors, so you can use `for` to generate multiple meshes and combine them in one call."}}}

     :attach-meshes
     {:title "Attach Meshes"
      :content "The `attach` function creates a mesh at the current turtle position and orientation. The `clone` macro creates a copy of an existing mesh at the turtle position.

```
(attach mesh)    ; place mesh at turtle position
(clone mesh)     ; create a copy at turtle position
```

Unlike `register`, these create geometry that follows the turtle's current transformation."
      :examples
      {:attach-basic {:caption "Basic attach"
                      :description "The base is registered at origin. The cylinder is attached with movements that position it relative to the turtle."}
       :attach-boolean {:caption "Attach for boolean"
                        :description "`attach` can be used inline to position one mesh relative to another for boolean operations."}
       :attach-clone {:caption "Clone a mesh"
                      :description "Use `clone` to create copies of an existing registered mesh at different positions."}
       :attach-clone-rotated {:caption "Clone with rotation"
                              :description "Clones can include rotations. Here `tv` pitches and `tr` rolls the turtle before placing the copy."}}}

     :attach-faces
     {:title "Attach to Faces"
      :content "The `attach-face` function attaches geometry to a specific face of a mesh. Use face keywords (`:top`, `:bottom`, `:front`, `:back`, `:left`, `:right`) or indices 0-5.

```
(attach-face mesh :top body)
```

The turtle is positioned at the face center, oriented outward. Use `inset` to shrink the face before extruding:

```
(inset dist)  ; move each vertex dist units toward center
```

Positive values shrink the face, negative values expand it."
      :examples
      {:attach-face-frustum {:caption "Frustum (attach-face)"
                             :description "`attach-face` modifies the original face. `inset` shrinks it, `f` moves it outward. Creates a frustum (tapered box)."}
       :clone-face-spike {:caption "Spike (clone-face)"
                          :description "`clone-face` creates new vertices. `inset` creates a smaller inner face, `f` extrudes it. Creates a spike."}}}

     :debug-panels
     {:title "3D Text Panels"
      :content "**Panels** are 3D text displays that float in the viewport. They always face the camera (billboard effect) and are perfect for debugging or annotating your models.

**Creating panels:**
```
(register P1 (panel width height))
```

**Writing to panels:**
- `(out P1 \"text\")` — replace content
- `(append P1 \"text\")` — add to content
- `(clear P1)` — clear content

**Positioning panels:**
Use `attach` to position panels relative to the turtle:
```
(register P1 (attach (panel 50 30) (f 20)))
```

Panels are NOT exported to STL — they're for visualization only."
      :examples
      {:panel-basic {:caption "Basic panel"
                     :description "Create a panel and write text to it. The panel appears at the turtle's current position."}
       :panel-attach {:caption "Positioned panel"
                      :description "Use `attach` to position the panel relative to geometry. The panel floats near the cube."}
       :panel-multiline {:caption "Multi-line text"
                         :description "Use `append` with `\\n` to add new lines. Build up complex messages incrementally."}}}

     :debug-techniques
     {:title "Debugging Techniques"
      :content "When your code doesn't work as expected, these techniques help you understand what's happening.

**The T (tap) function:**
`T` is a built-in debug helper that prints a value with a label and returns it unchanged:
```
(T \"label\" value)  ; prints \"label: value\", returns value
```

Insert `T` anywhere to see intermediate values without changing behavior. Its implementation is simply:
```
(defn T [label x] (println label \":\" x) x)
```
You can define your own variant if needed.

**Turtle inspection:**
- `(turtle-position)` — current [x y z] position
- `(turtle-heading)` — current direction vector
- `(turtle-up)` — current up vector

**Step-by-step execution:**
Add `println` statements between operations to see the sequence of events. This helps identify which step causes problems.

**Common issues:**
- Mesh not visible? Check `(hide :name)` / `(show :name)`
- Wrong position? Print turtle state before creating geometry
- Unexpected shape? Simplify and test each part separately"
      :examples
      {:tap-function {:caption "The T function"
                      :description "`T` is built-in. It prints the value with a label, then returns it unchanged. Here we see the computed size (30) in the REPL output."}
       :inspect-turtle {:caption "Inspect turtle state"
                        :description "Print the turtle's position and heading at any point to understand where geometry will be created."}
       :step-by-step {:caption "Step-by-step debugging"
                      :description "Add println between operations to trace execution. The output appears in the REPL history."}}}

     :mark-goto-pushpop
     {:title "Marks and State Stack"
      :content "When creating complex geometry, you often need to return to previous positions or branch out from a point. Ridley provides two mechanisms:

**Named Anchors (mark/goto):**
- `(mark :name)` — Save current position, heading, and up vector with a name
- `(goto :name)` — Teleport to a named anchor (draws line if pen is on)

Anchors are persistent and named — use them when you need to return to specific points multiple times or navigate between distant locations.

**State Stack (push-state/pop-state):**
- `(push-state)` — Push current turtle state onto an anonymous stack
- `(pop-state)` — Restore the most recently pushed state

The stack is perfect for branching: push-state before a branch, create geometry, pop-state to return, then branch again. It's like undo for turtle position.

**Key difference:**
- `mark`/`goto` = named bookmarks for navigation
- `push-state`/`pop-state` = anonymous stack for branching patterns"
      :examples
      {:mark-goto-basic {:caption "Mark and goto"
                         :description "Mark a position, move away to create something, then goto returns you to the marked spot. The tower is built exactly where we marked :top."}
       :push-pop-branch {:caption "Branching with push-state/pop-state"
                         :description "Classic tree pattern: push-state before each branch, pop-state to return to the trunk. Both branches originate from the same point."}
       :multiple-anchors {:caption "Multiple anchors"
                          :description "Create multiple named anchors in a loop, then navigate to any of them later. Note: `(keyword (str \"pos\" i))` builds keywords dynamically (:pos0, :pos1, etc.)."}}}

     :arcs-beziers
     {:title "Arcs and Bezier Curves"
      :content "Smooth curves are essential for organic shapes. Ridley provides arc and bezier commands that integrate with extrusion.

**Horizontal Arc:**
```
(arc-h radius angle)      ; turn left/right while moving
(arc-h radius angle :steps 16)
```
Positive angle = left turn, negative = right turn. The turtle ends up at `radius × angle_rad` distance away, heading rotated by `angle` degrees.

**Vertical Arc:**
```
(arc-v radius angle)      ; pitch up/down while moving
(arc-v radius angle :steps 16)
```
Positive angle = pitch up, negative = pitch down.

**Bezier Curves:**
```
(bezier-to [x y z])                    ; auto control points
(bezier-to [x y z] [c1])               ; quadratic (1 control)
(bezier-to [x y z] [c1] [c2])          ; cubic (2 controls)
(bezier-to-anchor :name)               ; bezier to named anchor
```

`bezier-to` with no control points creates a curve tangent to the current heading.

`bezier-to-anchor` is smarter: it respects **both** the current heading AND the anchor's saved heading, creating a smooth S-curve that honors both directions."
      :examples
      {:arc-h-basic {:caption "Horizontal arc (U-turn)"
                     :description "`arc-h 30 180` creates a 180° arc with radius 30 — a U-turn in the horizontal plane. Combined with straight segments."}
       :arc-v-basic {:caption "Vertical arc (loop)"
                     :description "`arc-v 25 180` creates a half-loop in the vertical plane, like going over a hill."}
       :bezier-auto {:caption "Auto bezier to anchor"
                     :description "Mark start, move away, mark end, then goto start and extrude with `bezier-to-anchor`. The curve respects both the starting direction and the anchor's saved direction."}
       :bezier-control {:caption "Bezier with control points"
                        :description "Explicit control points give precise control. Here `[0 20 30]` and `[0 40 -30]` create an S-curve."}}}

     :colors-materials
     {:title "Colors and Materials"
      :content "Every mesh can have its own color and material properties. Set them **before** creating geometry — they apply to all meshes created afterward.

**Color only:**
```
(color 0xff0000)        ; red (hex RGB)
(color 255 0 0)         ; red (RGB values 0-255)
```

**Full material control:**
```
(material :color 0x8888ff
          :metalness 0.8    ; 0-1, how metallic
          :roughness 0.2    ; 0-1, how smooth/rough
          :opacity 1.0      ; 0-1, transparency
          :flat-shading true)
```

When you call `material`, any unspecified options reset to defaults. To reset everything:
```
(reset-material)
```

PBR (Physically Based Rendering) guidelines:
- **Metals** (gold, chrome): metalness 0.8-1.0, roughness 0.1-0.4
- **Plastics**: metalness 0.0-0.1, roughness 0.3-0.7
- **Matte surfaces**: metalness 0.0, roughness 0.8-1.0"
      :examples
      {:color-basic {:caption "Setting colors"
                     :description "Use `color` to quickly set the color for subsequent geometry. Each mesh remembers the color that was active when it was created."}
       :material-pbr {:caption "PBR materials"
                      :description "Full control over material appearance. High metalness + low roughness = shiny metal. Low metalness + high roughness = matte plastic."}
       :material-reset {:caption "Reset to defaults"
                        :description "`reset-material` returns all material properties to the default blue appearance."}}}

     :text-shapes
     {:title "Text Shapes"
      :content "Convert text to 2D shapes using the built-in Roboto font.

**Basic usage:**
```
(text-shape \"Hello\")           ; default size
(text-shape \"Hello\" :size 30)  ; larger text
```

**Extrude for 3D text:**
```
(register sign (extrude (text-shape \"RIDLEY\" :size 20) (f 5)))
```

**Quick extrusion with `extrude-text`:**
```
(extrude-text \"Hello\" :size 25 :depth 4)
```
This is a shortcut that extrudes text at the turtle position, flowing along heading.

**Text on a path:**
```
(def my-arc (path (arc-h 50 180)))
(text-on-path \"CURVED\" my-arc :size 12 :depth 3)
```

Options for `text-on-path`:
- `:align` — `:start`, `:center`, or `:end`
- `:spacing` — extra letter spacing (default 0)
- `:overflow` — `:truncate`, `:wrap`, or `:scale`

**Built-in fonts:**
- `:roboto` — Roboto Regular (default)
- `:roboto-mono` — Roboto Mono (monospace)

**Load custom font:**
```
(load-font! \"/path/to/font.ttf\")  ; returns a promise
```"
      :examples
      {:text-basic {:caption "Simple 3D text"
                    :description "Extrude text shapes to create 3D letters. The :size option controls the font size in world units."}
       :text-large {:caption "Signage"
                    :description "Larger text for signs and titles. Adjust :size and extrusion depth (f 3) for desired proportions."}
       :extrude-text {:caption "Quick extrusion"
                      :description "`extrude-text` is a convenient shortcut: text flows along the turtle's heading and extrudes perpendicular to it."}
       :text-on-arc {:caption "Text on arc"
                     :description "Place text along a curved path. Each letter follows the curve tangent. Use `:align :center` to center text on the path."}
       :text-on-spiral {:caption "Text on spiral"
                        :description "Text can follow any path, including complex 3D curves like spirals."}}}}}

   :it
   {:sections
    {:part-1 {:title "Per Iniziare"}
     :part-2 {:title "Forme & Estrusione"}
     :part-3 {:title "Manipolazione Mesh"}
     :part-4 {:title "Funzionalità Avanzate"}
     :part-5 {:title "Aiuto al Debug"}
     :part-6 {:title "Clojure Avanzato"}}
    :pages
    {:hello-ridley
     {:title "Ciao Ridley"
      :content "Benvenuto in Ridley, un ambiente di modellazione 3D basato sulla grafica turtle.

In Ridley, crei oggetti 3D scrivendo codice. Il modo più semplice è con primitive come `box` e `cyl`. Ma il vero potere viene dall'**estrusione** — trascinare una forma 2D lungo un percorso per creare geometria 3D.

Vediamo entrambi gli approcci:"
      :examples
      {:first-cube {:caption "Un semplice cubo"
                    :description "Il comando `register` dà un nome alla tua forma così appare nel viewport. `box` crea un cubo della dimensione specificata."}
       :first-tube {:caption "La tua prima estrusione"
                    :description "`extrude` trascina una forma 2D (qui, un cerchio) lungo un percorso (qui, avanti di 30 unità). Questo crea un tubo."}}}

     :ui-overview
     {:title "L'Interfaccia"
      :content "Ridley ha tre aree principali:

**Editor** (sinistra) — Scrivi il tuo codice qui. È dove definisci forme, funzioni e geometria.

**Viewport** (destra) — La vista 3D che mostra i tuoi modelli. Puoi ruotare (trascinare), zoomare (scroll) e spostare (tasto destro) per esplorare.

**REPL** (in basso) — Una linea di comando interattiva. Scrivi espressioni e premi Invio per valutarle istantaneamente.

**Barra Editor:**
- **▶ Run** — Esegui il codice (come Cmd+Invio)
- **Save** — Scarica il codice come file .clj
- **Load** — Carica un file .clj dal disco
- **Manual** — Apri questo manuale

**Barra Viewport:**
- **Grid** — Mostra/nascondi la griglia di riferimento
- **Axes** — Mostra/nascondi le linee degli assi X/Y/Z
- **Turtle** — Mostra/nascondi l'indicatore turtle (posizione/direzione)
- **Lines** — Mostra/nascondi le linee di costruzione (tracce della penna)
- **Normals** — Mostra/nascondi le normali delle facce
- **Reset** — Reimposta la camera alla vista predefinita
- **Export STL** — Scarica le mesh visibili come file STL"}

     :editor-commands
     {:title "Comandi Editor"
      :content "**Editor:**
- **Cmd+Invio** (Mac) o **Ctrl+Invio** (Windows/Linux) — Esegui tutto il codice
- Clicca il bottone **▶ Run** per lo stesso effetto

**REPL:**
- **Invio** — Valuta la linea corrente
- **↑/↓** — Naviga la cronologia dei comandi

**Paredit** (editing strutturale):
Paredit mantiene le parentesi bilanciate automaticamente. Comandi principali:

- **Tab** — Indenta la linea corrente
- **(** o **[** o **{** — Inserisce automaticamente la parentesi di chiusura
- **Backspace** su parentesi vuote — Elimina la coppia
- **Ctrl+K** — Taglia fino a fine linea
- **Ctrl+Shift+K** — Taglia l'espressione che racchiude
- **Shift+Cmd+K** — Slurp: ingloba l'elemento successivo nelle parentesi
- **Shift+Cmd+J** — Barf: espelle l'ultimo elemento dalle parentesi

Esempio — Slurp: con cursore in `(+ 1 2|) 3`, Slurp → `(+ 1 2 3)`
Barf: con cursore in `(+ 1 2 3|)`, Barf → `(+ 1 2) 3`

**Ricerca:**
- **Cmd+F** / **Ctrl+F** — Cerca nell'editor
- **Cmd+G** / **Ctrl+G** — Trova successivo

**Annulla/Ripeti:**
- **Cmd+Z** / **Ctrl+Z** — Annulla
- **Cmd+Shift+Z** / **Ctrl+Shift+Z** — Ripeti"}

     :the-turtle
     {:title "La Tartaruga"
      :content "Ridley usa una **tartaruga** — un cursore invisibile che si muove nello spazio 3D. Mentre si muove, disegna linee che puoi vedere nel viewport.

La tartaruga ha:
- Una **posizione** nello spazio 3D
- Una **direzione** (dove sta guardando)
- Un vettore **up** (quale direzione è \"su\" per la tartaruga)

Comandi di movimento base:
- `(f distanza)` - muovi avanti
- `(th angolo)` - gira orizzontalmente (imbardata)
- `(tv angolo)` - gira verticalmente (beccheggio)
- `(tr angolo)` - rollio"
      :examples
      {:movement {:caption "Movimento base"
                  :description "Muovi avanti di 50 unità, gira di 90°, poi muovi ancora avanti. La tartaruga disegna linee visibili mentre si muove."}
       :spiral-path {:caption "Disegnare una spirale"
                     :description "`dotimes` ripete i comandi. La variabile `i` conta da 0 a 49, quindi ogni passo in avanti diventa più lungo, creando una spirale."}}}

     :clojure-basics
     {:title "Basi di Clojure"
      :content "Il codice Ridley usa la sintassi Clojure. Ecco cosa devi sapere:

**Parentesi** — Ogni comando è racchiuso tra parentesi: `(comando arg1 arg2)`

**def** — Crea un valore con nome: `(def nome valore)`

**defn** — Crea una funzione: `(defn nome [args] corpo)`

Questi ti permettono di costruire pezzi riutilizzabili e mantenere il codice organizzato.

**Per Approfondire**

Per imparare di più su Clojure, consulta queste risorse:

- [Clojure for the Brave and True](https://www.braveclojure.com/) — Un libro online gratuito, divertente e completo (in inglese)

- [ClojureDocs](https://clojuredocs.org/) — Riferimento rapido con esempi per tutte le funzioni core"
      :examples
      {:define-value {:caption "Definire un valore"
                      :description "Usa `def` per dare un nome a un valore. Qui definiamo `size` e lo usiamo per creare un cubo."}
       :define-function {:caption "Definire una funzione"
                         :description "Usa `defn` per creare funzioni riutilizzabili. Questa funzione `tube` prende raggio e lunghezza come parametri."}}}

     :extrude-basics
     {:title "Estrusione"
      :content "L'**estrusione** è il concetto centrale in Ridley. Prendi una forma 2D e la trascini lungo un percorso per creare geometria 3D.

```
(extrude forma movimenti...)
```

La **forma** definisce la sezione trasversale. I **movimenti** definiscono il percorso che la tartaruga segue. Mentre la tartaruga si muove, la forma viene trascinata lungo, creando un solido."
      :examples
      {:circle-extrude {:caption "Cerchio → Tubo"
                        :description "Un cerchio estruso in avanti diventa un tubo (cilindro)."}
       :rect-extrude {:caption "Rettangolo → Barra"
                      :description "Un rettangolo estruso in avanti diventa una barra rettangolare."}
       :bent-extrude {:caption "Percorso curvo"
                      :description "Il percorso può includere curve. Qui creiamo un tubo curvo girando di 45° a metà estrusione."}}}

     :builtin-shapes
     {:title "Forme Predefinite"
      :content "Ridley fornisce diverse forme 2D che puoi estrudere:

- `(circle raggio)` — un cerchio
- `(rect larghezza altezza)` — un rettangolo
- `(polygon n raggio)` — poligono regolare con n lati
- `(star punte esterno interno)` — una stella

Ogni forma diventa la sezione trasversale del tuo solido estruso."
      :examples
      {:circle-extrude {:caption "Cerchio"
                        :description "`circle` crea un cerchio con il raggio dato. Estruso, diventa un tubo."}
       :rect-extrude {:caption "Rettangolo"
                      :description "`rect` crea un rettangolo con larghezza e altezza. Estruso, diventa una barra."}
       :polygon-extrude {:caption "Poligono"
                         :description "`polygon` crea poligoni regolari con n lati. Un esagono (6 lati) estruso diventa una barra esagonale."}
       :star-extrude {:caption "Stella"
                      :description "`star` crea forme a stella. I parametri sono: punte, raggio esterno, raggio interno."}}}

     :custom-shapes
     {:title "Forme Personalizzate"
      :content "La macro `shape` ti permette di disegnare le tue forme 2D usando movimenti turtle. La tartaruga traccia un percorso chiuso, e quel percorso diventa la tua forma.

```
(shape movimenti...)
```

La forma si chiude automaticamente — l'ultimo punto si ricollega al primo."
      :examples
      {:triangle-shape {:caption "Triangolo"
                        :description "Tre movimenti avanti con curve di 120° creano un triangolo equilatero. Estruso, diventa un prisma."}
       :l-shape {:caption "Forma a L"
                 :description "Percorsi più complessi creano sezioni più complesse. Questa forma a L diventa una trave a L quando estrusa."}}}

     :reusable-shapes
     {:title "Forme Riutilizzabili"
      :content "Le forme sono solo dati — puoi memorizzarle in variabili e riutilizzarle.

```
(def mia-forma (circle 10))
(extrude mia-forma (f 30))
```

Allo stesso modo, i percorsi possono essere catturati con la macro `path` e riutilizzati in più estrusioni."
      :examples
      {:def-shape {:caption "Riutilizzare una forma"
                   :description "Definisci una forma una volta, usala più volte. Entrambi i tubi usano lo stesso cerchio ma lunghezze diverse."}
       :def-path {:caption "Riutilizzare un percorso"
                  :description "Anche i percorsi possono essere memorizzati e riutilizzati. La macro `path` cattura i movimenti turtle per uso futuro."}
       :follow-path {:caption "Disegnare un percorso"
                     :description "Usa `follow-path` per far tracciare alla tartaruga un percorso memorizzato, disegnando linee visibili nel viewport."}}}

     :extrude-closed
     {:title "Estrusione Chiusa"
      :content "`extrude-closed` crea forme dove il percorso forma un anello chiuso — come un toro o una cornice. L'ultimo anello si collega al primo, senza tappi alle estremità.

```
(extrude-closed forma movimenti...)
```

Il percorso deve ritornare (approssimativamente) alla posizione e orientamento iniziali."
      :examples
      {:square-torus {:caption "Toro quadrato"
                      :description "Un percorso quadrato con sezione circolare crea un toro con lati piatti."}
       :hex-ring {:caption "Anello esagonale"
                  :description "Un percorso esagonale con sezione esagonale. La forma viene trascinata attorno all'intero anello."}}}

     :shape-transforms
     {:title "Transizioni di Forma"
      :content "Il comando `loft` ti permette di fare transizioni tra forme diverse lungo il percorso di estrusione. La forma si trasforma gradualmente dall'inizio alla fine.

```
(loft forma-inizio forma-fine movimenti...)
```

Questo crea forme rastremate, transizioni organiche e geometrie complesse."
      :examples
      {:scale-shape {:caption "Cono rastremato"
                     :description "Loft da un cerchio grande a uno piccolo crea un cono rastremato."}
       :morph-shapes {:caption "Morphing di forme"
                      :description "Loft può fare transizioni tra forme completamente diverse — qui, da un quadrato a un cerchio."}}}

     :joint-modes
     {:title "Modalità Giunzione"
      :content "Quando un percorso di estrusione ha angoli (curve), il comando `joint-mode` controlla come viene generata la giunzione all'angolo.

```
(joint-mode :flat)    ; connessione diretta (default)
(joint-mode :round)   ; angolo arrotondato
(joint-mode :tapered) ; anello intermedio rastremato
```

La modalità giunzione influenza come la forma transita attraverso gli angoli, impattando sia l'estetica che la geometria."
      :examples
      {:joint-flat {:caption "Giunzione piatta"
                    :description "`:flat` connette gli anelli direttamente agli angoli. Semplice ma può mostrare spigoli."}
       :joint-round {:caption "Giunzione arrotondata"
                     :description "`:round` aggiunge anelli intermedi che curvano dolcemente attraverso l'angolo."}
       :joint-tapered {:caption "Giunzione rastremata"
                       :description "`:tapered` aggiunge un anello intermedio scalato, creando una transizione rastremata agli angoli."}}}

     :resolution-settings
     {:title "Risoluzione"
      :content "Il comando `resolution` controlla la levigatezza delle curve per archi, bezier e giunzioni arrotondate.

```
(resolution :n 32)  ; numero fisso di segmenti
(resolution :a 5)   ; angolo massimo per segmento (gradi)
(resolution :s 0.5) ; lunghezza massima del segmento (unità)
```

Puoi anche sovrascrivere la risoluzione per singolo comando usando l'argomento `:steps`:
```
(arc-h 20 90 :steps 16)
```"
      :examples
      {:resolution-n {:caption "Segmenti fissi"
                      :description "`:n` imposta un numero fisso di segmenti per le curve. Valori bassi = più veloce ma più grezzo."}
       :resolution-a {:caption "Basato sull'angolo"
                      :description "`:a` imposta i gradi massimi per segmento. Buono per levigatezza consistente indipendentemente dalla dimensione dell'arco."}
       :resolution-steps {:caption "Steps per comando"
                          :description "Usa `:steps` per sovrascrivere la risoluzione globale per un comando specifico."}}}

     :control-structures
     {:title "Strutture di Controllo"
      :content "Clojure fornisce diversi modi per controllare il flusso del programma:

**dotimes** — Ripeti n volte. Usa `_` quando non ti serve l'indice:
```
(dotimes [_ 4] corpo)   ; ripeti 4 volte
(dotimes [i 10] corpo)  ; i va 0,1,2...9
```

**for** — Crea una sequenza iterando su valori:
```
(for [x [1 2 3]] (f x))
```

**when** — Esegui solo se la condizione è vera:
```
(when (> x 5) (fai-qualcosa))
```

**if** — Scegli tra due rami:
```
(if condizione expr-then expr-else)
```"
      :examples
      {:dotimes-ignore {:caption "Ripetere senza indice"
                        :description "Usa `_` quando non ti serve il contatore. Questo disegna un quadrato ripetendo 4 volte."}
       :for-comprehension {:caption "Comprensione for"
                           :description "`for` itera su una collezione e ritorna un vettore. Qui registriamo 3 tubi con raggi diversi. Usa `(hide tubes 1)` per nascondere il secondo."}
       :when-conditional {:caption "Esecuzione condizionale"
                          :description "`when` esegue il corpo solo se la condizione è vera. Qui giriamo solo nelle iterazioni dispari."}}}

     :local-variables
     {:title "Variabili Locali"
      :content "La forma `let` crea variabili locali che esistono solo nel suo corpo:

```
(let [nome1 valore1
      nome2 valore2]
  corpo)
```

Le variabili possono dipendere da quelle precedenti nello stesso `let`. Questo mantiene il codice pulito ed evita ripetizioni."
      :examples
      {:let-basics {:caption "Let base"
                    :description "Definisci variabili locali `r` e `h`, poi usale per creare un cilindro."}
       :let-computed {:caption "Valori calcolati"
                      :description "Le variabili possono essere calcolate da quelle precedenti. Nota: in Clojure, operatori come `+`, `-`, `*`, `/` usano notazione prefissa: `(/ base 2)` invece di `base / 2`."}}}

     :repl-usage
     {:title "La REPL"
      :content "La **REPL** (Read-Eval-Print Loop) in fondo all'editor è il tuo spazio interattivo. Scrivi espressioni e premi Invio per valutarle immediatamente.

**Perché usare la REPL?**
- Testare piccoli pezzi di codice senza modificare le definizioni principali
- Ispezionare valori e mesh
- Esplorare funzioni Clojure interattivamente
- Debug valutando parti di espressioni

**Meccanica:**
- Scrivi un'espressione e premi **Invio** per valutare
- Usa le frecce **↑/↓** per navigare la cronologia dei comandi
- I risultati appaiono sopra la linea di input
- La REPL condivide lo stato con le tue definizioni — puoi accedere alle mesh registrate

**Comandi utili:**
```
(info :nome)      ; mostra info mesh (vertici, facce)
(get-mesh :nome)  ; recupera mesh per nome
(hide :nome)      ; nascondi mesh dal viewport
(show :nome)      ; mostra mesh nascosta
```"
      :examples
      {:repl-quick-test {:caption "Calcoli veloci"
                         :description "Usa la REPL come calcolatrice. Prova operazioni aritmetiche, stringhe o qualsiasi espressione Clojure."}
       :repl-println {:caption "Output stampato"
                      :description "L'output di `println` appare nella cronologia della REPL. Usalo per debuggare i tuoi script."}}}

     :mesh-basics
     {:title "Cos'è una Mesh"
      :content "Una **mesh** è un oggetto 3D fatto di vertici (punti nello spazio) e facce (poligoni che connettono i vertici). In Ridley, le mesh sono l'output finale delle operazioni di modellazione.

**La struttura dati mesh:**
- **vertices** — una lista di punti 3D `[x y z]`
- **faces** — una lista di indici di vertici che formano triangoli

**Registrare mesh:**
Il comando `register` dà un nome a una mesh e la aggiunge alla scena:
```
(register nome espressione-mesh)
```

**Lavorare con le mesh:**
```
(get-mesh :nome)   ; recupera mesh per nome (per op. booleane)
(info :nome)       ; mostra statistiche nella REPL
(hide :nome)       ; nascondi dal viewport
(show :nome)       ; mostra di nuovo
```

Tutte le primitive (`box`, `cyl`, `sphere`) e le estrusioni producono mesh."
      :examples
      {:mesh-register {:caption "Registrare una mesh"
                       :description "`register` crea una mesh con nome che appare nel viewport. Il nome diventa una keyword che puoi usare dopo."}
       :mesh-info {:caption "Ispezionare con info"
                   :description "`info` ritorna statistiche della mesh. Usa `println` per vedere l'output nella REPL."}
       :mesh-get-mesh {:caption "Usare get-mesh per operazioni"
                       :description "`get-mesh` recupera una mesh registrata per nome. Usalo per passare mesh alle operazioni booleane."}}}

     :boolean-operations
     {:title "Operazioni Booleane"
      :content "Le operazioni booleane combinano mesh in modi diversi. Usa `get-mesh` per recuperare una mesh dal suo nome registrato.

```
(mesh-union a b)        ; combina A e B
(mesh-difference a b)   ; sottrai B da A
(mesh-intersection a b) ; tieni solo dove A e B si sovrappongono
(mesh-hull a b c ...)   ; inviluppo convesso di tutte le mesh
```

Queste operazioni richiedono mesh **manifold** (a tenuta stagna, senza auto-intersezioni). Il risultato è una nuova mesh che puoi registrare."
      :examples
      {:bool-union {:caption "Unione"
                    :description "Combina due mesh in una. Le regioni sovrapposte vengono fuse."}
       :bool-difference {:caption "Differenza"
                         :description "Sottrai una mesh dall'altra. Crea fori, tagli ed effetti di intaglio."}
       :bool-intersection {:caption "Intersezione"
                           :description "Tieni solo il volume dove entrambe le mesh si sovrappongono. Crea forme a lente dalle sfere."}
       :bool-hull {:caption "Inviluppo Convesso"
                   :description "Crea la più piccola forma convessa che contiene tutte le mesh di input. Come avvolgerle in pellicola termoretraibile."}
       :bool-union-for {:caption "Unione con for"
                        :description "Le operazioni booleane accettano vettori, quindi puoi usare `for` per generare più mesh e combinarle in una sola chiamata."}}}

     :attach-meshes
     {:title "Attaccare Mesh"
      :content "La funzione `attach` crea una mesh nella posizione e orientamento corrente della tartaruga. La macro `clone` crea una copia di una mesh esistente alla posizione della tartaruga.

```
(attach mesh)    ; posiziona mesh alla posizione turtle
(clone mesh)     ; crea una copia alla posizione turtle
```

A differenza di `register`, questi creano geometria che segue la trasformazione corrente della tartaruga."
      :examples
      {:attach-basic {:caption "Attach base"
                      :description "La base è registrata all'origine. Il cilindro è attaccato con movimenti che lo posizionano relativamente alla tartaruga."}
       :attach-boolean {:caption "Attach per booleane"
                        :description "`attach` può essere usato inline per posizionare una mesh relativamente a un'altra per operazioni booleane."}
       :attach-clone {:caption "Clonare una mesh"
                      :description "Usa `clone` per creare copie di una mesh registrata esistente in posizioni diverse."}
       :attach-clone-rotated {:caption "Clone con rotazione"
                              :description "I cloni possono includere rotazioni. Qui `tv` inclina e `tr` ruota la tartaruga prima di posizionare la copia."}}}

     :attach-faces
     {:title "Attaccare alle Facce"
      :content "La funzione `attach-face` attacca geometria a una faccia specifica di una mesh. Usa keyword (`:top`, `:bottom`, `:front`, `:back`, `:left`, `:right`) o indici 0-5.

```
(attach-face mesh :top body)
```

La tartaruga viene posizionata al centro della faccia, orientata verso l'esterno. Usa `inset` per rimpicciolire la faccia prima di estrudere:

```
(inset dist)  ; sposta ogni vertice di dist unità verso il centro
```

Valori positivi rimpiccioliscono la faccia, valori negativi la espandono."
      :examples
      {:attach-face-frustum {:caption "Tronco (attach-face)"
                             :description "`attach-face` modifica la faccia originale. `inset` la rimpicciolisce, `f` la sposta verso l'esterno. Crea un tronco di piramide."}
       :clone-face-spike {:caption "Punta (clone-face)"
                          :description "`clone-face` crea nuovi vertici. `inset` crea una faccia interna più piccola, `f` la estrude. Crea una punta."}}}

     :debug-panels
     {:title "Pannelli 3D di Testo"
      :content "I **pannelli** sono display di testo 3D che fluttuano nel viewport. Guardano sempre verso la camera (effetto billboard) e sono perfetti per il debug o per annotare i tuoi modelli.

**Creare pannelli:**
```
(register P1 (panel larghezza altezza))
```

**Scrivere sui pannelli:**
- `(out P1 \"testo\")` — sostituisce il contenuto
- `(append P1 \"testo\")` — aggiunge al contenuto
- `(clear P1)` — cancella il contenuto

**Posizionare pannelli:**
Usa `attach` per posizionare i pannelli relativamente alla turtle:
```
(register P1 (attach (panel 50 30) (f 20)))
```

I pannelli NON vengono esportati in STL — sono solo per visualizzazione."
      :examples
      {:panel-basic {:caption "Pannello base"
                     :description "Crea un pannello e scrivi del testo. Il pannello appare alla posizione corrente della turtle."}
       :panel-attach {:caption "Pannello posizionato"
                      :description "Usa `attach` per posizionare il pannello relativo alla geometria. Il pannello fluttua vicino al cubo."}
       :panel-multiline {:caption "Testo multi-riga"
                         :description "Usa `append` con `\\n` per aggiungere nuove righe. Costruisci messaggi complessi incrementalmente."}}}

     :debug-techniques
     {:title "Tecniche di Debug"
      :content "Quando il codice non funziona come previsto, queste tecniche ti aiutano a capire cosa sta succedendo.

**La funzione T (tap):**
`T` è un helper di debug built-in che stampa un valore con un'etichetta e lo restituisce invariato:
```
(T \"etichetta\" valore)  ; stampa \"etichetta: valore\", ritorna valore
```

Inserisci `T` ovunque per vedere i valori intermedi senza cambiare il comportamento. La sua implementazione è semplicemente:
```
(defn T [label x] (println label \":\" x) x)
```
Puoi definire una tua variante se necessario.

**Ispezione della turtle:**
- `(turtle-position)` — posizione [x y z] corrente
- `(turtle-heading)` — vettore direzione corrente
- `(turtle-up)` — vettore up corrente

**Esecuzione passo-passo:**
Aggiungi istruzioni `println` tra le operazioni per vedere la sequenza degli eventi. Questo aiuta a identificare quale passo causa problemi.

**Problemi comuni:**
- Mesh non visibile? Controlla `(hide :nome)` / `(show :nome)`
- Posizione sbagliata? Stampa lo stato della turtle prima di creare geometria
- Forma inaspettata? Semplifica e testa ogni parte separatamente"
      :examples
      {:tap-function {:caption "La funzione T"
                      :description "`T` è built-in. Stampa il valore con un'etichetta, poi lo restituisce invariato. Qui vediamo la dimensione calcolata (30) nell'output della REPL."}
       :inspect-turtle {:caption "Ispezionare lo stato turtle"
                        :description "Stampa posizione e direzione della turtle in qualsiasi punto per capire dove verrà creata la geometria."}
       :step-by-step {:caption "Debug passo-passo"
                      :description "Aggiungi println tra le operazioni per tracciare l'esecuzione. L'output appare nella cronologia della REPL."}}}

     :mark-goto-pushpop
     {:title "Marcatori e Stack di Stato"
      :content "Quando crei geometrie complesse, spesso devi tornare a posizioni precedenti o diramarti da un punto. Ridley fornisce due meccanismi:

**Ancore con Nome (mark/goto):**
- `(mark :nome)` — Salva posizione, direzione e vettore up correnti con un nome
- `(goto :nome)` — Teletrasportati a un'ancora nominata (disegna una linea se la penna è attiva)

Le ancore sono persistenti e nominate — usale quando devi tornare a punti specifici più volte o navigare tra posizioni distanti.

**Stack di Stato (push-state/pop-state):**
- `(push-state)` — Metti lo stato corrente della turtle su uno stack anonimo
- `(pop-state)` — Ripristina lo stato più recentemente salvato

Lo stack è perfetto per le diramazioni: push-state prima di un ramo, crea la geometria, pop-state per tornare, poi dirama di nuovo. È come un annulla per la posizione della turtle.

**Differenza chiave:**
- `mark`/`goto` = segnalibri nominati per la navigazione
- `push-state`/`pop-state` = stack anonimo per pattern di diramazione"
      :examples
      {:mark-goto-basic {:caption "Mark e goto"
                         :description "Marca una posizione, spostati per creare qualcosa, poi goto ti riporta al punto marcato. La torre è costruita esattamente dove abbiamo marcato :top."}
       :push-pop-branch {:caption "Diramazioni con push-state/pop-state"
                         :description "Pattern classico ad albero: push-state prima di ogni ramo, pop-state per tornare al tronco. Entrambi i rami originano dallo stesso punto."}
       :multiple-anchors {:caption "Ancore multiple"
                          :description "Crea più ancore nominate in un ciclo, poi naviga a qualsiasi di esse dopo. Nota: `(keyword (str \"pos\" i))` costruisce keyword dinamicamente (:pos0, :pos1, ecc.)."}}}

     :arcs-beziers
     {:title "Archi e Curve di Bezier"
      :content "Le curve morbide sono essenziali per forme organiche. Ridley fornisce comandi arc e bezier che si integrano con l'estrusione.

**Arco Orizzontale:**
```
(arc-h raggio angolo)      ; gira sinistra/destra mentre ti muovi
(arc-h raggio angolo :steps 16)
```
Angolo positivo = gira a sinistra, negativo = gira a destra. La turtle finisce a distanza `raggio × angolo_rad`, con direzione ruotata di `angolo` gradi.

**Arco Verticale:**
```
(arc-v raggio angolo)      ; beccheggia su/giù mentre ti muovi
(arc-v raggio angolo :steps 16)
```
Angolo positivo = beccheggia su, negativo = beccheggia giù.

**Curve di Bezier:**
```
(bezier-to [x y z])                    ; punti di controllo automatici
(bezier-to [x y z] [c1])               ; quadratica (1 controllo)
(bezier-to [x y z] [c1] [c2])          ; cubica (2 controlli)
(bezier-to-anchor :nome)               ; bezier verso ancora nominata
```

`bezier-to` senza punti di controllo crea una curva tangente alla direzione corrente.

`bezier-to-anchor` è più intelligente: rispetta **sia** la direzione corrente CHE la direzione salvata nell'ancora, creando una curva a S morbida che onora entrambe le direzioni."
      :examples
      {:arc-h-basic {:caption "Arco orizzontale (inversione a U)"
                     :description "`arc-h 30 180` crea un arco di 180° con raggio 30 — un'inversione a U nel piano orizzontale. Combinato con segmenti rettilinei."}
       :arc-v-basic {:caption "Arco verticale (loop)"
                     :description "`arc-v 25 180` crea un mezzo-loop nel piano verticale, come passare sopra una collina."}
       :bezier-auto {:caption "Bezier automatica verso ancora"
                     :description "Marca start, spostati, marca end, poi vai a start ed estrudi con `bezier-to-anchor`. La curva rispetta sia la direzione di partenza che la direzione salvata nell'ancora."}
       :bezier-control {:caption "Bezier con punti di controllo"
                        :description "Punti di controllo espliciti danno controllo preciso. Qui `[0 20 30]` e `[0 40 -30]` creano una curva a S."}}}

     :colors-materials
     {:title "Colori e Materiali"
      :content "Ogni mesh può avere le proprie proprietà di colore e materiale. Impostale **prima** di creare la geometria — si applicano a tutte le mesh create successivamente.

**Solo colore:**
```
(color 0xff0000)        ; rosso (hex RGB)
(color 255 0 0)         ; rosso (valori RGB 0-255)
```

**Controllo completo del materiale:**
```
(material :color 0x8888ff
          :metalness 0.8    ; 0-1, quanto metallico
          :roughness 0.2    ; 0-1, quanto liscio/ruvido
          :opacity 1.0      ; 0-1, trasparenza
          :flat-shading true)
```

Quando chiami `material`, le opzioni non specificate tornano ai default. Per resettare tutto:
```
(reset-material)
```

Linee guida PBR (Physically Based Rendering):
- **Metalli** (oro, cromo): metalness 0.8-1.0, roughness 0.1-0.4
- **Plastiche**: metalness 0.0-0.1, roughness 0.3-0.7
- **Superfici opache**: metalness 0.0, roughness 0.8-1.0"
      :examples
      {:color-basic {:caption "Impostare colori"
                     :description "Usa `color` per impostare rapidamente il colore per la geometria successiva. Ogni mesh ricorda il colore che era attivo quando è stata creata."}
       :material-pbr {:caption "Materiali PBR"
                      :description "Controllo completo sull'aspetto del materiale. Alta metalness + bassa roughness = metallo lucido. Bassa metalness + alta roughness = plastica opaca."}
       :material-reset {:caption "Reset ai default"
                        :description "`reset-material` riporta tutte le proprietà del materiale all'aspetto blu predefinito."}}}

     :text-shapes
     {:title "Forme di Testo"
      :content "Converti testo in forme 2D usando il font Roboto integrato.

**Uso base:**
```
(text-shape \"Hello\")           ; dimensione default
(text-shape \"Hello\" :size 30)  ; testo più grande
```

**Estrudi per testo 3D:**
```
(register sign (extrude (text-shape \"RIDLEY\" :size 20) (f 5)))
```

**Estrusione rapida con `extrude-text`:**
```
(extrude-text \"Hello\" :size 25 :depth 4)
```
Scorciatoia che estrude il testo alla posizione della turtle, fluendo lungo l'heading.

**Testo su percorso:**
```
(def mio-arco (path (arc-h 50 180)))
(text-on-path \"CURVO\" mio-arco :size 12 :depth 3)
```

Opzioni per `text-on-path`:
- `:align` — `:start`, `:center`, o `:end`
- `:spacing` — spaziatura extra tra lettere (default 0)
- `:overflow` — `:truncate`, `:wrap`, o `:scale`

**Font integrati:**
- `:roboto` — Roboto Regular (default)
- `:roboto-mono` — Roboto Mono (monospace)

**Carica font personalizzato:**
```
(load-font! \"/path/to/font.ttf\")  ; restituisce una promise
```"
      :examples
      {:text-basic {:caption "Testo 3D semplice"
                    :description "Estrudi forme di testo per creare lettere 3D. L'opzione :size controlla la dimensione del font in unità mondo."}
       :text-large {:caption "Segnaletica"
                    :description "Testo più grande per insegne e titoli. Regola :size e profondità estrusione (f 3) per le proporzioni desiderate."}
       :extrude-text {:caption "Estrusione rapida"
                      :description "`extrude-text` è una scorciatoia conveniente: il testo fluisce lungo l'heading della turtle e viene estruso perpendicolarmente."}
       :text-on-arc {:caption "Testo su arco"
                     :description "Posiziona testo lungo un percorso curvo. Ogni lettera segue la tangente alla curva. Usa `:align :center` per centrare il testo sul percorso."}
       :text-on-spiral {:caption "Testo su spirale"
                        :description "Il testo può seguire qualsiasi percorso, incluse curve 3D complesse come spirali."}}}}}})

;; Helper to find a page in the structure
(defn- find-page-structure [page-id]
  (some (fn [section]
          (some (fn [page]
                  (when (= (:id page) page-id)
                    (assoc page :section-id (:id section))))
                (:pages section)))
        (:sections structure)))

;; Get parent section for a page
(defn get-parent-section
  "Get the section ID that contains the given page.
   Returns nil for the :toc page."
  [page-id]
  (when (not= page-id :toc)
    (some (fn [section]
            (when (some #(= (:id %) page-id) (:pages section))
              (:id section)))
          (:sections structure))))

;; Get the "up" destination for a page
(defn get-up-destination
  "Get the destination for the Up button.
   - For pages within a section: go to :toc
   - For :toc: returns nil (no up)"
  [page-id]
  (when (not= page-id :toc)
    :toc))

;; Check if page is TOC
(defn toc-page?
  "Check if page-id is the table of contents."
  [page-id]
  (= page-id :toc))

;; Get full structure for TOC rendering
(defn get-toc-structure
  "Get the full manual structure for table of contents."
  []
  (:sections structure))

;; Get merged page data (structure + i18n)
(defn get-page
  "Get page data merged with i18n content for the given language.
   Returns nil if page not found."
  [page-id lang]
  (when-let [page-struct (find-page-structure page-id)]
    (let [lang-data (get-in i18n [lang :pages page-id])
          ;; Fall back to English if translation missing
          fallback-data (when (and (not= lang :en) (nil? lang-data))
                          (get-in i18n [:en :pages page-id]))
          text-data (or lang-data fallback-data)
          ;; Merge examples with their i18n captions
          examples (mapv (fn [ex]
                           (merge ex (get-in text-data [:examples (:id ex)])))
                         (:examples page-struct))]
      (merge page-struct
             (dissoc text-data :examples)
             {:examples examples}))))

;; Get section title
(defn get-section-title
  "Get the title of a section in the given language."
  [section-id lang]
  (or (get-in i18n [lang :sections section-id :title])
      (get-in i18n [:en :sections section-id :title])
      (name section-id)))

;; Get all pages for navigation
(defn get-all-pages
  "Get a flat list of all page IDs in order."
  []
  (mapcat (fn [section]
            (map :id (:pages section)))
          (:sections structure)))

;; Get next/previous page
(defn get-adjacent-page
  "Get the next or previous page ID. Direction is :next or :prev."
  [current-page direction]
  (let [pages (vec (get-all-pages))
        idx (.indexOf pages current-page)]
    (when (>= idx 0)
      (case direction
        :next (get pages (inc idx))
        :prev (when (pos? idx) (get pages (dec idx)))
        nil))))

;; Get see-also links for a page
(defn get-see-also
  "Get the list of 'see also' page IDs for a given page.
   Returns nil if no see-also links defined."
  [page-id]
  (when-let [page-struct (find-page-structure page-id)]
    (:see-also page-struct)))
