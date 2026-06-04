---
name: mark
category: path
since: ""
status: stable
---

# mark

## Signature

`(mark name)` — available inside `path` / `extrude` / `loft` / `attach`
bodies.

## Description

Record a named pose inside a path recording. Marks have no geometric
effect: on replay they capture the turtle's position and orientation at
the point they appear in the command stream, and that snapshot is later
exposed as an anchor by `with-path` or as a 2D coordinate by `mark-pos`
/ `mark-x` / `mark-y`.

`mark` is a recording command — it is only meaningful inside a `path`
recorder. Outside, the symbol is not bound.

## Parameters

- `name` — a keyword (or any value usable as a map key) identifying the
  mark. Marks are stored in the path map and can be queried later by
  name.

## Example

{{example: mark-basic}}

<!-- example-source: mark-basic -->
```clojure
(def arm
  (path
    (f 30) (mark :elbow)
    (th 45) (f 20) (mark :hand)))

(register skeleton (extrude (circle 1) arm))
```
<!-- /example-source -->

Two marks are dropped along an arm path. They can later be resolved as
named anchors (with `with-path`) or queried in 2D coordinates (with
`mark-pos`).

## Variations

{{example: mark-with-side-trip}}

<!-- example-source: mark-with-side-trip -->
```clojure
(def skel
  (path
    (mark :origin)
    (f 50)
    (side-trip (th 90) (f 27) (tv -90) (f 37) (mark :branch))
    (mark :tip)))

(register layout (extrude (circle 0.8) skel))
```
<!-- /example-source -->

Marks survive `side-trip`: the spine never advances during the
side-trip, but `:branch` is captured at the off-axis position the
sub-path reaches.

## Notes

- A mark records the turtle's full pose (position, heading, up) — not
  just position. This matters when resolving the mark as an anchor:
  `move-to` and `with-path`'s anchor table will restore both position
  and orientation.
- Mark names are not unique within a path: redefining the same name
  overwrites the earlier capture. Use distinct names when both points
  are needed.
- Marks inside a `follow`ed sub-path are spliced into the parent path
  exactly as their commands are. Inside a `side-trip`, the marks are
  kept but the spine position is restored on exit (the natural pattern
  for "drop a tag off to the side, then keep walking").

## See also

- **Related:** `path`, `with-path`, `mark-pos`, `mark-x`, `mark-y`,
  `anchors`, `move-to`, `side-trip`, `follow`
