---
name: clone-face
category: faces
since: ""
status: stable
---

# clone-face

## Signature

`(clone-face mesh face-id & body)`

## Description

Extrude a chosen face of `mesh` outward, creating new geometry. The
body is a small turtle path operating in the face's local frame:
each step inserts a new ring of vertices and connects it to the
previous one with quads. The result is a stepped extrusion attached
to the original mesh.

Available body commands:

| Command           | Effect                                                              |
|-------------------|---------------------------------------------------------------------|
| `(f dist)`        | Move the leading ring `dist` units along the face's normal         |
| `(inset dist)`    | Shrink the leading ring inward by `dist` units                     |
| `(scale factor)`  | Scale the leading ring uniformly                                   |

Multiple steps can be chained: each `(f …)` / `(inset …)` /
`(scale …)` produces a new ring connected to the previous one. The
final ring becomes the new outer face; the original face is replaced
by the connecting side walls.

`face-id` resolution is the same as for `attach-face` — `:top`,
`:bottom`, `:side`, etc. on primitives; face queries for CSG results.

For deforming an existing face WITHOUT adding new layers, use
`attach-face`.

## Parameters

- `mesh` — a mesh value or a registered name.
- `face-id` — a keyword identifying the source face.
- `body` — one or more of `(f dist)`, `(inset dist)`, `(scale factor)`,
  in any combination.

## Example

{{example: clone-face-simple}}

<!-- example-source: clone-face-simple -->
```clojure
(register stepped
  (-> (box 20)
      (clone-face :top (f 10))))    ; extrude top face up 10 units
```
<!-- /example-source -->

A single `(f 10)` extrudes the top face into a new prism atop the
box. The result is one mesh with the extruded prism welded onto the
original.

## Variations

{{example: clone-face-stepped}}

<!-- example-source: clone-face-stepped -->
```clojure
;; Three-step stepped extrusion: each step narrows and rises
(register tower
  (-> (box 20)
      (clone-face :top (f 5))
      (clone-face :top (inset 3) (f 5))
      (clone-face :top (inset 3) (f 5))))
```
<!-- /example-source -->

Each `clone-face` builds on the previous step. The composition is
the canonical pattern for stepped pedestals, tapered chimneys, and
similar profiles where each layer is offset from the one below.

{{example: clone-face-with-scale}}

<!-- example-source: clone-face-with-scale -->
```clojure
;; Bullet shape: extrude up, scale, extrude further
(register bullet
  (-> (cyl 8 20)
      (clone-face :top (f 8) (scale 0.6))
      (clone-face :top (f 4) (scale 0.2))))
```
<!-- /example-source -->

`scale` is per-step: each new ring is resized uniformly. Combined
with `f`, it produces taper profiles that would otherwise require a
`loft` or a series of CSG cuts.

## Notes

- The body builds geometry incrementally. Order matters: `(inset 3)
  (f 5)` first shrinks the ring, then extrudes it; `(f 5) (inset 3)`
  first extrudes, then shrinks at the top.
- The original face is removed and replaced by side walls. The new
  outer face is always the leading ring after the last command.
- For deforming a face without creating new geometry, use
  `attach-face`. For full free-form extrusion that follows a turtle
  path with rotations, use `extrude` from a face-shape (see Spec
  §8.4 `face-shape`).

## See also

- **Related:** `attach-face`, `extrude`, `face-info`, `list-faces`,
  `find-faces`, `face-at`, `face-shape`, `inset`
