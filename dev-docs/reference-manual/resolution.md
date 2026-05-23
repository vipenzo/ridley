---
name: resolution
category: turtle-movement
since: ""
status: stable
---

# resolution

## Signature

`(resolution :n value)`
`(resolution :a value)`
`(resolution :s value)`

## Description

Set the global resolution mode for curves and circular primitives.
**Modifies turtle state.** The setting persists on the turtle until
changed again.

Three modes, mutually exclusive:

- **`:n`** — fixed number of segments per full revolution (default
  `16`).
- **`:a`** — maximum angle per segment, in degrees. Smaller values
  produce smoother curves on tight turns.
- **`:s`** — maximum segment length, in world units. Smaller values
  produce smoother curves at large radii.

## Affected operations

- `arc-h`, `arc-v` — step count along the arc.
- `bezier-to`, `bezier-to-anchor` — step count along the curve.
- `bezier-as` — per-segment step count when smoothing a path.
- `circle` (1-arity) — segment count.
- `sphere`, `cyl`, `cone` — circumferential segments.
- `revolve` — number of revolution segments (and rings for shape-fn
  revolves).
- `bloft` — ring count along the path.
- Round joints during extrusion (`(joint-mode :round)`).
- SDF meshing — voxels-per-unit derived from the resolution.

**Not affected:** plain `loft` and `extrude` do **not** derive their
step count from `resolution`. `loft` defaults to 16 rings and accepts
`loft-n` for overrides; `extrude` produces one ring per `f` segment.

Every individual call also accepts an explicit override (e.g.
`(arc-h r a :steps n)`, `(circle r segments)`, `(sphere r segments rings)`)
that bypasses the resolution setting for that call.

## Parameters

- `:n value` — integer segment count per full revolution.
- `:a value` — angle per segment, in degrees.
- `:s value` — maximum segment length, in world units.

## Example

{{example: resolution-n}}

<!-- example-source: resolution-n -->
```clojure
(resolution :n 64)            ;; smoother circles and arcs for the rest of the session
(register smooth-ball (sphere 20))
```
<!-- /example-source -->

Set a fixed segment count; subsequent primitives inherit it.

## Variations

{{example: resolution-a}}

<!-- example-source: resolution-a -->
```clojure
(resolution :a 5)             ;; max 5° per segment
(arc-h 100 90)                 ;; many steps because the radius is large
(arc-h 5 90)                   ;; fewer steps for a small arc
```
<!-- /example-source -->

`:a` adapts the step count to the angular extent — long arcs at small
radii reuse the same density as short arcs at large radii.

{{example: resolution-s}}

<!-- example-source: resolution-s -->
```clojure
(resolution :s 0.5)           ;; max 0.5-unit segment length
(arc-h 100 90)                 ;; many steps (long arc)
(arc-h 5 90)                   ;; few steps (short arc)
```
<!-- /example-source -->

`:s` adapts to the absolute segment length — useful when fidelity
should depend on world-space size.

## Notes

- Each `:n` / `:a` / `:s` call overrides the previous mode entirely.
- Per-call overrides (`:steps`, explicit segment counts on
  constructors) take priority over the global setting.

## See also

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `arc-h`, `arc-v`, `bezier-to`, `circle`, `sphere`,
  `cyl`, `cone`, `revolve`, `loft-n`, `bloft`
