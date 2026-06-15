# Brief: `path-2d` species + `ensure-path-2d` normalizer

Status: **design agreed & validated in REPL (2026-06-15), NOT yet built.**
High blast-radius (touches `path-to-shape`, a core consumer, and the `macro-defs`
string). Build in a focused session.

## Problem

Ridley conflates two path species in one `{:type :path}`:

- a **3D rail** — turtle in space, consumed in its own frame (extrude-from-path,
  loft). Correct.
- a **2D profile** — planar, consumed as a shape (path-to-shape → stamp/extrude).
  Feels "rotated".

The 2D friction: a `path` traced with `f/th` lives in the **(heading, right)** plane
(default XY), but a **shape** embeds via `right = heading × up` and lives in the
**(right, up)** plane (default YZ). Different planes (they share only `right`).

Important (verified): there is **no per-use rotation in the path data**.
`path-to-shape` just extracts `(x,y)`; the "YZ feel" is the one-time shape embed.

## What a path actually is (correcting an earlier mistake)

A `path` is **pose-less / relative**. It records commands in a local frame
(`[0,0,0]`, heading `[1,0,0]`). Absolute placement is supplied by the **consumer at
use time**: `stamp(path-to-shape P)` stamps at the current turtle pose; `follow-path
P` replays from the current turtle pose. `path-2d` is the same — it does **not** carry
an absolute pose.

So "path-2d carries the pose" is wrong. The correct statement:

> `path-2d` carries only its **relative plane**: its trace lies in the `(right, up)`
> plane of its frame (the same plane the shape stamps into). The absolute pose is
> supplied by the consumer, exactly like a normal path.

## Design (non-breaking)

- `path` unchanged + label `:3d`.
- new **`path-2d`**: records its trace in the `(right, up)` plane by seeding
  `heading = right` of the local frame and turning with `tv`/`arc-v`. Labels `:2d`.
  `edit-path` produces it.
- **`ensure-path-2d p`** normalizes at the **planar-consumer** boundary
  (path-to-shape, stroke-shape, 2D booleans, edit-path):
  - `:2d` → identity (trace already in (right,up); extract shape coords as
    `(proj on right, proj on up)`).
  - `:3d` → today's `(x,y)` extraction (legacy → non-breaking).
  - **RAIL** consumers (extrude-from-path, loft) keep the 3D path — not forced to 2D.

Same normalize-at-boundary pattern as the deferred `ensure-path` (for `:pure-path`
curve recovery — see memory `project_edit_path_reedit`).

## Why `tv`/`arc-v`, not `th`/`arc-h`

With `heading = right`, `f/tv/arc-v` trace in the `(right, up)` = stamp plane → trace
and shape coincide. `th` would trace the wrong plane. (`th` and `tv` produce the SAME
shape with matched coord extraction — the choice is which plane the trace lands in +
ergonomics.)

## Command semantics inside `path-2d` (forgiving, and required for planarity)

A plane has only **2 translation dof**: forward (`f`) and one perpendicular
(strafe). There is no independent third direction, so `rt/lt` and `u/down` cannot be
distinct axes in 2D — they collapse onto the single strafe axis. Also, in `path-2d`'s
frame (heading and up lie IN the plane) the turtle's `right = heading × up` is the
**plane normal**, so native `rt`/`lt` (along right) and `tr` (roll around heading)
would LEAVE the plane. Folding them in keeps `path-2d` genuinely planar.

So inside `path-2d`:

- **Rotation**: `th` = `tv` = `tr` → the single in-plane turn. **positive = left**,
  negative = right.
- **Strafe**: `rt` = `u` → strafe **right** (positive); `lt` = `down` → strafe
  **left** (positive).
- **Forward**: `f` (and `b` / negative = backward), unchanged.
- **Arcs**: `arc-h` = `arc-v` → the single in-plane arc (sign = direction).

Outside `path-2d` (i.e. `:3d` `path`) everything stays distinct (th/tv/tr/rt/lt/u).

## Acceptance invariant (executable spec)

```clojure
(def P (path-2d (dotimes [_ 4] (f 10) (tv 90))))
;; assert: world points of (follow-path P) == world points of (stamp (path-to-shape P))
```

Today this is broken (path in XY, shape in YZ — perpendicular).

## Validation done (REPL, no production change)

Commands seeded `set-heading [0 -1 0] [0 0 1]` then `f/tv` stay at `x=0` (the YZ =
(right,up) plane): `[0,0,0] [0,-10,0] [0,-10,5] [0,0,5]`. So projection onto
`(right,up)` is lossless and re-embed is identity → the invariant holds **by
construction**.

## Build plan (next session)

1. `path-2d` macro: seed pose so the trace is in `(right, up)`. NOTE: `set-heading`
   is **not bound inside `(path …)`** today — `path-2d` must seed the heading itself.
2. `ensure-path-2d`: 3D-trace (via `path-to-3d-waypoints`, which DOES handle
   `tv`/`set-heading`) + project onto carried `(right, up)`; identity on `:2d`;
   legacy `(x,y)` on `:3d`. NOTE: `path-to-shape` is currently a 2D-only (`th`)
   tracer that **ignores `tv`/`arc-v`** — that's why this needs a real 3D
   trace+project, not `(x,y)` extraction.
3. Route `path-to-shape` / `stroke-shape` through `ensure-path-2d`.
4. The shape must carry enough that `stamp` places it to satisfy the invariant
   (both follow and stamp use the same consumption pose).
5. `follow-path` of a `:2d` replays so its trace lands in `(right, up)` of the
   consumption pose; assert the invariant as a test.
6. Then: `edit-path` emits `:2d`; 3D-path-editing design resumes from here.

## Related

- memory `project_path_2d_3d.md` (this design)
- memory `project_edit_path_reedit.md` (`:pure-path` / `ensure-path` — same pattern)
- memory `project_stroke_self_intersection.md` (known stroke limitation)
