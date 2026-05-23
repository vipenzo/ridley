---
name: concat-meshes
category: mesh-operations
since: ""
status: stable
---

# concat-meshes

## Signature

`(concat-meshes m1 m2 …)`
`(concat-meshes [m1 m2 …])`

## Description

Stitch two or more meshes into a single mesh by concatenating their
vertex and face arrays. Returns one mesh that visually contains every
input, without invoking Manifold or any CSG. The result is generally
NOT manifold — its components remain topologically separate even
though they live in the same mesh value.

`concat-meshes` is the cheap counterpart of `mesh-union`. Use it
when:

- You only need the combined geometry for visualization, export, or
  heightmap sampling.
- You want to feed many small pieces into a single boolean call —
  for instance, drilling a grid of holes by concatenating them all
  into one tool and calling `mesh-difference` once instead of N
  times.
- The pieces are guaranteed not to overlap, so unioning them would
  be wasted work.

## Parameters

- `m1`, `m2`, … — meshes (manifold or not).
- `[m1 m2 …]` — alternatively, a single vector.

## Example

{{example: concat-meshes-basic}}

<!-- example-source: concat-meshes-basic -->
```clojure
(register a (box 10))
(f 20)
(register b (sphere 6))

;; Cheap aggregate — no CSG, components remain separate
(register pair (concat-meshes a b))
(hide :a) (hide :b)
```
<!-- /example-source -->

The two primitives are stitched into a single mesh value. The
viewport renders both, but no boolean operation has been performed.

## Variations

{{example: concat-meshes-as-tool}}

<!-- example-source: concat-meshes-as-tool -->
```clojure
;; A grid of holes drilled through a plate, in one CSG call
(def tool
  (concat-meshes
    (for [i (range 5)
          j (range 3)]
      (attach (cyl 2 10)
              (rt (* i 8)) (u (* j 8))))))

(register board
  (mesh-difference (box 60 30 4) tool))
```
<!-- /example-source -->

15 cylinder holes are pre-aggregated by `concat-meshes`, then handed
to a single `mesh-difference`. Compared to a sequential subtraction,
this avoids 14 intermediate CSG passes.

## Notes

- The result of `concat-meshes` is acceptable as a tool input to
  Manifold (which tolerates triangle soups in tool position) but NOT
  as a base for further smoothing or as an output for STL export
  expecting a single closed shell.
- `concat-meshes` is linear: cost grows with the number of vertices
  and faces, not with the number of operands.
- Material/colour assigned per input mesh is preserved when meshes
  remain distinct components; assembling pre-coloured pieces is a
  legitimate use.

## See also

- **Guide:** placeholder → cap. 7 (Mesh operations)
- **Related:** `mesh-union`, `mesh-difference`, `mesh-intersection`
