---
name: edit-plane-by-eye
category: acquisition
since: ""
status: stable
---

# edit-plane-by-eye

## Signature

`(edit-plane-by-eye ring)`
`(edit-plane-by-eye ring pose-map)`
`(edit-plane-by-eye pose-map)`

## Description

Place a plane **by eye** on the acquisition stage: seeded on one of the
registration cage's rings, carried onto the part with a gizmo, written into the
`:marks` of the evaluated `(acquire …)`. It is the `d` anchor gesture of
`edit-acquire`, moved out to the stage — where your own geometry is visible over
the photo and **follows the plane live** as you move it, so you aim at the thing
you built on the mark rather than at the disc.

Like `edit-plane-mark` it is not a top-level marker: it lives **inside** the
acquire's `:marks` map, and the gesture is the family's usual one — the resting
form is `(plane-by-eye …)`, put `edit-` in front of the head and Run.

```clojure
(acquire "dir"
  {:proxy (registration-cage :d 176 :gen 2)
   :marks {:coperchio (edit-plane-by-eye :big)
           :fianco    (plane-by-eye :medium {:position [...] :heading [...] :up [...]})}})
```

Evaluating that source flies the camera into a photo and shows `:coperchio` as a
translucent disc at the centre of the cage, parallel to the big ring, with a
translate + rotate gizmo on it. Drag the gizmo's arrows and rings to carry the
plane onto the flat zone of the part; a click on the photo puts the plane's
**origin** where you clicked (the ray meets the plane in exactly one point — no
triangulation); the arrow keys nudge the origin in the plane and, with Shift,
the plane in depth; `[` and `]` change photo, so you can check that the disc
stays glued to the surface from every registered angle. **Enter** writes
`(plane-by-eye :big {…})` in place of the form; **Backspace** puts the plane
back where it started; **Esc** renames the head back and leaves the mark as it
was.

Given a pose map as well — `(edit-plane-by-eye :big {…})`, which is what you get
by re-opening a written mark — the editor starts at that pose instead of on the
ring; the ring is then only provenance and the size of the disc.

While the editor is open the form returns the pose map when there is one, else
the ring spec `{:ring :big}` that `acquire` resolves to the ring's own plane —
so the mark exists, and anything built on it renders, from the first eval.

## Parameters

- `ring` — which ring of the cage seeds the plane: `:big`, `:medium` or `:small`
  (the bench's names, the rings ordered by size), or `:x`, `:y`, `:z` (the
  model's). Case does not matter. Required for a creation; optional when a pose
  map is given.
- `pose-map` — `{:position … :heading … :up …}` to start from, as written by a
  previous Enter.

## Notes

- Nothing is measured here: the plane is where your hand put it, checked across
  the photos by eye. When the zone can be pinned by points on several photos,
  prefer `edit-plane-mark`, which measures it.
- Only a `registration-cage` proxy has rings. On a plate or a box, give a pose
  map instead.
- With several `edit-*` mark forms present, the first is opened and the others
  wait — confirm this one and re-run to reach the next.

## See also

- `plane-by-eye` — the resting form this pairs with
- `edit-plane-mark` — the measured plane, from points clicked on several photos
- `acquire` — the form whose `:marks` this edits
- `turtle` — how a mark is used: `(turtle A :at :coperchio (edit-path-2d))`
