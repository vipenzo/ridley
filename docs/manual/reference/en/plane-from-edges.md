---
name: plane-from-edges
category: acquisition
since: ""
status: stable
---

# plane-from-edges

## Signature

`(plane-from-edges name …)`
`(plane-from-edges name … opts)`

## Description

The **plane through the edges you name** — written where a mark goes, inside an
evaluated `(acquire …)`'s `:marks`:

```clojure
:edges {:bordo-alto (edge-mark {…})
        :bordo-basso (edge-mark {…})}
:marks {:coperchio (plane-from-edges :bordo-alto :bordo-basso)}
```

The names are keys of the **same** acquisition's `:edges`, and every kind counts:
a straight edge (sampled along itself), a curve (its points), a circle (its
ring). All of them become points, and the plane is the robust fit through them.

The point of writing it this way is that **the plane is a formula, not a copy**.
It is recomputed on every Run from the evidence it names — correct one edge and
the plane follows; delete one and the plane changes; the case "old plane, new
evidence" stops existing. And the selection is text: durable, repeatable,
diffable.

Use it like any other mark:

```clojure
(turtle A :at :coperchio (edit-path-2d))
(turtle A :at :coperchio (extrude (rect 18 16) (f 2)))
```

**Two non-parallel edges of the same face pin it exactly**, and they are the
sturdiest evidence here: a straight edge is measured without pairing any points
between photographs, so it carries none of a curve's ghost trouble.

## Parameters

- `name …` — one or more keys of this acquisition's `:edges`.
- `opts` — an optional trailing map, merged into the resulting mark. Useful for
  the display keys: `(plane-from-edges :a :b {:show false})` makes the plane and
  keeps it out of the drawing.

## Notes

- **A refusal does not create the mark**, and it says why with numbers. Edges
  that lie nearly in a row are the common one: a row lies on infinitely many
  planes, and the message reports how far the normal would tilt per millimetre
  of error. Name a transversal edge and try again.
- **The fit is robust, not partial.** An edge more than ~1.5 mm from the plane
  the others agree on is dropped **whole**, and the plane does not move — a
  border on *another* face must not be able to tilt this one a little, which
  would be the worst way to be wrong. When that happens the output says which
  edge was left out and by how much.
- **From three edges up it reports the leave-one-out**: how far the plane would
  turn if you removed each one. On a real face those numbers are small. Nine
  degrees means the edges are describing different surfaces — a chamfer, a
  fillet — or one of them is mismeasured. Flatness in millimetres does not catch
  this: 0.7 mm across a cloud 8 mm wide *is* nine degrees.
- The normal points **away from the object's centre** — the outward-face rule.
  It deliberately does not use the camera positions: a formula must give the
  same answer on every Run, and cameras move.
- The mark carries `:from`, the points it was fitted through, so the stage can
  size its disc and you can see the evidence with `:show :prove`.

## See also

- `edit-edge-mark` · `edit-curve-mark` — measure the edges first
- `plane-mark` — a plane written as a fixed literal instead
- `acquire` — the form whose `:marks` this lives in
- `turtle` — how a plane poses the turtle
