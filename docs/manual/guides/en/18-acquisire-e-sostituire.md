<!--
Translated from it/18-acquisire-e-sostituire.md (2026-07-16). Keep in sync:
same section structure, same examples, same honesty note about the scanner
channel. Code examples identical to the IT source. No em-dash.
-->

# 18. Acquiring and replacing

<!-- level: advanced -->

## 18.1 The tracing guide

There is a way of working that the previous chapters do not cover: starting from an object that already exists. A spare part to reproduce, a downloaded STL to modify, a scan to distill into a clean model. In all these cases the imported mesh is not the final product: it is the *tracing guide* on which to build a native Ridley object, parametric and editable, that will eventually replace it entirely.

The workflow has four moments: importing the mesh, taking it apart into its logical pieces, building the replacements while comparing them against the reference, and finally letting the scaffolding fall. This chapter walks through them in order.

Today the input channel is the mesh via STL; scanner acquisition (PLY, repair, scale calibration) is anticipated by the design but not yet implemented.

## 18.2 Importing

There are two ways to bring an STL into Ridley. The most immediate is the library's **import procedure** (ch. 9): you pick the file and Ridley generates a library entry with the geometry data embedded. The generated source looks like this:

```clojure
(def mount
  (-> (decode-mesh "...STL data...")
      (mesh-translate [0.039 -0.001 -1.842])))
```

`decode-mesh` is the function that rebuilds the mesh from the embedded data: you meet it in generated code, you rarely write it by hand. The `mesh-translate` emitted alongside makes any recentering visible (and editable).

The scriptable alternative is `import-stl`, which reads the file from disk (desktop only):

```clojure
(def mount (import-stl "/path/to/mount.stl" :recenter true))
```

Unlike the library entry, the geometry is not embedded in the source: the program only references the path. Useful when the STL cannot be redistributed, or when it is large and you do not want to bloat the source.

Either way, from here `mount` is a mesh like any other: you can measure it (ch. 10), section it (ch. 7.5), diagnose it (ch. 7.7). But it is a monolithic block: thousands of triangles with no structure. The first step is giving it one.

## 18.3 Taking apart: edit-mesh-split

You could write a `mesh-split` with its planes by hand (ch. 7.8), but finding the right offsets by trial and error is tedious. `edit-mesh-split` is the interactive editor that does this job:

```clojure
(edit-mesh-split mount)
```

A session opens on the viewport. The cut plane is the turtle's pose: you move it with the arrow keys or by dragging the gizmo, and the two live halves are colored to show what you would detach. Below, a strip shows the section profile A(t): the area of material the plane crosses along its travel. The ticks on the strip are the natural cut points: blue for steps (area discontinuities, computed exactly from the mesh's faces), orange for necks (constrictions). A click on the strip jumps to the nearest candidate.

The essential gestures: Enter accepts the `:behind` half as a definitive piece and moves on to the rest; `a` declares the current piece finished as it is (even if concave: finished is a decision, not a geometric fact); Backspace undoes the last gesture; `n` moves to the next open piece. When every piece is finished, Enter commits.

On closing, the editor writes the corresponding call into the source:

```clojure
(def AA (mesh-split mount
          (path (tv 90) (f -1.62) (mark :cut-1))
          [:cut-1]))
```

It is a normal call, with no scaffolding: you can touch it up by hand, and you can reopen it in the editor by putting `edit-` back in front of `mesh-split`. This round-trip is the tool's contract: the source remains the single truth.

If a piece contains disconnected parts (a U cut at its base leaves two prongs), the editor does not separate them: accept it as it is and separate afterwards, in code, with `mesh-components`.

## 18.4 From cuts to pieces

The value of `AA` is a nested composite. `split-tree` turns it into the map of pieces, and from there you register:

```clojure
(def AAs (split-tree AA))

(register base  (AAs :piece-1))
(register forks (AAs :piece-2))

;; the two prongs are disconnected parts of the same piece:
(let [[left right] (mesh-components (AAs :piece-2))]
  (register fork-l left)
  (register fork-r right))
```

At this point the object has a structure: names, pieces, boundaries. It is still all imported geometry, but it is addressable.

## 18.5 Building and comparing: mesh-board

Now the real work: rebuilding each piece as a native Ridley object. The replacement must be built and positioned on top of the piece it replaces, and here you need feedback that tells you where and by how much the two differ. That is `mesh-board`'s job:

```clojure
(def SOST (attach (extrude (polygon 8 12) (f 6)) (tv 90) (f -1)))

(mesh-board (AAs :piece-2) SOST)
```

The two-argument form is the comparison: the first is the reference (the piece being replaced), the second the candidate. At each evaluation the comparison views appear in small windows at the edges of the viewport, and the fidelity, the percentage of volumetric coincidence between the two, is printed in the output panel:

- `intersection`: the common part;
- `missing`: the reference material the candidate does not cover yet;
- `excess`: where the candidate overshoots.

Each window shows the solid result with its label and volume, and follows the main view's orientation: rotate the object and the comparisons rotate with it. With `{:views [:excess]}` you limit the views to the ones you need; with `{:ghost true}` you add the overlapped in-place wireframes, useful for the initial coarse positioning; with `:label` you disambiguate several comparisons active in the same program.

The tuning loop is all here: touch up the replacement, re-evaluate, watch `missing` and `excess` empty out and the fidelity climb. `mesh-board` returns its first argument unchanged and leaves no trace in the exported scene: it is a display directive, not a transformation (its one-argument form, `(mesh-board AAs)`, instead shows the pieces as in-place wireframe scaffolds).

## 18.6 The scaffolding falls

When a piece's fidelity satisfies you, the replacement is a one-line change:

```clojure
;; before:
(register fork-l (first (mesh-components (AAs :piece-2))))
;; after:
(register fork-l SOST)
```

The `mesh-board` line comes out at that point: its job is done. Piece by piece, the imported references leave the program; when the last piece is native, `mesh-split` and `split-tree` fall too, and finally the `decode-mesh` itself. What remains is a pure, parametric Ridley object that keeps only the measurements of the original. The tracing guide is thrown away, the drawing stays.
