---
name: edit-image-board
category: live-interactive
since: ""
status: stable
---

# edit-image-board

## Signature

`(edit-image-board path)`
`(edit-image-board path scale [imx imy] [orx ory] [w h])`

## Description

Interactive editor for an `image-board` — place, scale and crop a reference photo
with on-screen handles and a calibration ruler, then trace it with `edit-path-2d`.
Open it from the **definitions panel** (Cmd+Enter), not the REPL, and wrap the form
in `(stamp …)` to see the image. On **OK** the `(edit-image-board …)` marker is
rewritten to `(image-board path scale [imx imy] [orx ory] [w h])` with the values
you set; on **Esc** it is restored. Like `tweak` / `edit-path-2d`, one modal runs at
a time and the editor is read-only while open.

The first call `(edit-image-board path)` sizes the rect to the whole image
(aspect-fit, turtle-centred), with a default scale. To re-edit a board, **rename
`image-board` → `edit-image-board`**: the editor reads its six values back.

## Parameters

- `path` — absolute image file path (desktop only — read through the Rust server).
- `scale [imx imy] [orx ory] [w h]` — optional; the same six values as
  `image-board`, supplied to re-open an existing board.

## Mouse & keys

- **Scale ruler** — a yellow ✕–✕ segment is open by default. Drag each **✕** end
  onto the two ends of a feature of **known length**, type that length in the
  **feature len** field, and press **set scale**. Recompute is explicit (the
  button), never automatic on drag, so the photo never rescales under the cursor.
  The ✕ ends are anchored to the image, so after a rescale they stay on the feature
  and scale with it.
- **Drag the rect body** — move it relative to the turtle (`orx`/`ory`); the photo
  rides along, so the framing is kept.
- **Alt+drag inside the rect** — pan the photo under the crop window (`imx`/`imy`).
- **Drag a corner** (cyan handle) — resize the crop (`w`/`h`); the photo stays put.
- **White ✛** — marks the turtle `[0 0]`, i.e. the **creation pose** of whatever you
  extrude from the traced outline (display only).
- **Panel number fields** mirror every value (`scale`, `imx`/`imy`, `orx`/`ory`,
  `w`/`h`) for fine-tuning by hand.
- **Enter** — OK; **Esc** — cancel. Dragging on empty space (outside the rect) still
  orbits the camera.

## Example

<!-- example-source: edit-image-board-basic :no-run :warning desktop-only -->
```clojure
;; Calibrate + frame a photo interactively, then trace and extrude.
(stamp (edit-image-board "/Users/me/ref/part.jpg"))
;; …on OK → (stamp (image-board "/Users/me/ref/part.jpg" 120 [0 0] [-60 -40] [120 80]))

(register part
  (extrude (path-to-shape (edit-path-2d) :preserve-position true) (f 4)))
```
<!-- /example-source -->

## Notes

- The board is `preserve-position?`, so `[orx ory]` controls where the turtle sits
  inside it; combined with `(path-to-shape … :preserve-position true)` the extruded
  mesh's creation pose lands on that turtle point — generally *off* the contour.
- **Not a persistent primitive.** Confirm bakes `(image-board …)`; re-running does
  not re-open editing. Rename `image-board` → `edit-image-board` to edit again.
- **Desktop only** — the photo loads through the Rust server.

## See also

- **Related:** `image-board`, `set-image`, `edit-path-2d`, `path-to-shape`, `ruler`
