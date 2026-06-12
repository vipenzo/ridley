---
name: loft
category: generative-operations
since: ""
status: stable
---

# loft

## Signature

`(loft shape-fn & path-commands)`
`(loft shape transform-fn & path-commands)`
`(loft start-shape end-shape & path-commands)`
`(loft-n n shape-fn & path-commands)`
`(loft-n n shape transform-fn & path-commands)`

## Description

Sweep a 2D profile along a path while letting the profile change shape from
one end to the other. Returns a single mesh; does not modify turtle state.

`loft` walks the path in 16 steps by default. Use `loft-n` to override the
step count.

The profile can vary in three ways:

- **shape-fn mode** (preferred). Pass a shape-fn — a function tagged
  `{:type :shape-fn}` that maps `t ∈ [0, 1]` to a shape. Built-in shape-fns
  (`tapered`, `twisted`, `fluted`, `rugged`, `noisy`, `morphed`, `shell`, …)
  compose via `->` threading, so a single profile expression can fold
  several transformations together.

- **two-shape mode**. Pass a start shape and an end shape with the same point
  count. The loft linearly interpolates between them at each ring.

- **transform-fn mode**. Pass a plain shape and a function
  `(fn [shape t] -> shape)` that produces the ring at each step. The most
  flexible form, useful when the transformation does not fit a reusable
  shape-fn — for example when it depends on external state, samples a
  texture, or mixes per-ring logic that is too specific to package.

For variants closely related to `loft`, see:

- `loft-n` — same as `loft` with explicit step count (variant page).
- `loft-between` — alias of the two-shape mode (variant page).

## Parameters

- `shape-fn` — a shape-fn (a function with metadata `{:type :shape-fn}`). See
  the **shape-fn contract** in Internals.
- `shape` — a 2D shape used as the starting profile (transform-fn mode).
- `transform-fn` — a function `(fn [shape t] -> shape)`, where `t` goes from
  `0` at the start of the path to `1` at the end.
- `start-shape`, `end-shape` — 2D shapes with the same point count
  (two-shape mode). Use `resample-shape` to match counts when they differ.
- `path-commands` — one or more turtle movement commands (e.g. `(f 30)`,
  `(th 45)`, `(arc-h 20 90)`), or a recorded path. Multiple commands are
  auto-wrapped in a `path`.
- `n` (for `loft-n`) — explicit number of steps along the path.

## Example

{{example: loft-tapered-cone}}

A cone is the simplest loft: a circle that tapers to zero along a straight
path. The `tapered` shape-fn carries the scaling instruction inside the
profile; the loft only has to walk the path.

## Variations

{{example: loft-twisted-rectangle}}
A rectangle that rotates 90 degrees as it extrudes. `twisted` is a shape-fn
that progressively rotates the profile.

{{example: loft-fluted-column}}
Shape-fns compose. The profile is a fluted, tapering circle: `fluted` adds
longitudinal grooves, `tapered` shrinks the result, `->` threads the two
together. The loft walks a straight path; the profile does all the work.

{{example: loft-between-shapes}}
Two-shape mode. The loft interpolates between a circle and a smaller circle
along the path. Both shapes must have the same point count — `resample-shape`
is the escape hatch when they do not.

{{example: loft-transform-fn-mode}}
Transform-fn mode. The function receives the base shape and `t ∈ [0, 1]`
and returns the ring for that step. Used here to scale and rotate together
in a single inlined expression.

## Notes

- `loft` evaluates the profile at each step; `extrude` does not. If the
  profile is static, prefer `extrude` — it is faster.
- Default step count is 16. Increase via `loft-n` when the profile changes
  rapidly along the path (a steep taper, a high-frequency rugged profile)
  and 16 rings produce visible facets.
- Profiles vary with `t`, not with turtle pose. A shape-fn cannot read the
  turtle's heading or position. If the profile needs the absolute path
  length (rather than the normalised fraction `t`), the dynamic var
  `*path-length*` is bound for the duration of the loft. See the **shape-fn
  contract** in Internals.
- A profile expressed as a vector of shapes is allowed: the loft extrudes
  each shape independently along the same path and merges the results into
  a single mesh, so downstream booleans need no manual `concat-meshes`.
- Joint modes (`(joint-mode :flat | :round | :tapered)`) apply to `loft` as
  they do to `extrude` and control corner geometry where the path turns.
- **At a sharp corner the profile is frozen across the joint.** `loft` splits
  the path at real corners (`th`/`tv`/`tr`) into separate segment meshes joined
  by a miter, and a miter must share one cross-section on both sides — so the
  profile is held constant at the corner's `t` value through the bend instead of
  progressing. The taper (or any shape-fn variation) pauses across the joint; the
  constant-shape zone grows with the turn angle (the miter scales as
  `tan(angle/2)`), so it is negligible for gentle turns and conspicuous at sharp
  ones. Smooth curves (`arc-h`/`arc-v`, beziers) are not treated as corners and
  do not trigger this. `extrude` is unaffected — its profile is static anyway.
- For paths with tight bezier curves where rings would self-intersect,
  smooth the path first (`bezier-as`, `arc-h`, `joint-mode :round`); `loft`
  has no built-in escape hatch for genuinely overlapping rings.
- **Profile marks become mesh anchors and ride the morph.** A marked
  `path-to-shape` profile carries its marks as point-index references, so a
  mark stays on its corner as the profile tapers/twists; `(move-to mesh :at
  :mark …)` reaches it, and `(slice-mesh mesh :on t)` hands back the profile
  at station `t` with the marks intact. See `path-to-shape` and `slice-mesh`.

## See also

- **Guide:** [6. From mathematical functions to shapes](../../guides/06-shape-fn/index.md)
- **Internals:** [shape-fn contract](../../internals/contracts/shape-fn.md)
- **Related:** `extrude`, `extrude-closed`, `revolve`, `loft-n`, `loft-between`
- **Composable shape-fns:** `tapered`, `twisted`, `fluted`, `rugged`, `noisy`, `morphed`, `shell`, `woven-shell`, `profile`, `heightmap`, `displaced`