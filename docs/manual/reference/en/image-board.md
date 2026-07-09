---
name: image-board
category: 2d-shapes
since: ""
status: stable
---

# image-board

## Signature

`(image-board path)`
`(image-board path scale-factor)`
`(image-board path scale-factor [imx imy])`
`(image-board path scale-factor [imx imy] [orx ory])`
`(image-board path scale-factor [imx imy] [orx ory] [w h])`

All arguments after `path` are optional, with defaults resolved by a single chain (see Parameters).

## Description

Build a rectangular **tracing board** carrying a reference photo, ready to trace
over with `edit-path-2d`. It is a convenience wrapper over `set-image` on a
`preserve-position?` rectangle: unlike a bare `(set-image (rect …) …)`, the board
keeps the **turtle fixed at `[0 0]`**, so stamping it places the rect relative to
the turtle by `[orx ory]` and leaves the turtle exactly on the point that will
become the extruded mesh's **creation pose** — typically a point *off* the contour
you trace.

The photo rides on the shape's `:image` attribute, so it shows only when the board
is `stamp`ed, UV-mapped onto the rect and clipped to it. **Desktop only** (the image
bytes are read through the Rust server; the web build shows nothing).

## Parameters

- `path` — absolute image file path (PNG / JPG / …).
- `scale-factor` — image width in Ridley units (calibrates scale — verify with
  `ruler`, or set it interactively with `edit-image-board`). Height follows the
  image aspect ratio. **Default: 100.**
- `[imx imy]` — image lower-left corner **relative to the rect's lower-left**.
  Frames which part of the photo shows; because it is relative to the rect, moving
  `[orx ory]` carries the image along, so the framing is preserved. **Default: `[0 0]`.**
- `[orx ory]` — rect lower-left corner **relative to the turtle**. **Default:
  turtle-centred, `[(- (/ w 2)) (- (/ h 2))]` on the resolved `[w h]`.**
- `[w h]` — rect (crop window) dimensions. **Default: `[scale scale]`.**

Malformed arguments (a non-positive scale, a vector of the wrong shape, non-numeric
values) throw an error naming the offending argument — an incomplete call can never
produce a silently broken shape. `edit-image-board` uses the same resolver, so its
defaults and these are one and the same.

## Example

<!-- example-source: image-board-basic :no-run :warning desktop-only -->
```clojure
;; A photo on a 200×100 board, turtle centred, full image shown — trace over it.
(stamp (image-board "/Users/me/ref/part.jpg" 200 [0 0] [-100 -50] [200 100]))

(register part
  (extrude (path-to-shape (edit-path-2d) :preserve-position true) (f 4)))
```
<!-- /example-source -->

## Notes

- Pair the trace with `(path-to-shape outline :preserve-position true)` (or
  `stroke-shape … :preserve-position true`) so the extruded mesh's creation pose
  lands on the turtle `[0 0]` you framed, not on the first traced vertex.
- `edit-image-board` is the interactive way to set all six values — drag handles to
  move / crop / pan, a two-click ruler to calibrate scale — and it bakes an
  `(image-board …)` call.
- The image is a viewport aid only: it is **not exported**, not part of mesh CSG,
  and (for now) does not survive `extrude` into the mesh.

## See also

- **Related:** `edit-image-board`, `set-image`, `path-to-shape`, `edit-path-2d`,
  `ruler`, `stamp`
