# 14. Color and materials

In Ridley color is a modeling tool, not just a visualization one. The colors you assign to the meshes determine how the parts will be mapped to the filaments in multi-material printing. This chapter covers color assignment, material properties, and the workflow for exporting multi-color models.

## 14.1 Coloring shapes

### Global color

`color` without a mesh name as the first argument sets the color for all the meshes created from that point on:

```clojure
(color 0xff0000)           ;; red, from here on
(register red-box (box 20))

(color 0x0000ff)           ;; blue
(register blue-cyl (cyl 10 30))
```

<!-- example-source: color-global
(color 0xff0000)
(register red-box (box 20))
(color 0x0000ff)
(register blue-cyl (attach (cyl 10 30) (f 40)))
-->

The color is stored in the turtle's state and is applied to every mesh created after the call (primitives, extrusions, lofts, etc.).

The RGB form with three separate arguments works the same way:

```clojure
(color 255 0 0)            ;; red, components 0-255
```

### Color per mesh

To change the color of an already-registered mesh, without recreating it:

```clojure
(register b (box 20))
(color :b 0xff8800)        ;; orange for :b
(color :b 255 128 0)       ;; same thing, RGB form
```

<!-- example-source: color-per-mesh
(register b (box 20))
(color :b 0xff8800)
-->

The first argument is the registered name (keyword). The mesh in the registry is updated with the new color.

### Color on mesh values

`color` can also take a mesh value (not registered) as its first argument. In this case it returns a new mesh with the color set, without modifying anything in the registry:

```clojure
(def colored-box (color (box 20) 0xff0000))
(register r colored-box)
```

This form is useful for inline compositions with `register`:

```clojure
(register part (color (mesh-union a b) 0x00ff00))
```

## 14.2 Materials

### Material properties

`material` sets the PBR (physically-based rendering) properties of the meshes:

```clojure
;; Global: applies to the meshes created from here on
(material :metalness 0.8 :roughness 0.2)

;; Per registered mesh
(material :my-mesh :metalness 0.8 :roughness 0.2)

;; On a mesh value (returns a new mesh)
(register bowl (material (make-bowl 30 20) :opacity 0.3))
```

<!-- example-source: material-metallic
(register shiny-sphere (material (sphere 20) :metalness 0.9 :roughness 0.1))
-->

The available properties are those of the standard Three.js material: `:metalness` (0-1), `:roughness` (0-1), `:opacity` (0-1), `:color` (hex, also settable from `material`).

`(reset-material)` returns the properties to the default.

### Transparency

Opacity is set with `:opacity` inside `material`:


<!-- example-source: material-transparent
(register ghost (material (box 30) :opacity 0.3))
(register glass (material (attach (sphere 15) (f 40)) :opacity 0.5 :color 0x88ccff))
-->

Transparency is useful during modeling to see through a piece and understand how the internal parts fit together. It has no effect on export: STL and 3MF do not carry opacity.

## 14.3 Multi-material: the register + color pattern

The workflow for multi-material printers (Bambu AMS, Prusa MMU) is simple: register the parts as separate meshes, assign a color to each, export to 3MF.

In the resulting 3MF file, each mesh becomes a separate object with its color. When you open it in Bambu Studio or OrcaSlicer, the parts appear with the pre-assigned colors, ready to be mapped to the filament slots.

A few things to know:

Meshes with the same color share a single material entry in the 3MF (same filament, distinct parts). Meshes without a color are exported as geometry without material information, as in an STL.

The STL format does not support multiple colors or materials. If you export to STL, all the meshes are merged into one and the color is lost. For multi-material, always use 3MF.

### The bicolor label pattern

The most common example of multi-material is a two-color label: a flat base with text in contrast. The pattern is:

1. Build the base as one mesh
2. Build the text as a separate mesh, positioned on the top face of the base
3. Assign distinct colors
4. Export both to 3MF


The key is that base and text are separate meshes, not merged with `mesh-union`. If you merge them, they become a single object in the 3MF and you cannot assign different colors.

### A reusable function: inset text

The `bicolor-label` function below *insets* the text into the base: the letters are subtracted from the plate with `mesh-difference` and filled by a second mesh of the same thickness, so the two colors lie on the same plane and the print has no protruding letters.

The function encapsulates the whole procedure and returns a vector with the two meshes `[base text]`, already colored and ready to register and export:


The use is then one line: you destructure the vector and register the two parts.

```clojure
(let [[base txt] (bicolor-label "Ridley")]
  (register label-base base)
  (register label-text txt))
```

<!-- example-source: bicolor-label-fn
(defn bicolor-label
  [text & {:keys [font-sz label-depth font margin-ratio tolerance full-cut
                  base-color text-color]
           :or   {font-sz 15 label-depth 3 font :roboto
                  margin-ratio 1.1 tolerance 0.3 full-cut false
                  base-color 0xffffff text-color 0xff00ff}}]
  (let [len       (text-width text font font-sz)
        mgn       (fn [x n] (* (+ 1 (* n (- margin-ratio 1))) x))
        cut-depth (if full-cut label-depth (/ label-depth 2))
        t-shape   (text-shape text :size font-sz :font font :center true)
        n-t-shape (shape-offset t-shape tolerance)
        base      (color (mesh-difference
                           (attach (box (mgn len 2) (mgn font-sz 2) label-depth)
                                   (cp-f (/ label-depth -2)))
                           (extrude n-t-shape (f cut-depth)))
                         base-color)
        e-text    (color (extrude t-shape (f cut-depth)) text-color)]
    [base e-text]))

(let [[base txt] (bicolor-label "This is Ridley")]
  (register label-base base)
  (register label-text txt))
  
;(export :label-base :label-text :3mf)

(let [[base txt] (bicolor-label "3D Printing is nice"
                   :label-depth 5
                   :full-cut true
                   :font-sz 24
                   :margin-ratio 1.05
                   :tolerance 0.8
                   :base-color 0xffff00
                   :text-color 0x999988
                   )] ; text only on the -X side
  (register A (attach txt (u 100)))
  (register B (attach base (u 100))))

-->

The parameters:

- `text` is the only mandatory argument.
- `:font-sz` (15), `:font` (`:roboto`), and `:label-depth` (3) control the character body, the font, and the plate's thickness.
- `:margin-ratio` (1.1) sets the margin around the text: the plate is sized on the real width of the text (measured with `text-width`) plus this margin.
- `:tolerance` (0.3) widens the hole relative to the letters (`shape-offset`), leaving the play needed for the two parts to fit together in multi-material printing.
- `:base-color` (white) and `:text-color` (magenta) are the two colors.
- `:full-cut` (`false`) decides whether the text goes through the plate from side to side (`true`, writing visible from both sides) or stays engraved only on the front face down to half the thickness (`false`, with a solid floor in the base color).

The function neither registers nor names the meshes: it returns a vector, so you decide how to call them and how to pass them to `(export ... :3mf)`.
