# 16. Clojure for Ridley

<!-- level: base -->

Ridley uses a subset of Clojure as its language. If you come from Python, JavaScript, or a traditional CAD, the parenthesized syntax can seem alien. This chapter covers the minimum needed to write Ridley models without having to read a book on Clojure. If you already know Clojure or another Lisp, you can skip straight to chapter 17.

## 16.1 Control structures

The most important thing to understand about Clojure is not the parentheses, but the fact that everything is an expression that returns a value. An `if` does not "do" something: it returns the value of the true branch or the false branch. A `let` does not "declare variables": it returns the value of the last expression in its body. A `for` does not "loop": it returns a vector of results.

If you come from Python, JavaScript, or C, you are used to thinking in terms of statements that *do things* (assign, print, modify). In Clojure you think in terms of expressions that *produce values*. This difference shows up everywhere:

```clojure
;; In an imperative language you would think:
;; "if the width is large, create a box, otherwise create a cylinder"
;;
;; In Clojure you say:
;; "the result is a box or a cylinder, depending on the width"
(register piece
  (if (> width 50)
    (box width width 10)
    (cyl (/ width 2) 10)))
```

The `register` receives directly the value returned by `if`. There is no need for an intermediate variable or a conditional assignment. This pattern repeats throughout Ridley code: the control structures live inside the expressions, not around them.

There are exceptions. `register` itself is one: it does not return a value, but stores a mesh in the registry and shows it in the viewport. `def` is another: it creates a global variable for its effect, not for its return value. `println`, `color` (in the global form), `sdf-resolution!`, `attach!` are all commands that *do things*. In Clojure they call these "side effects", and they are not forbidden: they are just the minority. Most of the code you will write produces values and passes them to other expressions.

### If, when, cond, case

`if` has two branches: true and false. You already saw it in the example above. The form is `(if condition value-if-true value-if-false)`.

`when` is an `if` without the false branch. Useful when you want to compute a value only if a condition is true (if it is false it returns nil, a special value that means `absence of value`):

```clojure
(when (> n 0)
  (register holes
    (concat-meshes
      (for [i (range n)]
        (attach (cyl 3 20) (th (* i (/ 360 n)) (f 15)))))))
```

`cond` is a chain of conditions:

```clojure
(cond
  (< r 5)   (sphere r)
  (< r 15)  (cyl r 20)
  :else     (box r r r))
```

`case` compares a value with constants:

```clojure
(case shape-type
  :round (cyl 10 20)
  :square (box 20 20 20)
  :hex (cyl 10 20 6)
  (box 10 10 10))            ;; default
```

### For: generating sequences

`for` is a list comprehension, not an imperative loop. It produces a vector of results:

```clojure
;; 12 cylinders in a circle
(for [i (range 12)]
  (attach (cyl 3 20) (th (* i 30)) (f 40)))
```

The result is a vector of 12 meshes. To make a single one, wrap it in `concat-meshes` or `mesh-union`.

`for` accepts multiple bindings and filters:

```clojure
;; 5x5 grid with a filter
(for [x (range 5)
      y (range 5)
      :when (not= x y)]
  (attach (box 4 4 4) (rt (* x 10)) (u (* y 10))))
```

### Dotimes: repeating N times

`dotimes` runs a block N times. Unlike `for`, it does not produce a sequence: it is for side effects (moving the turtle, registering meshes).

```clojure
(dotimes [i 6]
  (register (symbol (str "post-" i)) (cyl 3 30))
  (f 20))
```

### Loop/recur: controlled recursion

For more complex iterations where `for` is not enough:

```clojure
(loop [n 5 r 20]
  (when (> n 0)
    (register (symbol (str "ring-" n)) (cyl r 3))
    (f 8)
    (recur (dec n) (* r 0.8))))
```

`loop` defines the initial bindings, `recur` jumps to the start of the loop with new values. It is the only idiomatic way to do a loop with mutable state in Clojure.

## 16.2 Let and local variables

`let` creates local variables. The bindings are name-value pairs inside square brackets:

```clojure
(let [r 15
      h (* r 2)
      base (cyl r h)
      cap (attach (sphere r) (f h))]
  (register piece (mesh-union base cap)))
```

The variables live only inside the `let`. Outside, `r`, `h`, `base`, `cap` do not exist.

The bindings are sequential: a binding can refer to the previous ones (`h` uses `r`, `base` uses `r` and `h`).

### Def and defn: global definitions

`def` creates a global variable:

```clojure
(def wall-thickness 2)
(def bolt-r 3)
```

`defn` creates a function with a name:

```clojure
(defn pillar [r h]
  (mesh-union
    (cyl r h)
    (attach (sphere (* r 1.2)) (f h))))
```

Functions are called like everything else in Clojure: name and arguments inside parentheses.

```clojure
(register p1 (pillar 5 40))
(register p2 (pillar 8 60))
```

## 16.3 Anonymous functions

Anonymous functions are useful when you need a small function to pass to `map`, `filter`, `for`, or a shape-fn, without giving it a name.

The extended form:

```clojure
(fn [x] (* x 2))
```

The compact form (the `#()` reader macro):

```clojure
#(* % 2)            ;; one argument: % is the argument
#(+ %1 %2)          ;; two arguments: %1 and %2
```

A practical example with `map`:

```clojure
;; Double all the radii
(def radii [5 8 12 15])
(def doubled (map #(* % 2) radii))
;; => (10 16 24 30)
```

With shape-fns:

```clojure
;; Custom taper
(register tapered-tube
  (loft (circle 20)
    #(scale-shape %1 (- 1 (* 0.5 %2)))
    (f 60)))
```

Here `%1` is the shape and `%2` is `t` (the progress along the path, from 0 to 1).

## 16.4 Map, filter, reduce

### Map: transforming a sequence

`map` applies a function to each element of a sequence:

```clojure
(def sizes [10 15 20 25])
(map #(cyl % 30) sizes)      ;; => (cyl-10 cyl-15 cyl-20 cyl-25)
```

### Filter: selecting elements

`filter` keeps only the elements that satisfy a predicate:

```clojure
(def marks (keys (anchors skel)))
(def mid-marks (filter #(re-find #"^mid-" (name %)) marks))
```

### Reduce: accumulating a result

`reduce` combines the elements of a sequence with a two-argument function:

```clojure
;; Sum
(reduce + [1 2 3 4 5])    ;; => 15

;; Progressive union of meshes
(reduce mesh-union meshes)
```

For booleans, the variadic form (`(mesh-union [a b c])`) is usually clearer than `reduce`. But `reduce` is useful when the combination operation is not a boolean:

```clojure
;; Apply a list of transformations in sequence
(reduce (fn [m transform] (transform m))
        (box 20)
        [#(chamfer % :all 2)
         #(color % 0xff0000)])
```

## 16.5 Using the REPL

The REPL (Read-Eval-Print Loop) is the command line at the bottom of the editor. You can write expressions and see the result immediately, without pressing Cmd+Enter to re-run the whole program.

### Inline eval vs Cmd+Enter

Cmd+Enter (or the Run button) re-runs all the code in the editor from scratch: the scene is cleared and rebuilt. It is the normal way to work when you write the program.

The REPL runs a single expression in the current context, without clearing the scene. You can inspect values, try operations, add temporary meshes, all without touching the main program.

```clojure
;; In the REPL:
(bounds :my-piece)                    ;; how big is it?
(manifold? (mesh :my-piece))          ;; is it watertight?
(mesh-diagnose (mesh :my-piece))      ;; full diagnosis
(flash-face (mesh :my-piece) :top)    ;; where is the top face?
```

### Useful things in the REPL

`(objects)` lists the visible meshes. `(registered)` lists everything in the registry. `(info :name)` gives the summary of a mesh. `(bounds :name)` gives the bounding box.

`(tweak :name)` opens interactive tweaking on a registered mesh. `(pilot :name)` opens interactive positioning.

`(export :name)` exports a mesh. `(export :a :b :3mf)` exports to multi-material 3MF.

`(hide :name)`, `(show :name)`, `(hide-all)`, `(show-all)` control visibility.

The REPL expressions do not modify the code in the editor. If you find a value you like (a radius, a position), write it by hand into the program. The exception is `tweak` and `pilot`, which can rewrite the source when you confirm.
