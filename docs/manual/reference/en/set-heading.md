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
`reverse-path` / `mirror-path` emit `(set-heading …)(f …)` per segment. For tracing
a path by hand, prefer the relative turns — they read better and **compose under
the consumption pose**.

⚠️ **Composition caveat.** Because `set-heading` is absolute, a path that uses it
follows the consumption pose's **translation** (it starts at the turtle's position)
but **not its rotation** — the heading snaps to the literal vectors regardless of
how the turtle is oriented. A purely relative path (`th`/`tv`/`tr`) rotates *and*
translates with the pose. That's why the 3D `edit-path` bake and
[`ensure-untwisted`](#ensure-untwisted) use **relative** turns (a `tr` roll for the
twist-free frame), not `set-heading`: a rail placed via `attach` / `on-anchors` /
after a turtle rotation must rotate with its pose.

## Parameters

- `[hx hy hz]` — the new heading (forward) vector. Normalized internally.
- `[ux uy uz]` — the new up vector. Normalized internally; should not be parallel
  to `heading` (the right axis would be undefined).

## Notes

- `set-heading` is **absolute**: it ignores the current frame, so any prior turn
  (or the consumption pose's orientation) is overwritten. Handy for a fixed
  reference orientation; the trade-off is the composition caveat above.
- Position still follows the consumption pose: `set-heading` doesn't move the
  turtle, and `f` advances from wherever it is, so the rail starts at the pose's
  position (only the orientation is absolute).

## See also

- `th`, `tv`, `tr` — relative turns (the usual way to steer by hand)
- [`ensure-untwisted`](#ensure-untwisted) — re-frames a rail with explicit
  `set-heading` for a twist-free sweep
- `reverse-path`, `mirror-path`
