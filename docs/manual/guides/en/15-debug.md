# 15. Focusing in and troubleshooting

<!-- level: intermediate -->

When a model does not do what you expect, you need tools to understand what is happening. Ridley offers several, from the simplest (printing a value in the console) to the more sophisticated (interactive tweaking with sliders, 3D panels positioned in the scene). This chapter gathers them in one place, with references to the chapters where they were already introduced.

## 15.1 3D text panels

Panels are text billboards positioned in the 3D scene. They are the most visual debug tool: you can place a panel next to a piece and have it show the values you care about.

<!-- example-source: panels
;; Create a panel at the turtle's current position
(register debug (panel 40 20))

;; Write something
(out :debug "Hello World")

;; Add text
(append :debug "\nVertices: 1234")

;; Clear the content
;(clear :debug)
-->

The style options:

<!-- example-source: panels2
(register debug (panel 56 15
                  :font-size 8
  :bg 0xff3333cc        ;; semi-transparent background (cc, the low byte, is the alpha)
  :fg 0xffffff          ;; white text
  :padding 2
  :line-height 1.4))
(out :debug "Your Ad Here")
-->

Panels behave like meshes: they support `show`/`hide`, `register`, `attach`/`attach!`. You can position them with `attach` next to the piece you are debugging.

A useful pattern: a panel that shows the dimensions of a piece while you build it:

<!-- example-source: panel-showing-mesh-details
(def W 10)
(def H 20)
(def D 40)

(register part (box W H D))
(register info-panel (attach (panel 30 15) (f -40)))
(out :info-panel
  (str "W=" W " D=" D " H=" H
       "\n" (:n-faces (mesh-diagnose part)) " faces"))
-->

## 15.2 Debugging techniques

### Println and the console

The most direct way: `println` prints in the browser's console (and in Ridley's output panel):

<!-- example-source: println :no-run
(def m (box 20))
(println "vertices: " (:n-verts (mesh-diagnose m)))
(println "manifold? " (manifold? m))
(println "bounds: " (bounds m))
-->

Do not underestimate `println`. For most problems ("why does this boolean fail?", "how big is this piece?", "is the creation-pose where I think?") a targeted `println` is faster than any sophisticated tool.

### T: printing a value without interrupting the flow

`T` is a debug helper that prints a value with a label and returns it unchanged. It serves to peek at a value in the middle of an expression without having to break it.

<!-- example-source: tap
(register cube (box (T "size" (* 10 3))))
-->

It prints `size : 30` in the console and still passes `30` to `box`, so the cube is created normally. Since it returns the value it receives, you can wrap any subexpression with `T` leaving the rest of the code intact: it is the fastest way to discover which of the many computed values is the wrong one.

### Inspecting the turtle's state

When the doubt is not about a value but about where the turtle is, `turtle-position`, `turtle-heading`, and `turtle-up` return the current position, advance direction, and up vector. Print them to verify that the geometry will be born where you think.

<!-- example-source: turtle-inspect :no-run
(f 50)
(println "pos:" (turtle-position))
(th 45)
(println "heading:" (turtle-heading))
(println "up:" (turtle-up))
-->

### Show/hide to isolate

When the scene is crowded and you do not understand which piece is causing problems, hide everything except the one you are examining:

```clojure
(hide-all)
(show :suspect-piece)
```

Or the opposite: hide a piece to see what is underneath:

```clojure
(hide :outer-shell)
```

`show-all` returns everything to visibility. `objects` lists the visible names, `registered` lists all the names in the registry.

### Transparency to see inside

If hiding a piece is too drastic, make it transparent:

```clojure
(material :outer-shell :opacity 0.3)
```

You see through the outer shell without losing it from view. Remember that opacity does not survive export.

### Info and bounds

`info` returns a summary of a registered mesh:

```clojure
(info :my-piece)
;; => {:name :my-piece :visible true :vertices 1234 :faces 2468 :bounds {...}}
```

`bounds` returns the bounding box, useful to verify that a piece is where you think:

```clojure
(bounds :my-piece)
;; => {:min [x y z] :max [x y z] :center [x y z] :size [sx sy sz]}
```

The functions `height`, `width`, `depth`, `top`, `bottom`, `center-x`, `center-y`, `center-z` extract individual dimensions from the bounding box.

### Highlighting faces

To understand which face you are selecting with `find-faces` or `face-at`:

```clojure
(flash-face my-mesh :top 2000 0x00ff00)    ;; green highlight, 2 seconds
(highlight-face my-mesh :top)              ;; permanent highlight
(clear-highlights)
```

Covered in detail in section 7.4.

## 15.3 Interactive tweaking

`tweak` is the tool for exploring the parameters of an expression with real-time sliders. It evaluates the expression, shows the result in the viewport, and creates sliders for the literal numbers in the code.

<!-- example-source: tweak1 :no-run
;; No filter: sliders for every literal number (15 and 30) — same as :all
(register t (tweak (extrude (circle 15) (f 30))))
-->

Sliders for all the numbers
<!-- example-source: tweak2 :no-run
(register tube (tweak :all (extrude (circle 15) (f 30) (th 90) (f 20))))
-->

```clojure
;; Slider for a specific index (0-based, depth-first left-to-right)
(tweak 2 (extrude (circle 15) (f 30) (th 90) (f 20)))    ;; tweaks 90

;; Negative index (from the last)
(tweak -1 (extrude (circle 15) (f 30) (th 90) (f 20)))   ;; tweaks 20
```

The slider starts with a range centered on the current value (`[value * 0.1, value * 3]`). The zoom buttons (`-`/`+`) narrow or widen the range. OK confirms and prints the final expression in the REPL, Cancel (or Esc) restores.

### Tweak on registered meshes

When you pass a keyword, `tweak` operates on the registered mesh: it hides the original during tweaking, re-registers the result on OK, restores the original on Cancel.

```clojure
(register A (extrude (circle 15) (f 30)))

;; Tweak using the source form saved by register
(tweak :A)
(tweak :all :A)

;; Tweak with an explicit expression
(tweak :all :A (extrude (circle 20) (f 40)))
```

`(tweak :A)` works because `register` automatically saves the source form. If the form contains calls to user-defined functions (`(register A (make-a 1))`), those functions must already be defined at the moment of the tweak.

### Edit-attach: interactive positioning with gizmo and keyboard

`edit-attach` opens a modal session in which you reposition and deform an existing mesh. A gizmo appears on the mesh's creation-pose: three translation arrows and three rotation rings oriented along the turtle's axes, plus three stretch handles. Every drag becomes a turtle command (`f`, `rt`, `u`, `th`, `tv`, `tr`, `stretch-*`) that appears in the side panel, with the effect visible in the viewport in real time. When you confirm, `edit-attach` rewrites itself in the source as an `attach` containing the accumulated sequence of commands; Esc cancels and leaves just the starting expression.

<!-- example-source: edit-attach :no-run
(register part (edit-attach (box 20)))
-->

The call goes where its value is used, here inside `register`: on confirm the `edit-attach` rewrites itself into an `attach` in place, and the form becomes `(register part (attach (box 20) ...))`. A bare `(edit-attach ...)` at top level edits a copy whose result is never consumed, and the work is lost.

The keyboard remains as the precision layer, with the three historical modes (vim-style): movement (`f`/`b`/`rt`/`lt`/`u`/`d`), rotation (`th`/`tv`/`tr`), scale (`stretch-f`/`stretch-rt`/`stretch-u`). Drags snap to the same grid as the keyboard steps (the step/angle/scale parameters in the panel); holding Shift the movement is free.

The object/origin toggle in the panel changes the meaning of the gestures: in origin mode you move the creation-pose relative to the geometry (the `cp-*` family from ch. 8), and a click on a mesh vertex snaps the pose exactly there. It is the quick way to place the anchor, and therefore the pivot of rotations and stretches, on a meaningful point of the part.

An already-written `attach` is re-editable: the "Edit" entry of the context menu on the form (or the `edit-` prefix added by hand), the existing commands enter the panel untouched and the new gestures append. `pilot`, the old name of this session, still works as an alias. The keys and operational details are in the Reference.

### A third session: edit-bezier

`edit-bezier` uses the same modal mechanism as `tweak` and `edit-attach` to draw a cubic Bezier curve from the keyboard, and on confirm it rewrites it to source as `bezier-to`. Since it is first of all a tool for curves, it is covered in § 11.2.

## 15.4 Where the tools live

Some of the tools you reach for while debugging have their home in other chapters. The reminder, in one table:

| Tool | What it is for when investigating | Home |
|---|---|---|
| `stamp` | seeing a 2D profile before extruding or lofting it | § 3.2 |
| `follow-path` | seeing a path as a trace, without building geometry | § 5.2 |
| `ruler`, `distance`, `bounds`, Shift+Click | measures and dimensions, interactive too | ch. 10 |
| `mesh-diagnose`, `manifold?` | understanding why a mesh is not healthy | § 7.7 |
