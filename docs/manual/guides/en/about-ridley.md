# What Ridley is

Ridley is a CAD (Computer Aided Design) program. In plain terms, it is for drawing three-dimensional objects, mostly intended for 3D printing.

There are already dozens of programs that do this, so why build a new one? Ridley has a number of features that set it apart from other approaches.

## Text-based

Most programs of this kind offer an interaction that lets you work directly on the 3D object as you design it. Moving it, changing its dimensions or other aspects, is done by clicking the object and dragging the mouse, or by picking entries from context menus that appear once you select the object or one of its parts (faces, edges, vertices). Creating the object itself is mouse-based too: dragging elementary shapes from a palette, for example.

None of this happens in Ridley: you see the object but you did not create it, and you cannot edit it, with the mouse. The 3D image you see is the result of running a program, which you write and edit in the left panel of the interface (the editor). This layer of mediation between you and the model can feel like a cost, especially if you come from mouse-oriented programs, but it turns out to be one of Ridley's greatest strengths in sustained use.

The text that makes up the program is handled like any other text: you open it in any editor, version it, search inside it, compare different versions to see what changed, email it, keep the history of the project. All things that are hard or impossible with other approaches.

Ridley is not the only CAD that makes this choice. The best known in this category is OpenSCAD: if you come from there you will find a cousin, but a cousin that can do a bit more.

## Clojure-based

Clojure is a modern dialect of Lisp, a family of programming languages as old as it is rare to meet in a CAD. For Ridley the choice is deliberate and has a precise consequence: the functions you write are part of the language on the same footing as the built-in commands. There is no privileged standard library on one side and user code on the other. Everything lives on the same plane.

Concretely: the verbs that make up Ridley are no different in form from the language's own verbs, and you can create your own, all the way to building a custom language tailored to your needs. You start by writing `(box 20)` and you end up, with no perceptible boundary, writing `(my-frame 80 60 12)`, which behaves exactly like a primitive.

And this does not hold for objects only: in Ridley, functions are building material. A shape-fn is a function that makes a profile vary along the path, a thickness-fn is a function that modulates the thickness of a wall, a custom SDF is a distance formula written by you. The boundary between using Ridley and extending it simply is not there.

Then there is a quieter quality: Clojure's syntax is almost all substance. A model reads like a list of operations, with no ceremony and no decorative punctuation; the same model, written in a conventional language, would be longer and noisier.

Everything in Ridley is data you can look inside: shapes, paths, and meshes are ordinary Clojure structures, inspectable from the REPL. And the data carries its own history: a mesh extruded along a path remembers the notable points (the *marks*) annotated on that path, and those points can be fished back out of the mesh long after it was created, even after it has been through boolean operations.

Finally, the deepest part of the Lisp inheritance: the language's control forms are not a closed set, new ones can be created. `on-anchors` is the best example: it takes the marks traveling inside a path or a mesh and turns them into a phrase of the language ("for every point with this role, build this piece here"): an expressive form that a language outside the Lisp family could hardly have offered.

## Turtle-based

Turtle Geometry is a paradigm introduced by a programming language created to teach children to program, called Logo. The basic idea is to imagine giving movement commands to a turtle (drawn schematically on screen). Commands like "go forward 5 units", "turn right 90 degrees". In response to these commands the Logo turtle moved across the screen leaving a trace of its path (a "pen up" or "pen down" command let you move while drawing the trace, or simply reposition without drawing).

This approach lets you make geometric drawings without resorting to external coordinate systems: the turtle always carries its own frame of reference, it has its own front and back, its own right and left. The program "repeat 4 times: forward 10, turn left 90" produces a square wherever the turtle happens to be.

Ridley takes this concept and expands it into three dimensions: the turtle can go forward and back, move sideways or up and down, and can rotate horizontally, vertically, or around its own axis (roll). It has become a little airplane moving through space, or a sea turtle (the name Ridley comes from a species of these creatures) swimming in the sea. In Ridley the turtle concept is used in many areas: the turtle can trace paths, which can be recorded and used to remember important points, to create two-dimensional shapes from which solids are derived, or to set how a two-dimensional shape should be swept to produce a solid. Turtle commands serve to move objects, to modify their faces, and much more.

Much of programming in Ridley comes down to sitting at the controls of a turtle and flying it around the model as it takes shape.
