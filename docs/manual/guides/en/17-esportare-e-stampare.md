# 17. Exporting and printing

<!-- level: base -->

Once the model is ready in the viewport, you export it as a file for the slicer or for other software. Ridley supports two formats: STL (the universal 3D printing format) and 3MF (the more recent format, with support for colors and multiple materials).

## 17.1 STL

### Export from the registry

```clojure
(export :my-piece)                   ;; one piece, by name
(export :piece-a :piece-b)           ;; several pieces, merged into a single file
(export my-mesh)                     ;; by reference (variable)
```

`export` looks for the mesh in the registry by name (keyword) or accepts a mesh value directly. The file is downloaded by the browser (or saved through a file picker on the desktop).

When you export several meshes to STL, they are merged into a single file. There is no way to keep separate parts in STL: it is a limit of the format.


### What ends up in the file

The STL contains only the geometry: vertices and triangular faces. It does not carry colors, materials, names, or the distinction between parts. If your model has several differently colored pieces and you export them to STL, you get a single gray block.

## 17.2 Multi-material 3MF

### Basic export

```clojure
(export :my-piece :3mf)              ;; single piece in 3MF
(export :piece-a :piece-b :3mf)      ;; several pieces in 3MF
```

The keyword `:3mf` as the last argument selects the format. Without it, `export` produces STL.

### Multi-material

When the exported meshes have colors assigned with `color`, the 3MF carries them:

```clojure
(register base (box 40 40 3))
(register label (attach (extrude-text "OK" :size 15 :depth 1) (th 90) (rt -4) (u 2.5)))

(color :base 0xff0000)
(color :label 0xffffff)

(export :base :label :3mf)
```

In the resulting 3MF file each mesh is a separate object with its color. In the slicer (Bambu Studio, OrcaSlicer, PrusaSlicer) the parts appear with the pre-assigned colors, ready to be mapped to the filament slots.

Meshes with the same color share the same material entry (same filament, distinct parts). Meshes without a color are exported as geometry without material information.


### When to use STL, when 3MF

STL is the most supported format: every slicer, every online printing service, every CAD software reads it. Use it when you do not need colors or multiple materials, or when you have to share the file with someone who might not have a modern slicer.

3MF is the format to prefer for multi-material printing and in general when you work with Bambu Studio or OrcaSlicer, which support it natively. It is also more compact (compressed) and carries more information (units, part names).

### Tips for printing

The model you see in the viewport is exactly what ends up in the file: same dimensions, same geometry, same resolution. If a piece looks faceted in the viewport, it will be faceted in the print too. Raise `resolution` before export if needed.

Ridley's units are millimeters. If your model is parametric, check with `bounds` that the final dimensions are what you expect before exporting.

`mesh-diagnose` (chapter 7.7) is the tool to verify that the mesh is healthy before export. Most slicers automatically repair small defects, but meshes with many open-edges or non-manifold-edges can produce unpredictable results in slicing.

## 17.3 Orienting for printing

The slicer expects the model resting on the print bed. If the piece was built in an arbitrary orientation, `lay-flat` rotates it so that one of its faces rests on the world's XY plane, and recenters the result on the origin. It is the explicit step to give the piece the print orientation without remodeling it.

<!-- example-source: lay-flat-default -->
```clojure
(register bracket (-> (box 30 20 8) (lay-flat)))
```

Without arguments `lay-flat` uses `:bottom`: it takes the largest bottom face and rests it on the plane. For asymmetric pieces the best print orientation is often not the bottom. A direction keyword (`:top`, `:bottom`, `:up`, `:down`, `:left`, `:right`) chooses the largest face on that side and puts it down.

<!-- example-source: lay-flat-direction -->
```clojure
(register sideways (-> (box 40 10 20) (lay-flat :left)))
```

When the print face is not aligned to a cardinal direction, you can mark it as an anchor with `attach-path` and pass the anchor's name to `lay-flat` (`(lay-flat :part :print-face)`): the anchor's plane is laid flat, whatever its orientation.

Two things to remember. `lay-flat` recenters the piece on the origin: if you need to keep a precise corner at `[0 0 0]`, follow it with a `translate`. And it operates on the vertices, not on the `:creation-pose`, which stays at the construction origin; if you need to re-anchor it there is `reset-creation-pose`. For meshes without face groups, like the results of a boolean, the face groups are deduced automatically by coplanar adjacency and the largest face is chosen from that grouping.

## 17.4 Printable joints

A piece too big for the bed gets cut with `mesh-split` and printed in parts; the parts need something to hold them together again. The built-in **joints** library (activate it in the libraries panel) has four, all built the same way: cut the mesh at the turtle's pose, build the joint on the cut plane straddling it, return `{:a :b :extras}` — `:a` with the male part, `:b` with the female, `:extras` with anything printed separately.

```clojure
(def ab (joints/bayonet (box 30 30 30) 8 12 0.3))
(register A (:a ab))
(register B (:b ab))
(tweak (mesh-board {:A A :B B} {:solid true :views [[:section :cut :offset 3]]}))
```

- `joints/tenon` — integral pin and blind hole.
- `joints/dowel` — a separate pin, holes on both sides; the parts print flat on the cut face.
- `joints/bayonet` — plug with lugs, L-slots in the bore: insert turned, twist, and the parts come back aligned.
- `joints/thread` — square thread; flank clearance `t` and radial clearance `:radial` are separate, because on a printer they need different values.

The last argument of each is the clearance, and it is the one number only a print can settle: the bayonet reassembled a cube true at 0.3, the thread needed 0.5 on the flanks. Check a joint before printing with `mesh-board`'s section, sliding the plane through it with `tweak`; for many pins on a wide face, `layout-anchors` (chapter 7.8) places them and `on-anchors` builds them.
