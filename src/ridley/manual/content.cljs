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
         :code "(def corner (path (f 30) (th 90) (f 30)))\n\n(register pipe\n  (extrude (circle 8) corner))"}]}
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
         :code "(println \"Hello from REPL!\")\n(println \"2 + 2 =\" (+ 2 2))"}]}]}
    {:id :part-4
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
         :code "(register spike\n  (clone-face (box 30) :top\n    (inset 10) (f 30)))"}]}]}]})

;; Internationalization - text content for each language
(def i18n
  {:en
   {:sections
    {:part-1 {:title "Getting Started"}
     :part-2 {:title "Shapes & Extrusion"}
     :part-3 {:title "Clojure Advanced"}
     :part-4 {:title "Mesh Manipulation"}}
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

These let you build reusable pieces and keep your code organized."
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
                  :description "Paths can also be stored and reused. The `path` macro captures turtle movements for later use."}}}

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
                          :description "`clone-face` creates new vertices. `inset` creates a smaller inner face, `f` extrudes it. Creates a spike."}}}}}

   :it
   {:sections
    {:part-1 {:title "Per Iniziare"}
     :part-2 {:title "Forme & Estrusione"}
     :part-3 {:title "Clojure Avanzato"}
     :part-4 {:title "Manipolazione Mesh"}}
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

Questi ti permettono di costruire pezzi riutilizzabili e mantenere il codice organizzato."
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
                  :description "Anche i percorsi possono essere memorizzati e riutilizzati. La macro `path` cattura i movimenti turtle per uso futuro."}}}

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
                          :description "`clone-face` crea nuovi vertici. `inset` crea una faccia interna più piccola, `f` la estrude. Crea una punta."}}}}}})

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
