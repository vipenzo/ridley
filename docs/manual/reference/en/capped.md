---
name: capped
category: generative-operations
since: ""
status: stable
---

# capped

## Signature

`(capped shape-or-fn radius & {:keys [mode start end fraction end-radius preserve-holes]})`

## Description

Shape-fn that adds a fillet or chamfer transition at the start and/or end
of an extrusion. Insets the 2D profile near the cap and transitions
smoothly to the full shape using a quarter-circle (`:fillet`) or linear
(`:chamfer`) easing. Used with `loft` (not `extrude`, which has a static
profile). Does not modify turtle state.

The transition `fraction` is auto-calculated from the path length as
`radius / path-length` so the fillet is geometrically correct. Override
with `:fraction` when needed. The radius is clamped to the shape's
inradius internally to prevent degenerate geometry.

Negative `radius` expands the shape at the caps instead of shrinking it —
useful for reinforcement fillets at the base.

## Parameters

- `shape-or-fn` — a 2D shape, or another shape-fn (composes).
- `radius` — fillet / chamfer distance. Positive shrinks at caps,
  negative expands.
- `:mode` — `:fillet` (default, quarter-circle easing) or `:chamfer`
  (linear easing).
- `:start` — apply at `t = 0` (default `true`).
- `:end` — apply at `t = 1` (default `true`).
- `:fraction` — override the auto-calculated transition fraction
  (default `0.08` when path length is unknown).
- `:end-radius` — different radius at the end (default: same as
  `radius`).
- `:preserve-holes` — when `true`, holes are not inset; only the outer
  boundary transitions (default `true`).

## Example

{{example: capped-fillet}}

<!-- example-source: capped-fillet -->
```clojure
(register rounded-box (loft (-> (rect 40 20) (fillet-shape 5) (capped 3)) (f 50)))
```
<!-- /example-source -->

A box with rounded 2D corners (via `fillet-shape`) and rounded 3D cap
edges (via `capped`). The two operations compose: 2D corner rounding
runs along the extrusion direction, cap rounding works on the top/bottom
faces.

## Variations

{{example: capped-chamfer}}

<!-- example-source: capped-chamfer -->
```clojure
(register pill (loft (-> (rect 40 20) (capped 3 :mode :chamfer)) (f 50)))
```
<!-- /example-source -->

Chamfer mode replaces the quarter-circle easing with a linear ramp:
flat-cut cap edges instead of rounded.

{{example: capped-end-only}}

<!-- example-source: capped-end-only -->
```clojure
(register drop (loft (-> (circle 20) (tapered :to 0.3) (capped 2 :end false)) (f 40)))
```
<!-- /example-source -->

Apply the cap fillet at the start only, leaving the tip sharp. The
tapered profile + start-cap fillet produces a teardrop.

{{example: capped-asymmetric}}

<!-- example-source: capped-asymmetric -->
```clojure
(register tank (loft (capped (circle 25) -3 :end-radius 4) (f 50)))
```
<!-- /example-source -->

`radius -3` expands the start cap (reinforcement fillet at the base),
`:end-radius 4` shrinks the top — asymmetric profiles in a single
`capped` call.

## Notes

- `capped` works with `loft`, not `extrude`. `extrude` has a static
  profile, so a varying-radius transition isn't expressible there.
- The cap transition uses centroid-anchored scaling: shape proportions
  (including any 2D fillet radii) are preserved.
- For 2D corner rounding (along extrusion direction), use `fillet-shape`
  or `chamfer-shape`. The two operations are orthogonal and compose.
- Compose with all shape-fns via `->` threading.

## See also

- **Related:** `fillet-shape`, `chamfer-shape`, `loft`, `shape-fn`,
  `tapered`
