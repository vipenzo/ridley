---
name: set-heading
category: turtle-movement
since: ""
status: stable
---

# set-heading

## Signature

`(set-heading [hx hy hz] [ux uy uz])`

## Description

Set the turtle's frame **absolutely** inside a `(path …)`: `heading` is the new
forward direction and `up` the new up direction (the right vector is derived as
`heading × up`). Unlike the relative turns `th` / `tv` / `tr`, which rotate the
current frame, `set-heading` replaces it outright.

```clojure
(def rail
  (path (set-heading [0 0 1] [1 0 0]) (f 30)    ; go straight up, up-axis = +X
        (set-heading [1 0 0] [0 0 1]) (f 20)))   ; turn to +X, up-axis = +Z
```

It is mainly used by **generated** paths rather than hand-written ones:
`reverse-path`, `mirror-path`, [`ensure-untwisted`](#ensure-untwisted) and the 3D
`edit-path` bake all emit `(set-heading …)(f …)` per segment, because carrying an
explicit per-segment frame lets them control the swept-section orientation exactly
(e.g. a twist-free rail). For tracing a path by hand, prefer the relative turns —
they read better and compose from any pose.

## Parameters

- `[hx hy hz]` — the new heading (forward) vector. Normalized internally.
- `[ux uy uz]` — the new up vector. Normalized internally; should not be parallel
  to `heading` (the right axis would be undefined).

## Notes

- `set-heading` is **absolute**: it does not depend on the current frame, so two
  consecutive `set-heading`s ignore the rotation between them. This is what makes a
  generated rail's frame reproducible regardless of how it is placed.
- A path is still **pose-less / relative**: `set-heading` sets the frame relative to
  the consumption pose, not in world space — `follow-path` / extrude place it from
  the current turtle pose like any path.

## See also

- `th`, `tv`, `tr` — relative turns (the usual way to steer by hand)
- [`ensure-untwisted`](#ensure-untwisted) — re-frames a rail with explicit
  `set-heading` for a twist-free sweep
- `reverse-path`, `mirror-path`
