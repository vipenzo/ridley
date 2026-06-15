---
name: edit-path
category: live-interactive
since: ""
status: stable
---

# edit-path

## Signature

`(edit-path)`
`(edit-path (move-to [x y]) (th a) (f d) …)`

## Description

A **pen tool** for tracing a polyline interactively — draw over a reference image
(see `set-image`) and clip the piece you need. `edit-path` wraps a path body and
opens an interactive session from the **definitions panel** (Cmd+Enter), not the
REPL.

Unlike `edit-bezier`, `edit-path` is **not** a persistent primitive. On confirm it
rewrites its `(edit-path …)` marker to a plain `(path …)`, so re-running the script
does **not** re-enter editing. To edit an existing path again, **rename
`path` → `edit-path`**: the editor reads the body's nodes back (a leading `move-to`
is honored).

Empty `(edit-path)` starts from a small triangle so the downstream is valid and
there is something to drag; click to add your own nodes and delete the rest.

The baked path is anchored with a leading `(move-to [x0 y0])`, so `path-to-shape`
seeds the trace from the **absolute** start point (no spurious `[0 0]` vertex) and
the traced shape lands in the same 2D frame as the board it was drawn over —
`(shape-intersection board (path-to-shape (edit-path …)))` clips correctly.

Nodes are edited in the **turtle's stamp plane** at the call site (x-axis =
`right` = heading × up, y-axis = `up`) — the same 2D frame the board uses. With the
default pose that is the **YZ world plane**: horizontal arrows move along world Y,
vertical arrows along Z, and world X is never touched.

MVP traces **straight segments** only; per-segment arcs and beziers are planned.

## Parameters

- body — an optional path body (turtle commands) to seed the nodes. A leading
  `(move-to [x y])` sets the absolute start. Empty opens a default triangle.

It does **not** require a reference image: clicks land on the turtle's working
plane, so `edit-path` works as a standalone polygon / region drawing tool. A
`set-image` board, when present, just makes a convenient backdrop to trace.

## Mouse & keys

- **Click a segment** — insert a node there (splits the segment).
- **Click elsewhere** — append a node at the end of the path.
- **Drag a node** — move it. Orbiting still works: only grabbing a node or a
  segment suppresses the orbit for that drag; a drag on empty space orbits.
- `Tab` — cycle the selected node.
- Arrows — nudge the selected node in the working plane (`←`/`→` = the plane's
  horizontal = world Y by default, `↑`/`↓` = vertical = world Z); world X (off the
  image plane) is never touched.
- `Shift`+arrows — nudge the selected bezier node's **start handle (c1)**;
  `Alt`+arrows — nudge its **end handle (c2)** (`Ctrl`/`Cmd` are reserved by macOS
  for switching spaces). Both re-apply the tangent constraint after the move (a
  smooth c1 stays on its tangent — only its length changes). No-op on a node
  without a bezier segment.
- Type digits — set the step size (mm); `Backspace` edits the buffer.
- `c` — toggle the selected node's **incoming segment** between a straight line and
  a **cubic bezier**. A bezier shows two control handles (small squares, to set them
  apart from the round nodes); it bakes to a compact
  `(bezier-to …)`. Handles are **directional**: the start handle (c1) stays tangent
  to how the path arrives at the start node (you only set its length — it slides
  along that line), so curves join smoothly; the end handle (c2) is free and sets
  the entry direction into the next node.
- `x` — toggle the selected node **smooth ↔ cusp**. A cusp frees the node's
  **outgoing** handle (shown magenta) so the curve can leave at any angle.
- `Delete` — remove the selected node (green nodes carrying a mark/side-trip are
  protected and won't be deleted).
- `Enter` — confirm; `Esc` — cancel.

While the session is open the reference image is **dimmed** and the overlay is drawn
on top, so the trace reads even over a light image.

## Example

<!-- example-source: edit-path-basic :no-run :warning desktop-only -->
```clojure
;; Trace a region over a stamped board image, then clip that piece out.
(def board (set-image (rect 200 100) "/Users/me/ref/photo.jpg" 200 -100 -50))
(stamp board)

(register cut
  (extrude (shape-intersection board (path-to-shape (edit-path))) (f 4)))
```
<!-- /example-source -->

Open from the definitions panel; click around the detail you want, confirm, and the
`(edit-path)` marker becomes a plain `(path (move-to …) (th …)(f …) …)` that clips
the photo region.

## Notes

- **Segment types.** A straight segment bakes to `f` / `rt` / `lt` / `th`+`f`; a
  **cubic bezier** segment (press `c` on the destination node) bakes to a compact
  `(bezier-to …)`. Node positions and heading come from `f`, `th`, `set-heading`,
  `rt`, `lt` and a leading `move-to`. (Per-segment **arcs** are still planned.)
- **`rt`/`lt`** are in-plane strafes (heading kept) and round-trip as `rt`/`lt`;
  a corner whose heading actually turns stays `(th …)(f …)`.
- **Orientation is preserved** where it matters: the last (exit) node and marks
  keep their heading; plain corners follow the geometry. Moving a plain corner
  re-derives its heading; moving a mark or the exit node keeps it.
- **Node colours** are semantic and stay visible even while selected (selection is
  shown by a larger dot): **green** = carries a mark/side-trip (protected), **orange**
  = an endpoint of the open path — the **start** node is an orange **ring** (a dot
  with a hole), the **exit** node a solid orange dot — **yellow** = a plain selected
  node, **blue** = plain.
- The baked path only includes a leading `(move-to …)` when the start node isn't at
  the origin; a path starting at the origin bakes as a plain `(path (f …) …)`.
- **Marks & side-trips are preserved.** `mark` and `side-trip` attach to the node
  they sit at: those nodes render **green**, are **protected from deletion** (marks
  become mesh anchors — never lost), and are re-emitted on confirm.
- A **non-leading `move-to` is rejected** with an error.
- Arcs (`arc-h`/`arc-v`), beziers, and out-of-plane `u`/`tv`/`tr` are **dropped**
  from the nodes and the baked output (confirming replaces them with straight
  segments); a warning lists what was dropped.
- **Not a persistent primitive.** Confirm bakes a `(path …)`; re-running does not
  re-open editing. Rename `path` → `edit-path` to edit again.
- **Modal session.** Like `tweak` / `edit-bezier` / `pilot`, one runs at a time and
  the editor is read-only while open; switching workspace closes the session.
- **Desktop tracing.** Node placement raycasts onto scene meshes (e.g. the stamped
  image), so reference images load only on desktop (see `set-image`).

## See also

- **Related:** `set-image`, `path-to-shape`, `shape-intersection`, `edit-bezier`,
  `path`, `move-to`
