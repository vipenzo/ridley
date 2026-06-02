---
name: face-shape
category: faces
since: ""
status: stable
---

# face-shape

## Signature

`(face-shape mesh face-id)`

## Description

Extract one face of `mesh` as a 2D shape, together with a pose that
places the shape's local frame at the face. Returns a map
`{:shape <ridley-shape> :pose {:pos [x y z] :heading [hx hy hz] :up [ux uy uz]}}`,
or `nil` if `face-id` is not present. Pure function; does not modify
turtle state.

`face-shape` is the bridge from "a face of an existing mesh" to "an
arbitrary 2D shape you can extrude". It's the escape hatch when
`attach-face` and `clone-face` are not flexible enough — you want a full
extrude pipeline (curves, joints, twists) starting from the face contour.

The shape preserves the face's boundary, including holes. The pose's
heading and up are derived from the mesh's creation-pose, projected onto
the face plane: combine with the `turtle` macro to position downstream
geometry on the face.

Internally calls `ensure-face-groups`, so it works on raw CSG output.

## Parameters

- `mesh` — a mesh value or a registered name.
- `face-id` — a keyword on primitives, or an integer on auto-grouped
  meshes.

## Example

{{example: face-shape-extrude-top}}

<!-- example-source: face-shape-extrude-top -->
```clojure
(register b (box 20))
(def top (face-shape :b :top))
;; Extrude the top face's contour 8 units along the face normal
(turtle (:pose top)
  (extrude (:shape top) (f 8)))
```
<!-- /example-source -->

The `turtle` macro adopts the face's pose; `extrude` then walks the
turtle as usual. Equivalent in effect to `clone-face`, but exposes the
full extrude toolbox (curves, joints, transforms) on the body.

## Variations

{{example: face-shape-csg-rim}}

<!-- example-source: face-shape-csg-rim -->
```clojure
;; On a CSG mesh, pick the face geometrically then extract its contour
(register cut (difference (box 30) (translate (cyl 8 30) 0 0 0)))
(def top (face-shape :cut (:id (largest-face :cut :top))))
(turtle (:pose top)
  (extrude (:shape top) (th 30) (f 10)))
```
<!-- /example-source -->

The face contour is an annulus (square with a circular hole). `extrude`
sweeps it along a curved path; the resulting solid inherits the rim
geometry exactly.

## Notes

- The pose map uses `:pos`, not `:position`, for compatibility with the
  `turtle` macro. Other fields are `:heading` and `:up`, both unit
  vectors in mesh coordinates.
- If the face has multiple boundary loops, the outer loop is detected by
  signed-area magnitude; the remaining loops become holes. The orientation
  is normalised to CCW for the outer loop.
- For deforming a face in place (no new geometry) or for a simple
  axial extrusion, `attach-face` / `clone-face` are simpler.

## See also

- **Related:** `attach-face`, `clone-face`, `extrude`, `face-info`,
  `find-faces`, `largest-face`, `turtle`
