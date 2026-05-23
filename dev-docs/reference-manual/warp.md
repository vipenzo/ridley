---
name: warp
category: spatial-deformation
since: ""
status: stable
---

# warp

## Signature

`(warp mesh volume deform-fn)`
`(warp mesh volume deform-fn1 deform-fn2 …)`
`(warp mesh volume deform-fn :subdivide n)`

## Description

Deform the vertices of `mesh` that fall inside `volume`, leaving the rest
untouched. Returns a new mesh. Pure function; does not modify turtle
state.

`warp` is the general-purpose deformation operator: pass a volume to
choose *where* the deformation acts, and one or more deform-fns to
choose *how*. The volume is any mesh — typically a primitive like
`sphere`, `box`, `cyl`, or `cone` — positioned with `attach` exactly
like you would position any other shape. Vertices outside the volume
keep their original position; vertices inside are passed through every
deform-fn in sequence.

A deform-fn is a function of five arguments returning a new position.
Most users compose presets — `inflate`, `dent`, `attract`, `twist`,
`squash`, `roughen` — rather than write deform-fns by hand. For custom
deformations, see the deform-fn contract in Notes.

For low-poly inputs, `:subdivide n` densifies triangles that fall inside
the volume before the deform runs; this lets a smooth volume produce a
smooth deformation even when the source mesh has few faces.

## Parameters

- `mesh` — the mesh to deform.
- `volume` — a mesh whose interior defines the deformation region. Any
  primitive works; `attach` it to position it relative to `mesh`.
- `deform-fn`, `deform-fn2`, … — one or more functions
  `(fn [pos local-pos dist normal vol] -> new-pos)`. Presets all match
  this signature; multiple deform-fns apply in order, each seeing the
  previous one's output.
- `:subdivide n` (option) — perform `n` passes of midpoint subdivision
  on triangles inside the volume before deforming. Each pass turns one
  triangle into four (edges split at midpoints, vertices shared with
  neighbours). Drops `:face-groups` metadata on the result.

## Example

{{example: warp-inflate-box}}

<!-- example-source: warp-inflate-box -->
```clojure
;; Organic bump on a flat face
(register b (warp (box 40) (sphere 25) (inflate 5) :subdivide 2))
```
<!-- /example-source -->

The sphere defines a circular zone of influence on the box; `inflate 5`
pushes vertices in that zone outward along their normals by up to 5
units, with smooth falloff toward the volume's boundary. `:subdivide 2`
gives the box enough triangles to deform smoothly.

## Variations

{{example: warp-twist-cyl}}

<!-- example-source: warp-twist-cyl -->
```clojure
;; Twisted cylinder — volume matches the cylinder, axis auto-detected
(register c (warp (cyl 10 40 32) (cyl 12 40) (twist 90)))
```
<!-- /example-source -->

When the volume is a cylinder or cone, `twist` auto-detects the rotation
axis. The angle varies smoothly along the axis: zero at the centre,
±half-angle at the ends.

{{example: warp-chain-roughen-attract}}

<!-- example-source: warp-chain-roughen-attract -->
```clojure
;; Multiple deform-fns: roughen first, then attract toward centre
(register r (warp (sphere 20 32 16) (sphere 22)
                  (roughen 1.5 3)
                  (attract 0.3)))
```
<!-- /example-source -->

Deform-fns chain in order: each receives the previous one's output.
Here vertices are first noised, then pulled partway toward the volume
centre — producing a textured surface that bulges inward.

## Notes

- **Deform-fn contract.** The function receives:
  - `pos` — world position `[x y z]` (the previous deform-fn's output,
    or the original vertex for the first call).
  - `local-pos` — normalized position in the volume, `[-1, 1]` per axis.
  - `dist` — normalized distance from the volume centre, `0` at centre,
    `1` at boundary.
  - `normal` — estimated vertex normal `[nx ny nz]`.
  - `vol` — volume bounds map (`:center`, `:up`, `:heading`, `:right`,
    `:half-ext`, `:primitive`, `:radius`, `:height`, …).

  The deform-fn returns the new `pos`.

- **Smooth falloff.** All presets weight their effect with a Hermite
  curve (`3t² − 2t³`) so the deformation tapers to zero at the volume
  boundary. The same curve is exposed as `smooth-falloff` for custom
  deform-fns.

- **`:subdivide` cost.** Each pass roughly quadruples the in-volume
  triangle count. Use sparingly — one or two passes are usually enough.
  `:face-groups` is dropped because subdivision invalidates the face
  topology.

- **Positioning the volume.** The volume's pose is taken as-is from the
  mesh value you pass. Wrap it in `attach` (or `translate`) to move it
  into position relative to the target mesh.

## See also

- **Preset deform-fns:** `inflate`, `dent`, `attract`, `twist`,
  `squash`, `roughen`
- **Helpers:** `smooth-falloff`
- **Related:** `attach`, `translate`, `concat-meshes`, `mesh-smooth`
