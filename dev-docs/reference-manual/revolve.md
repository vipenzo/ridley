---
name: revolve
category: generative-operations
since: ""
status: stable
---

# revolve

## Signature

`(revolve shape)`
`(revolve shape angle)`
`(revolve shape angle & {:keys [pivot]})`
`(revolve shape-fn)`

## Description

Rotate a 2D profile around the turtle's **up** axis to produce a solid of
revolution (lathe). The revolution axis passes through the turtle's
current position. Returns a mesh; does not modify turtle state.

The 2D profile is interpreted as:

- **X = radial distance** from the axis (perpendicular to up).
- **Y = position along the axis** (in the up direction).

At the starting angle (`θ = 0`), the profile is stamped so that shape-X
maps to the turtle's right (`heading × up`) and shape-Y maps to up —
identical to the `stamp` / `extrude` convention. To tilt the revolution
axis, change the turtle's pose with `(tv …)` before calling `revolve`.

With no angle, the revolution is a full 360°. With an explicit angle (in
degrees), the revolution is partial. Shapes with vertices at `x < 0` are
auto-clipped at the revolution axis to prevent self-intersecting
geometry.

A shape-fn is also accepted: the profile is evaluated at each revolution
step with `t` going from 0 (first ring) to 1 (last ring).

## Parameters

- `shape` — a 2D profile, or a shape-fn, or a vector of shapes (merged
  into a single mesh).
- `angle` — revolution angle in degrees. Optional; defaults to 360.
- `:pivot` — `:left`, `:right`, `:up`, `:down`. Shifts the shape so the
  named edge sits on the revolution axis, then compensates the mesh
  position. Use for bend/corner geometry: it keeps holes intact (no
  axis clipping).

## Example

{{example: revolve-basic}}

<!-- example-source: revolve-basic -->
```clojure
(register vase
  (revolve (path-to-shape (path (f 5) (th 80) (f 15) (arc-h 5 -160) (f 15)))))
```
<!-- /example-source -->

A vase: a turtle silhouette is captured as a path, projected to 2D, and
revolved a full 360° around the turtle's up axis.

## Variations

{{example: revolve-partial}}

<!-- example-source: revolve-partial -->
```clojure
(register half-bowl (revolve (rect 20 5) 180))
```
<!-- /example-source -->

A partial revolution sweeps the profile through an explicit angle —
useful for half-symmetric shells.

{{example: revolve-shape-fn}}

<!-- example-source: revolve-shape-fn -->
```clojure
(register organic (revolve (noisy (circle 15 64) :amplitude 2 :scale 4)))
```
<!-- /example-source -->

A shape-fn varies the profile during the revolution. Here `noisy`
displaces points to create an organic surface.

{{example: revolve-pivot}}

<!-- example-source: revolve-pivot -->
```clojure
(register elbow (revolve (translate-shape (circle 5) 15 0) 90 :pivot :left))
```
<!-- /example-source -->

The `:pivot` option moves the shape's left edge onto the axis before the
revolution, so a torus-like elbow is built without clipping any holes.

## Notes

- The rotation axis is the turtle's **up** vector — not heading. To
  revolve around a different direction, tilt the turtle pose with
  `(tv …)` before the call.
- Profiles with vertices at `x < 0` are clipped at the axis (unless
  `:pivot` is used). Use `translate-shape` to shift the profile outward
  for hollow tori.
- Shape-fns parameterised by `t` apply to revolution the same way they
  do to `loft`: `t = 0` at the first ring, `t = 1` at the last.
- For chainable bend/corner geometry, see `revolve+` and `transform->`.

## See also

- **Guide:** placeholder → cap. 4 (Estrusione) e cap. 6 (Da funzioni
  matematiche a forme)
- **Related:** `extrude`, `loft`, `revolve+`, `transform->`,
  `path-to-shape`, `translate-shape`
