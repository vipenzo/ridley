---
name: attach-face
category: faces
since: ""
status: stable
---

# attach-face

## Signature

`(attach-face mesh face-id & body)`

## Description

Move the vertices of a chosen face of `mesh` in-place. The body is a
small turtle path operating in the face's local frame: `(f dist)`
slides the face along its outward normal, `(inset dist)` shrinks it
inward toward the centroid, `(scale factor)` resizes it uniformly.
Returns a new mesh.

`attach-face` does NOT create new geometry — it relocates the
existing face's vertices. Adjacent quads bend or stretch to follow.
For creating new geometry by extruding a face outward, see
`clone-face`.

`face-id` is a semantic face name on primitives:

| Primitive       | Face IDs                                                  |
|------------------|-----------------------------------------------------------|
| Box              | `:top`, `:bottom`, `:front`, `:back`, `:left`, `:right`   |
| Cylinder / Cone  | `:top`, `:bottom`, `:side`                                |
| Sphere           | `:surface`                                                |

For CSG results without semantic faces, use `face-shape`-style
queries (see Spec §8 Face selection) to pick a face by geometric
property.

## Parameters

- `mesh` — a mesh value or a registered name.
- `face-id` — a keyword identifying the target face.
- `body` — one or more of `(f dist)`, `(inset dist)`, `(scale factor)`.

## Example

{{example: attach-face-extrude-top}}

<!-- example-source: attach-face-extrude-top -->
```clojure
(register block
  (-> (box 20)
      (attach-face :top (f 8))))     ; top face pulled up 8 units
```
<!-- /example-source -->

The top face slides 8 units along its outward normal. The side faces
stretch to follow; no new triangles are created.

## Variations

{{example: attach-face-inset-scale}}

<!-- example-source: attach-face-inset-scale -->
```clojure
;; Shrink the top face inward and downsize it uniformly
(register tapered
  (-> (box 20)
      (attach-face :top (inset 4) (scale 0.6))))
```
<!-- /example-source -->

`inset` pulls the face's vertices toward its centroid; `scale`
resizes it. Combined, they produce a tapered top — like a truncated
pyramid — without inserting any new vertices.

{{example: attach-face-cylinder}}

<!-- example-source: attach-face-cylinder -->
```clojure
;; Pull the cylinder's top face outward and rescale it for a goblet shape
(register goblet
  (-> (cyl 8 25)
      (attach-face :top (f 10) (scale 1.5))))
```
<!-- /example-source -->

Works the same on cylinders. `:side` is also valid: it operates on
the lateral face group as a whole.

## Notes

- `attach-face` only deforms existing geometry. For ADDING new
  layers of geometry from a face (the more flexible "stepped
  extrusion" pattern), use `clone-face`.
- The available body commands are exactly `(f dist)`,
  `(inset dist)`, and `(scale factor)`. Other turtle commands
  (rotations, curves) are not meaningful here — the face moves
  rigidly along its normal or scales in its plane.
- Operating on a face with non-uniform vertex spacing (e.g. a CSG
  result with a complex boundary) is fine: every vertex on the face
  follows the same transformation. The "face" is the connected set
  of coplanar adjacent triangles.

## See also

- **Guide:** placeholder → cap. 8 (Faces and surface features)
- **Related:** `clone-face`, `attach`, `attach!`, `face-info`,
  `list-faces`, `find-faces`, `face-at`, `face-shape`
