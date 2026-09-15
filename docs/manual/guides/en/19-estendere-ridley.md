<!--
Translated from it/19-estendere-ridley.md (2026-09-15). Keep in sync: same
section structure, same examples, same example-source ids. Code examples
identical to the IT source. No em-dash.
-->

# 19. Extending Ridley

<!-- level: advanced -->

The previous chapters explain how to use Ridley. This one how to extend it: it spells out the contracts the language's functions keep with each other, so that yours keep the same ones, and it opens the hood on how the code you write gets evaluated. You do not need it for the first hundred objects. You need it when a shape-fn of yours does something strange, when you want to generate the scene from code, or when a library you wrote does not behave as you expected.

## 19.1 The shape-fn contract

Chapter 6 shows how to write a shape-fn with `shape-fn` and how to compose it with the built-ins. Here is what chapter 6 does not say: what a shape-fn really is, what the loft expects from it, and the mistakes that can keep it from working.

### A function with a label

A shape-fn is a Clojure function `(fn [t] -> shape)` carrying the metadata `{:type :shape-fn}`. The metadata is everything: it is what `loft` and `revolve` check to decide whether they are looking at a static shape (an extrusion with a constant profile) or a shape to re-evaluate at every ring. `shape-fn?` does nothing but read it:

```clojure
(shape-fn? (fn [t] (circle 20)))            ; false: a bare function has no metadata
```

This is the trap. A lambda that returns a shape is not a shape-fn, because it has no label: the loft does not recognise it as a varying profile. To build a real one you go through `shape-fn`, which puts the label on and also records `:base`, the starting shape (or shape-fn). Nobody checks the number of points in the profile: if your transformation changes it, the loft only notices when it tries to connect rings with a different number of vertices. Chapter 6's rule (same number of points in and out) is a contract, not advice.

### t, and when it does not reach 1

`t` is the fraction of the path: 0 on the first ring, 1 on the last, and the number of rings in between is decided by `loft-n` (or the global resolution). On a path with corners and short segments the last ring may stop at a `t` slightly below 1: that is why `loft+` returns as `:end-face` the section actually stamped on the last ring, not a re-evaluation of the shape-fn at `t=1`. If you chain geometry by hand, use that one.

With `revolve` the shape-fn is evaluated at every step of the revolution, with `t` equal to the fraction of the angle swept. On a full turn the last ring coincides with the first, so the profile at `t = 1` is never built: with 64 steps the last value is 63/64. On a partial revolution `t` does reach 1, and that ring is the closing face. Before the rings, the profile is evaluated once at `t = 0` as a probe, to count the points.

### Thinking in millimetres: `current-path-length`

`t` is a fraction, and for many transformations that is enough: "halfway along, the profile is twice as big" does not need to know how long the path is. When instead the shape depends on a measurement, say a bulge ten millimetres long whatever the length of the path, you need to convert `t` into millimetres, and you ask `(current-path-length)` for the total: inside a loft it is the length of the path, inside a `revolve` the length of the arc swept by the profile's centroid (angle times radius).

```clojure
;; a bulge 10 mm long at the start, whatever the path length
(register bulge
  (loft (shape-fn (circle 12)
          (fn [s t]
            (let [mm (* t (or (current-path-length) 1))]
              (scale-shape s (+ 1 (* 0.4 (max 0 (- 1 (/ mm 10)))))))))
        (path (f 40) (arc-h 20 90) (f 30))))
```

The `or` is there because outside a loft or a revolve the function returns `nil` (and `revolve` evaluates the profile once at `t = 0` before starting, as a probe to count the points, when the length is not known yet): a shape-fn must be evaluable there too without breaking.

### Composition, and what passes through

`(-> shape (A ...) (B ...))` builds a shape-fn B whose `:base` is the shape-fn A: when the loft asks B for a given `t`, B first asks A at the same `t` and transforms the result. Execution runs from the inside out, and every ring re-evaluates the whole chain (there is no memoisation: a long chain on a `loft-n 256` costs in proportion).

One detail of the contract that chapter 6 states only as a rule: `shell` and `woven-shell` do not transform the points, they **annotate** the shape with extra keys (`:shell-mode`, `:shell-thickness`, `:shell-values`, and `:shell-offsets` for the woven one) that the loft reads to build the double ring, outer and inner. Every built-in preserves those keys, because it transforms the points with `update` and leaves the rest alone: `tapered`, `twisted`, `fluted`, `capped` after a `shell` produce the same double-ring mesh. The reason `shell` still sits well at the end is about meaning, not mechanism: the thickness values are computed on the profile `shell` receives, one per point, and a deformation applied afterwards (`fluted`, `noisy`) moves the points while leaving the values where they were. If you write a shape-fn of your own, do the same: `update` `:points` (and `:holes`) on the shape you receive instead of building a new one, and the keys you do not know about pass through. The one built-in that does not enter a chain is `morphed`: it takes two static shapes, and with a shape-fn as first argument it throws.

### The partial form

`(tapered :to 0.5)`, with no shape in front, does not return a shape-fn: it returns the bare transformation, `(fn [shape t] -> shape)`, with no metadata. It is the form `loft` accepts as second argument next to a static shape, and the one `loft+` accepts inside a `transform->` where the profile comes from the previous step. The predicate sees it as any other function (`(shape-fn? (tapered :to 0.5))` is `false`), and `shape-fn` refuses it with an error that says so: as base it wants a shape or a shape-fn, because a partial form has no profile to start from. If you want to compose a transformation of yours with a built-in, give `shape-fn` the full form, `(tapered (circle 20) :to 0.5)`.

### The helpers you have

Besides the shape operations listed in chapter 6, inside a transformation you have the helpers meant for this work: `angle`, which gives the angle in radians of a point relative to the origin (not the centroid: if the profile is not centred, subtract the centre first); `displace-radial`, which moves every point along the direction from the centroid by an offset computed by a function `(fn [point] -> number)`, holes included; `noise` and `fbm`, deterministic, continuous noise in two variables (`fbm` with optional octaves, lacunarity and gain); `sample-heightmap`, which samples a heightmap at `[u v]` with bilinear interpolation and wrapping at the edges. Chapter 6 uses almost all of them in its examples; here the signatures matter:

```clojure
(angle [x y])                        ; radians, atan2 y x
(displace-radial shape (fn [p] ...)) ; radial offset per point, holes included
(noise x y)                          ; ~[-1 1], deterministic, smooth
(fbm x y)  (fbm x y octaves)  (fbm x y octaves lacunarity gain)
(sample-heightmap hm u v)            ; bilinear, wraps at the edges
(smoothstep e0 e1 x)                 ; 0 below e0, 1 above e1, Hermite ramp between
(shape-centroid shape)               ; [cx cy] of the outer contour
(current-path-length)                ; mm of the sweep, nil outside a loft or revolve
```

Put together, they make a shape-fn chapter 6 does not have: bark, that is a noise that follows the angle around the profile and flows along the path, applied as a radial displacement:

<!-- example-source: extend-bark -->
```clojure
(register bark
  (loft-n 48
    (shape-fn (circle 15 96)
      (fn [s t]
        (displace-radial s
          (fn [p] (* 2 (fbm (* 3 (angle p)) (* 8 t) 3))))))
    (f 60)))
```

`angle` gives `fbm` a coordinate that turns around the profile, `t` scaled gives one that climbs along the path, and `displace-radial` applies the value as an outward (or inward, if negative) displacement without changing the number of points. The two extra functions, `smoothstep` and `shape-centroid`, serve thickness-fns above all, and we meet them in the next section. Other tools the built-ins use for themselves (the signed distance from a polygon, each point's arc-length fraction) stay private: if you need them, you rewrite them in a few lines in a library, which is the right place to accumulate such tools (19.5).

## 19.2 The thickness-fn contract

Chapter 6 (6.11) shows how to draw a pattern in `(angle, t)` coordinates. Here is the exact definition of those coordinates and of what happens to the value you return.

### The domain

A thickness-fn for `shell` has the signature `(fn [a t] -> number)`. `a` is the angle of the profile point relative to the **centroid of the current shape**, computed with `atan2` (the centre is what `shape-centroid` returns): it therefore runs from **-π to π** (upper end included), with zero on the positive X axis and the jump from π to -π on the opposite side. A pattern periodic in `a` does not notice (`(sin (* a 8))` is continuous across the jump), but a pattern that uses `a` as a linear coordinate, `(mod (* a 3) 1)` say, gets a seam there: if you want an axis that runs from 0 to 1 all the way round, compute it yourself with `(/ (+ a PI) (* 2 PI))`. `t` is the fraction along the path, as for shape-fns.

The centroid is that of the shape **at that `t`**: if the thickness-fn follows a `tapered` or a `noisy`, the centre the angle is measured from moves with the shape, not with the original profile. For a symmetric profile nothing changes; for an asymmetric one it is the difference between a pattern that "turns" with the shape and one fixed in space.

First of all, something the circles in the examples do not show: the thickness-fn is evaluated **once per profile point**, and between one point and the next the thickness is interpolated. A `(circle 20 64)` has 64 points and is fine; a `(rect 40 12)` has four, the corners, so `shell` would decide the thickness in four places only and the pattern would not exist. A polygonal profile must be resampled first, with `resample-shape`, to at least twice as many points as the pattern's frequency.

Then the coordinate. The angle is good on a circle and bad on everything else: on a 40 by 12 rectangle, one degree around the centroid covers few millimetres on the short sides and many on the long ones, and twelve slots "every 30 degrees" come out narrow and dense on the short sides, wide and sparse on the long ones. If you want a constant pitch in millimetres, the right coordinate is the **arc length** along the perimeter, and you get it by passing `:style :pattern` together with your `:fn`: with that style the first coordinate the function receives is no longer the angle but the fraction of the perimeter travelled, from 0 to 1.

<!-- example-source: extend-thickness-arclength -->
```clojure
;; twelve slots of equal width all around a rectangle
(register slotted
  (loft (shell (resample-shape (rect 40 12) 208) :thickness 2 :style :pattern
          :fn (fn [u t] (if (< (mod (* u 12) 1) 0.6) 1 0)))
        (f 30)))
```

Try removing `:style :pattern` and reading `a` instead of `u` (with `(/ (+ a PI) (* 2 PI))` to bring it back between 0 and 1): the slots are still twelve, but no longer equal. And try removing `resample-shape`: the four-point frame comes back.

### The value

What you return is processed in three steps, in this order: if you passed `:invert? true`, the value becomes `1 - v` (this applies to a `:fn` of yours too, not only to the styles); if it is below `:threshold` (default 0.05) it becomes 0, that is an opening; then it is clamped to `[0, 1]`. A value above 1 does not make a wall thicker than `:thickness`: it is clamped to 1. If you want walls of different thickness, the scale is `:thickness`, and the function modulates between 0 and 1.

`:softness` applies to a `:fn` of yours too, but you have to ask for it: without it, the openings follow the loft's grid, in steps; with `:softness` greater than zero the loft cuts the triangles along the 0.5 iso-line of your field, and the edges come out smooth. For this to work the field must be continuous: a function that returns only 0 and 1 has no iso-lines to follow. That is the use case for `smoothstep`: `(smoothstep 0.4 0.6 v)` turns a hard threshold into a ramp as wide as you like, and the ramp is what the smooth cut follows.

### woven-shell

`woven-shell` has a different contract: the function returns a map `{:thickness v :offset mm}`. `:thickness` is the coefficient from before, with the same threshold but **without** the clamp to 1 (the two threads of a weave add up where they cross, and the built-in produces values above 1 on purpose); `:offset` is the radial displacement of the wall's centre, in millimetres, positive outwards. The offset is what creates the over and under of the weave: two threads with the same `:thickness` and opposite offsets pass one in front of the other.

`embroid` accepts a `:fn` too, with a signature of its own: `(fn [u t] -> 0..1)`, where `u` is the arc-length fraction along the wall and `t` the sweep; 1 is a strut, 0 an opening, cut on the 0.5 iso-line. The styles' options `:margin`, `:border` and `:softness` do not apply to a function: if you want the panel attached to its neighbours, return 1 near the edges.

### A function to reuse

The cleanest way to write thickness-fns is as functions that return functions, with the parameters closed over. That way the pattern goes into a library and is called by name:

<!-- example-source: extend-thickness-factory -->
```clojure
(defn stripes
  "Horizontal bands: n bands along the sweep, `fill` the solid fraction."
  [n fill]
  (fn [a t] (if (< (mod (* t n) 1) fill) 1 0)))

(defn helix
  "Diagonal bands: `turns` full turns along the sweep."
  [n turns fill]
  (fn [a t]
    (let [u (/ (+ a PI) (* 2 PI))]        ; a in [-PI PI] -> u in [0 1]
      (if (< (mod (+ (* u n) (* t turns)) 1) fill) 1 0))))

(register banded
  (loft (shell (circle 20 64) :thickness 2 :fn (stripes 10 0.5)) (f 60)))
(f 60)
(register helical
  (loft (shell (circle 20 64) :thickness 2 :fn (helix 6 2 0.5)) (f 60)))
```

Note the computation of `u` in `helix`: it is the line that makes the pattern indifferent to the `atan2` jump.

## 19.3 Registering and inspecting

`register` is the normal way to put something in the scene. Underneath it there is a registry, that is a table of named objects, and a small group of functions to read and write it from a program. They are for whoever generates the scene from code instead of one line per object: a script that produces twenty parts with computed names, a library that registers a whole assembly, a piece of code that has to look at what is already there.

### Writing to the registry

`register` is a macro: it looks at what you pass it and calls the right function. The functions are exposed, and they are the ones to use when the name is computed:

```clojure
(register-mesh! name mesh)     ; a mesh, visible in the scene
(register-shape! name shape)   ; a 2D shape, by name only
(register-path! name path)     ; a path, by name only
(register-value! name value)   ; any other value (a map of parameters, say)
(add-mesh! mesh)               ; an anonymous mesh: in the scene, without a name
```

`name` is a keyword. `register-mesh!` and `add-mesh!` both put a mesh in the scene; the difference is whether you want to find it again. With a name the mesh can be looked up (`get-mesh`), shown and hidden, re-evaluated with `tweak`, exported by name; without a name it is only in the scene, which is fine for background geometry nobody will ever query, a hundred decorative studs that do not deserve a hundred names, say. The difference between `register` and `register-mesh!`, on the other hand, is not only syntactic: the macro also captures the form that produced the mesh (which is what lets `tweak :name` re-evaluate it with different parameters), the function does not. If you register from code, that link is not there.

<!-- example-source: extend-register-loop -->
```clojure
;; a row of pegs of growing height, each with its own name
(doseq [i (range 5)]
  (register-mesh! (keyword (str "peg-" i))
                  (attach (cyl 3 (+ 5 (* 4 i))) (rt (* 12 i)))))
```

### Reading the registry

```clojure
(get-mesh :peg-2)          ; the registered mesh
(get-shape :profile)       ; a registered shape
(get-path :spine)          ; a registered path
(registered-names)         ; every mesh name
(shape-names) (path-names) ; the other two registers
(visible-names)            ; the meshes currently shown
(all-meshes-info)          ; per mesh: name, visibility, vertex and face counts
```

These are the functions a piece of code uses to work on what a previous script put in the scene, for example to merge into a single part everything with a given prefix:

```clojure
(register all-pegs
  (mesh-union
    (vec (map get-mesh
              (filter #(clojure.string/starts-with? (name %) "peg-") (registered-names))))))
```

Next to them are the visibility functions (`show-mesh!`, `hide-mesh!`, `show-all!`, `hide-all!`, `show-only-registered!`), the function forms of `show` and `hide`, and `refresh-viewport!`, which is only needed if you mutate the registry outside the normal Run cycle.

### What produced a mesh

Every mesh that went through `register` carries two pieces of metadata: `:source-history`, the chronological log of the operations that produced it, and `:source-form`, the quoted form that gave rise to it. They are the data the inspector and `tweak` read. From code they are reached from the current selection in the viewport:

```clojure
(selected)                 ; {:mesh :face :name :origin :last-op ...} or nil
(selected-name)            ; the registry name, nil if anonymous
(last-op (selected))       ; the last operation recorded on it
(source-of (selected))     ; the whole history
(get-source-form :peg-2)   ; the quoted form that produced it
```

This is material for whoever builds tools on top of Ridley more than for whoever models; we mention it because it exists and is stable.

### Hooks: animations and collisions

`anim!` and `anim-proc!` (the animation macros, described in the Spec) are macros over `anim-register!` and `anim-proc-register!`; `anim-make-cmd` and `anim-make-span` build the pieces the macros emit. They serve whoever generates animations from data, for example a sequence of spans computed from a table, where the macro would be awkward. `on-collide` registers a callback `(fn [evt])` that fires when two named meshes touch during playback, `off-collide` removes it, `list-collisions` and `clear-collisions` manage the list. The full signatures are in the Spec, Internals section.

### Single or collection

One last regularity worth knowing when writing general code: many Ridley functions accept indifferently a single value or a vector of values of the same type. `text-shape`, `slice-mesh`, `project-mesh` and `shape-xor` return vectors of shapes; `extrude`, `loft`, `revolve`, `stamp` and `shape-offset` accept them as they are, and an extrusion of a vector of shapes produces a single mesh, already fused, ready for booleans. A function of yours that wants to be as convenient does the same: check `(sequential? x)` (or `shape?` on the argument) and map over itself.

## 19.4 The path as data

Chapter 5 uses paths. Here we see what they look like inside.

### Commands, not points

A path is a map: `{:type :path :commands [...]}`, where each command is in turn a map `{:cmd :f :args [30]}`. What `path` records are the **instructions** you gave the turtle, not the points that result:

```clojure
(path->data (path (f 30) (th 90) (arc-h 10 90)))
;; => {:type :path,
;;     :commands [{:cmd :f, :args [30]} {:cmd :th, :args [90]} {:cmd :arc-h, :args [10 90], :steps 64}]}
```

`path->data` is the printable form: the real path also carries a cache of the tessellated commands, which consumers recompute on their own if missing, and the marks. Every `(mark :name)` also appears as a top-level key of the map, with its pose in the path's frame: `(path (f 30) (mark :here))` has `(:here p)` equal to `{:position [30 0 0] :heading [1 0 0] :up [0 0 1]}`. `path?` answers `true` to both forms.

The points are computed later, by whoever consumes the path: `extrude` interprets it with one sample per waypoint, `loft` with the steps you tell it (`loft-n`), `path-to-shape` traces it in the plane. That is why the same path can be sampled at different resolutions without rebuilding it: the resolution does not live in the path, it lives in the consumer. This holds for curves too: `arc-h`, `bezier-to` and `bezier-as` enter the path as analytic commands (angle and radius, control points), with one declared exception: the number of steps a curve will be tessellated into is decided when you record it (`:steps`), not by the consumer.

From this follows something useful: a path can be built from data, not only written by hand.

### Generated paths

The most direct route is `poly-path`, which takes coordinate pairs in a flat vector. A profile computed by a function becomes a path with a `mapcat`:

<!-- example-source: extend-path-from-data -->
```clojure
(defn sampled
  "A closed 2D outline: the curve (f x) for x in [x0 x1], n samples,
   closed by a flat base at y = base."
  [f x0 x1 n base]
  (poly-path
    (vec (concat
           (mapcat (fn [i]
                     (let [x (+ x0 (* (- x1 x0) (/ i (dec n))))]
                       [x (f x)]))
                   (range n))
           [x1 base x0 base]))))

(register wave-plate
  (extrude (path-to-shape (sampled #(* 8 (sin (/ % 6))) 0 60 40 -12) :preserve-position true)
           (f 3)))
```

The second route is a loop inside `path`: `dotimes` and `for` work in the recorder as anywhere else, so a spiral path is one line that changes the radius at every turn (that is how the shell in `examples/spiral-shell.clj` is made):

```clojure
(def spiral
  (path (dotimes [i 36]
          (arc-h (+ 10 (* i 0.8)) 30))))
```

### Composing and transforming

Paths compose by splicing, not by concatenation: `(follow other-path)` inside a `path` inserts the other's commands at the point where you are, and `side-trip` runs a sub-path that at the end puts the turtle back where it found it, keeping however the marks the sub-path left behind. The transformation functions return new paths, without touching the original: `reverse-path` walks it backwards, `mirror-path` reflects it (across the plane normal to the final tangent, or a normal you supply), `add-mark` inserts a mark at a fraction of the length, `subpath-y` clips out a band, `offset-x` and `fit` move and scale it. The classic recipe for a symmetric curve is to draw half of it and close with `(follow-path (reverse-path (mirror-path half)))` inside the `path` that completes it.

What is missing, and not by oversight, is access to the sampled points: the path does not have them. If you need the position of a notable point, put a mark there and read it with `mark-pos`; if you need the traced shape, go through `path-to-shape` and look at the shape's `:points`, which is made of points.

## 19.5 Libraries under the hood

Chapter 9 says how to use a library. Here is what really happens when you press Run, and what follows for the code you write in one.

### An interpreter, not a compiler

The code you write in Ridley is read as a string and evaluated by SCI, a Clojure interpreter written in ClojureScript that runs in the browser alongside Ridley. There is no compilation, no external process. At the start of every evaluation the interpreter receives two things: the map of the language's symbols (a few hundred functions, from `f` to `mesh-union`) and the active libraries. That is all your code can see.

The context is closed by choice. There is no JavaScript interop (`js/...` does not exist), no dynamic `require`, no access to the DOM or the network. Where Ridley needs the browser (loading a font, opening a dialog, reading an SVG) it does so inside a language function, and you see a pure function. `clojure.core` is there in full, `clojure.string` too; the mathematics is what Ridley exposes (`sin`, `cos`, `atan2`, `pow`, `sqrt`, `PI`...), in radians, while the turtle commands are in degrees: you write the conversion. The rest of Clojure is there: `defmacro`, `atom` with `swap!` and `reset!`, `defmulti`/`defmethod`, `defprotocol`/`defrecord`, `letfn`, `try`/`catch`, `ex-info`; `clojure.string` and `clojure.set` are called by their full name, there is no predefined `str/` alias. Two things to know. An `atom` lives from one REPL command to the next, but at every Run the context is rebuilt and it restarts from its initial value. And `defmulti` has `defonce` semantics: on a name already defined, `area` say, which is a Ridley function, it does nothing, and the following `defmethod` blows up with an unhelpful message; pick a new name.

Many Ridley functions are in fact macros: `extrude`, `path`, `register`, `loft`, `anim!`. They are defined inside the interpreter at every start and expand over your code: `path` opens a scope in which `f` and `th` record instead of moving, `register` looks at the type of what you pass and picks the registrar, `loft` decides what to do from the shape of its arguments. To your code they are functions that work; knowing this only matters when you try to pass them as values (`(map extrude ...)` makes no sense: a macro is not a function) or when a library wants to redefine them (it cannot: a library builds on top of the language, it does not change it).

### Run and REPL

Every Run (Cmd+Enter in the definitions panel) rebuilds the interpreter from scratch: it loads the active libraries, defines the macros, resets the turtle and the scene, and evaluates your whole buffer. The REPL instead reuses the interpreter of the last Run: it sees the `def`s and `defn`s the Run left behind, keeps the turtle's pose from one command to the next, but clears the geometry at every command, so you see only the last command's output. The consequences: a `def` made in the REPL survives until you press Run; a `def` in the buffer is redone at every Run; nothing survives closing the application, except what lives in a workspace or a library.

### What a library is, to the interpreter

A library is a file of code with a name and a list of dependencies. At every Run, for each active library and in the right order, Ridley creates a temporary interpreter with the language plus the libraries already loaded, evaluates the library's source, extracts the public names and reads them back one by one. The result is an SCI namespace with the library's name, and in your buffer its symbols are called `name/symbol`. Ridley does the `require` for you.

Three details that explain as many surprising behaviours:

- **Public names are found by form, not by evaluation.** Ridley looks in the source for lines beginning (whitespace aside) with `(def `, `(defn ` or `(defonce `. `defn-` and `^:private` are excluded, as in Clojure; so is a `def` produced by a macro, which is not in the source. A `defmacro` works inside the library but does not cross the prefix: the namespace receives values, and a macro is not one. If a function of your library "is not there", check first how its line is written.
- **A name found but never defined disappears silently.** After evaluating the source, Ridley reads back every name the search found; if a `def` sits somewhere the evaluation did not execute (inside a `comment`, in a branch not taken), that name is skipped without an error. An error in the library's source, on the other hand, skips the whole library with a warning in the panel.
- **Top-level code is executed.** A library is not only definitions: if it contains a `register` outside any function, that geometry enters the scene on every Run in which the library is active. It is almost always unintended; a clean library defines and nothing else, and leaves registering to whoever calls it.

The library's name is a string, not a hierarchy: `robot/arm` is a library called that, not a `robot` library with a sub-namespace. The prefix cannot be shortened or renamed.

### Order, dependencies, reloading

Dependencies are declared in the file's header, as a comment, and Ridley orders them topologically before loading: a library that requires another is evaluated after it, and sees its symbols with the prefix. A dependency that is not active, or a cycle, skips the library with a warning; activation is not automatic, you activate both. There is no cache: all active libraries are re-evaluated at every Run, even if they have not changed, and with many active libraries a minimal Run can be felt. It is the price of an always-fresh interpreter, in which a library is always exactly its current source.

Where the files live, and how they are exchanged, chapter 9.6 says. The header Ridley reads is two comment lines:

```clojure
;; Ridley Library: shapes
;; Requires: utils, robot/arm
```

### What follows

From all this, the practical consequences for a library's code. Only `def`, `defn` and `defonce` at the head of their line become public. Top-level code is executed at every Run: a `register` or a turtle movement outside any function enters the scene every time the library is active, so put them there only if that is what you want. Inside the library the language's names are the usual ones (`f` is `f`), the prefix only tells yours apart in the buffer. And a function that depends on the turtle's current pose inherits it from the caller, not from a state of its own: the library has none that survives the Run.
