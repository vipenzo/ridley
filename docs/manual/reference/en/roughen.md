---
name: roughen
category: spatial-deformation
since: ""
status: stable
---

# roughen

## Signature

`(roughen amplitude)`
`(roughen amplitude frequency)`

## Description

Return a deform-fn that displaces vertices along their normals by a
deterministic pseudo-random amount. The displacement is bounded by
`amplitude` and modulated by `smooth-falloff` so the noise tapers to
zero at the volume boundary. Use with `warp`.

The noise is a hash of the vertex's world position scaled by
`frequency`: same position always gives the same offset, so a roughened
mesh is reproducible. Higher `frequency` means smaller, finer-grained
bumps; lower frequency means bigger, gentler ripples.

## Parameters

- `amplitude` — maximum displacement in world units (the actual offset
  varies between roughly `±amplitude` per vertex, weighted by falloff).
- `frequency` (optional, default `1`) — spatial frequency multiplier.
  Higher values pack more detail into the same region.

## Example

{{example: roughen-sphere-surface}}

<!-- example-source: roughen-sphere-surface -->
```clojure
;; Roughen a sphere's surface
(register r
  (warp (sphere 20 32 16) (sphere 22) (roughen 1.5 3)))
```
<!-- /example-source -->

The slightly larger sphere volume covers the whole geometry; `roughen
1.5 3` displaces every vertex by up to 1.5 units along its normal with
medium-fine frequency. The result has a textured, organic surface.

## Variations

{{example: roughen-localised}}

<!-- example-source: roughen-localised -->
```clojure
;; Roughen only a localised patch
(register r
  (warp (sphere 20 32 16) (attach (sphere 8) (f 18)) (roughen 1 5)))
```
<!-- /example-source -->

A small volume confines the noise to a patch. Outside the volume the
sphere is smooth; inside it picks up high-frequency texture.

## Notes

- The noise function is deterministic — the same `amplitude`,
  `frequency`, and vertex positions always yield the same result. Good
  for reproducible exports; not good if you need each call to look
  different.
- For low-poly meshes the noise reads as flat shading on each triangle.
  Use `:subdivide` on `warp` to densify the mesh first.

## See also

- **Mother card:** `warp`
- **Related presets:** `inflate`, `dent`, `attract`, `twist`, `squash`
- **Related:** `fbm`, `noise`
