---
name: ensure-untwisted
category: path
since: ""
status: stable
---

# ensure-untwisted

## Signature

`(ensure-untwisted path)`

## Description

Re-frame a 3D rail so a sweep along it does **not twist**. The node positions are
kept exactly; only the turtle's `up` is recomputed, by **parallel transport** (a
rotation-minimizing frame), and the rail is rebuilt as `(set-heading …)(f …)` per
segment.

```clojure
;; a hand-written non-planar rail whose extruded tube spirals:
(def rail (path (tv 90) (f 20) (th -60) (tv 50) (f 25) (th 40) (tv -70) (f 20)))
(register tube (extrude (circle 3) (ensure-untwisted rail)))   ; no spiral
```

### The phenomenon it corrects

`extrude` / `loft` orient the swept cross-section by the **turtle's `up`** at each
point along the rail. On a **non-planar** rail (one whose bends don't all lie in a
single plane) that `up` slowly rotates around the heading as you go — a geometric
effect called *holonomy*. Because the mesh connects each section ring to the next
vertex-by-vertex, that rotation shows up as the tube **spiralling / twisting**
(and, in the extreme, pinching). `ensure-untwisted` replaces the rolled `up` with a
parallel-transported one, which rotates *only* as much as the rail bends — never
incidentally — so the section stays put.

For a **planar** rail there is no holonomy, so `ensure-untwisted` changes nothing.

## Parameters

- `path` — a path used as a sweep rail. Its traced waypoints supply the positions
  (a curved rail becomes its tessellated polyline).

## Notes / known limitation

- This is the **manual remedy** for the twist. `extrude` itself does **not** apply
  it automatically (that would be a core change with wider impact), so a
  hand-written non-planar rail can still twist until you wrap it in
  `ensure-untwisted`. Paths baked by the 3D `edit-path` already carry a twist-free
  frame, so they don't need it.
- It rebuilds the rail with `set-heading` per segment, so any `arc` / `bezier`
  curvature becomes its tessellated polyline (the shape is preserved, the curve
  metadata is not).
- Related to `:preserve-up` (used by `text-on-path`), which keeps the section
  aligned to a *fixed reference* up. `ensure-untwisted` uses parallel transport
  instead — no fixed reference, so it stays well-defined even where the rail runs
  along that reference (e.g. vertical), where `:preserve-up` would degenerate.

## See also

- [`set-heading`](#set-heading) — the per-segment frame it emits
- `extrude`, `loft` — the rail consumers that orient by `up`
