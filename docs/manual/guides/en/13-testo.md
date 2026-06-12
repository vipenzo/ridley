# 13. Text

<!-- level: intermediate -->

Text in Ridley is geometry. There is no separate annotation system: the letters are 2D shapes that you can extrude, combine with booleans, place along a path, or use as a heightmap to create reliefs. This chapter covers the three modes: text as a 2D shape, text as extruded 3D geometry, and text as a raised displacement.

## 13.1 2D text shapes

### Text-shape

`text-shape` converts a string into a vector of 2D shapes, one for each outer outline found in the text:

```clojure
(text-shape "Hello")                ;; default font (Roboto), default size
(text-shape "RIDLEY" :size 40)      ;; larger size
```

<!-- example-source: text-shape-basic
(stamp (text-shape "Hello" :size 30))
-->

One thing that can be surprising is *where* the text appears relative to the turtle, and it is worth fixing the logic once and for all, because the three text functions follow different but coherent conventions. `text-shape` produces the shape on the plane orthogonal to the heading, but — unlike `rect`, which is born *centered* on the turtle — it uses the turtle's pose as the *writing line*: the text develops upward and to the right starting from that point, as you would write on a sheet by setting the pen down at the bottom left. If you need the text centered on the turtle (like a primitive shape), pass `:center true`: the shape is centered on both axes relative to the real bounding box of the glyphs — handy for rotating the text around its own center or aligning it to another piece.

The example shows the difference by placing each version next to a `rect` (which is always centered on the turtle). At the top, `:center true`: the text is centered on the rectangle. At the bottom, the default: the text starts from the bottom-left corner and develops upward and to the right.

<!-- example-source: text-shape-center
(stamp (text-shape "Hello" :size 30 :center true))
(stamp (rect 90 30))
(u 100)
(stamp (text-shape "Hello" :size 30))
(stamp (rect 90 30))
-->

The result is not a shape per character, but a shape per *outer outline*. Most characters have a single outer outline, but composite characters have more: `i` and `j` have two (body + dot), `ä` and `ö` have three (body + two dots). The inner holes (the void inside the `o`, the `a`, the `B`) are automatically attributed to the smallest outer outline that contains them.

For the shapes of the individual characters (without the automatic composition of the outlines):

```clojure
(text-shapes "ABC" :size 20)        ;; vector of shapes, one per character
```

`char-shape` produces the shape of a single character, and accepts a font id (see below):

```clojure
(char-shape "A" :roboto-mono 20)    ;; single character with a specific font
```

### Font

The default font is Roboto Regular. `text-shape`, `text-shapes`, `extrude-text`, and `text-on-path` use it automatically without your having to pass anything.

To use a different font, you refer to it through its **id** (a keyword). Two fonts are always available in every version of Ridley, web included: `:roboto` and `:roboto-mono`. You pass them to the text functions with the `:font` option:

<!-- example-source: text-font-builtin
(register mono-title (extrude-text "RIDLEY" :size 40 :font :roboto-mono))
-->

A font is not an object to "load" before use: it is a registered resource, cited by id — like a mesh in the registry or a marker in a path. You pass the keyword where needed, and Ridley resolves the id internally.

**Custom fonts: only in the desktop version.** The two built-ins are the only fonts available in the web version. To use additional fonts you need the desktop app, where you register them once from the Settings → Fonts panel: you choose a file (`.ttf`, `.otf`, `.woff`, `.woff2`) and assign it an id. From that moment the font is available in all projects with that id, and the registration persists between sessions (like libraries). In code you use it like the built-ins:

```clojure
(extrude-text "RIDLEY" :size 40 :font :my-font)
```

If you pass an unregistered id, the error is immediate and clear: `Font id :x is not registered. Add it in Settings → Fonts.` No waiting, no "try again": either the id is there, or it is not. Keep in mind that a model using a custom font works only where that id has been registered — so on the desktop app of whoever added that font.

### Measuring text

`text-width` returns the horizontal width a string will occupy once rendered. It accepts the font id:

<!-- example-source: text-width :no-run
(println (text-width "Hello" :roboto-mono 20))    ;; => width in units at size 20
-->

Useful for layout: centering text along a path, sizing a backing plate, computing spacing.

## 13.2 Text as extruded geometry

### Manual extrude

You can pass the vector of shapes from `text-shape` directly to `extrude`:

<!-- example-source: text-extrude
(register title (extrude (text-shape "RIDLEY" :size 40) (f 5)))
-->

`extrude` combines the extrusions of the individual shapes into a single mesh, ready for booleans.

### Extrude-text: the shortcut

`extrude-text` combines `text-shape` and `extrude` in a single call:

```clojure
(extrude-text "RIDLEY")                            ;; default: size 10, depth 5
(extrude-text "RIDLEY" :size 40 :depth 3)          ;; customized
(extrude-text "RIDLEY" :size 40 :font :roboto-mono);; specific font by id
```

<!-- example-source: extrude-text
(register title (extrude-text "RIDLEY" :size 40 :depth 3))
-->

Like `text-shape`, it uses the turtle's pose as the writing line (the text starts there, not centered) and extrudes in depth. It returns a single mesh containing all the glyphs, ready to be combined with booleans or passed to `attach`.

### Text along a path

`text-on-path` places 3D text along a curved path:

<!-- example-source: text-on-path
(def curve (path (dotimes [_ 40] (f 2) (th 3))))
(register curved-text (text-on-path "Hello World" curve :size 15 :depth 3))
-->

Each letter is positioned tangent to the path at the corresponding point. The options:

`:spacing` adds (or removes, if negative) extra space between the letters. `:align` controls the alignment: `:start` (default), `:center`, `:end`. `:overflow` decides what to do if the text is longer than the path: `:truncate` (default, cuts), `:wrap` (restarts from the beginning, useful for closed paths), `:scale` (scales the text to make it fit).

### Text on a 3D path: preserve-up

As long as the path stays in the plane, the letters stand upright on their own. But on a path that rises into space — a spiral, a helix — a subtle problem emerges. Every `th` and `tv` is a rotation around the turtle's *current* axes, and composing many of them in sequence the up vector accumulates a drift: it begins to rotate around the heading without your having asked for it (an *implicit roll*). The text, which uses the up to know which way is "up", curls onto itself and becomes illegible.

`:preserve-up`, as an option of `turtle`, cancels this drift: it captures the up on entry to the scope and after each rotation reprojects it onto the plane perpendicular to the heading. The heading does not change — so the path and the positions stay identical — but the up stays anchored, and the letters stand up.

<!-- example-source: text-spiral
(def spiral (path (tv 7) (dotimes [_ 500] (f 1) (th 3))))
(register spiral-text
  (turtle :preserve-up
    (text-on-path
      "Once upon a time in a galaxy far far away there was a spiral that went up and up and up and never stopped and she's buying a stairway to heaven"
      spiral :size 6 :depth 1.2)))
-->

The initial `(tv 7)` tilts the turtle upward; the 500 steps of `(f 1) (th 3)` make it turn while rising, tracing an ascending spiral. Without `:preserve-up` the text would progressively screw around; with it, it follows the spiral while staying legible. It is the same flag needed for helices, springs, and handrails that climb.

### Common pattern: engraved or raised text

3D text becomes useful when you combine it with a piece through booleans:

<!-- example-source: text-engraved
(def plate (attach (box 3 20 80) (cp-f -40)))
(def label (attach (extrude-text "MODEL-A" :size 12 :depth 2) (f 15) (u -4)))
(register engraved (mesh-difference plate label))
-->

A detail on the orientation, which explains the dimensions chosen: the text develops along the turtle's "writing" axis, so the plate that has to host it must be sized along *that* same axis (here `box 3 20 80`, with the largest dimension, 80, on the text's axis) for the writing to fit inside. The `cp-f -40` shifts the plate's creation-pose to one end, so the text — which starts from the current pose — aligns to the edge instead of centering. For raised text, use `mesh-union` instead of `mesh-difference`. The depth of the extrusion (`:depth`) controls how much the text protrudes or penetrates. For a two-color plate (text in contrast with the base), see the two-color pattern in chapter 14.

## 13.3 Heightmap and raised text

For raised text on a curved surface (a cylinder, a vase, an organic profile), straight extrusion is not enough: the text should follow the curvature. The heightmap is the solution.

A heightmap is a 2D grid where each cell contains a height. Used as a shape-fn in a `loft`, it modulates the profile by adding or removing material point by point: where the cell is high, the profile swells outward.

### Text-heightmap

`text-heightmap` produces a heightmap directly from a string, already oriented correctly to be wrapped onto a profile. You pass it to the `heightmap` shape-fn inside a `loft`:

<!-- example-source: text-heightmap :warning slow
(def hm (text-heightmap "Ridley" :size 20)) ; resolution 256, supersample 3, edge-softness 0.02
(register embossed-cylinder
  (loft-n 256 (heightmap (circle 10 256) hm :amplitude 1.5 :direction :height :center true) (f 60)))
-->

The result is a cylinder with "Ridley" in relief, legible, ~20 units tall (the `:size` you asked for). The key point is that the heightmap *knows its own physical size*: `:size 20` is not stretched to fill the wall, but placed at the real size. It is the `heightmap` shape-fn that reads that size and positions the text accordingly — with the default behavior (a single copy, centered, at the physical size). `:amplitude` controls how much the relief protrudes; `:center true` distributes it symmetrically, so the letters emerge from a neutral surface instead of bulging the cylinder outward.

The parameters of `text-heightmap`: `:size` (physical height of the characters, default 5), `:font` (font id, default `:roboto`), `:resolution` (size of the grid, default 256), plus two knobs that govern the quality of the edge: `:supersample` and `:edge-softness`.

It is worth understanding why they are needed, because the cause is counterintuitive. The text's relief is a *binary mask*: letter (1) or background (0), with a sharp edge one cell of the grid wide. When the loft samples the profile more sparsely than the grid — and almost always it does — its vertices step over that sharp edge and the letters come out jagged, stepped. The temptation is to raise the resolution of the grid or of the loft, but it does not help: the edge stays one cell wide anyway. The solution is to soften the edge itself. `:supersample` (default 3) rasterizes at higher resolution and averages, bringing the edge's position to sub-cell precision; `:edge-softness` (default 0.02, that is, 2% of the height of the characters) widens the edge's ramp to a scale the loft can resolve, turning the step into a soft bevel. It is these two — not `:resolution` nor `:curve-segments` — that remove the jaggedness. For softer letters raise `:edge-softness` (e.g. 0.04); for sharp edges, `:edge-softness 0`. (`:curve-segments` exists and regulates the fidelity of the glyphs' *outline*, but it has nothing to do with the faceting on the loft.)

Once the edge is softened, a reasonably dense loft (`loft-n` with a good number of steps, and a circle at adequate resolution) renders it best: the two work together, not as alternatives. The softness gives the loft a ramp to follow instead of a sharp step; the dense loft samples it with enough vertices to render it smooth. This is why the examples below use `loft-n 256` and `(circle ... 256)`.

A useful detail: the spaces in the text become real flat margin. `"Ridley "` (with the trailing space) is wider than `"Ridley"`, and that margin comes in handy as a gap when you repeat the text around a profile.

Direction, coverage, and repetition are not the job of `text-heightmap` but of the `heightmap` shape-fn, which controls them with `:direction`, `:tile-x`/`:tile-y`, `:fit`. So the same heightmap can be made to run once around the cylinder, packed in repetition along the whole circumference, or made to climb along the axis:

<!-- example-source: text-heightmap-variants :warning slow
(def band (text-heightmap "Ridley " :size 10))

;; Repeated seamlessly around the whole circumference
(register tube
  (loft-n 256 (heightmap (circle 10 256) band :amplitude 1.2 :tile-x :fill) (f 30)))

;; Climbing along the axis instead of wrapping
(register column
  (attach (loft-n 256 (heightmap (circle 8 256) band :amplitude 1.2 :direction :height) (f 40))
          (rt 60)))
-->

`:tile-x :fill` packs as many copies as needed to cover the circumference (the trailing space of `"Ridley "` acts as the gap between one and the next); `:direction :height` orients the text so it runs along the loft's axis instead of around.
