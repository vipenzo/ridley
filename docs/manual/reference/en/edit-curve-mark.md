---
name: edit-curve-mark
category: acquisition
since: ""
status: stable
---

# edit-curve-mark

## Signature

`(edit-curve-mark)`
`(edit-curve-mark curve-map)`

## Description

**Measure a curved edge** — the same gesture as `edit-edge-mark`, said for a
curve. Written as a value inside an evaluated `(acquire …)`'s `:edges`, it arms
the measurement:

```clojure
:edges {:profilo (edit-curve-mark)}
```

Paint the curve on one photograph, change photograph with `]`, paint **the same
stretch** on another, and press **Enter**. What lands in its place is a
`(curve-mark {:points […]})`: the 3D points recovered from the pair.

A curve carries no pose, and that is the whole of what it is for. Its value is
the **plane** it lies on, and a plane wants evidence:

```clojure
:marks {:coperchio (plane-from-edges :profilo :bordo-alto)}
```

## Parameters

- `curve-map` — an already measured curve, to measure again from scratch (the
  contents are scaffolding: re-opening re-measures).
- No argument — the ordinary case: create the curve at this key.

## Notes

- A curve is harder than a straight edge, and honestly so. The two photographs
  must follow **the same stretch**: what the pairing shares is what gets
  measured, and the panel reports how many points the two views have in common
  and what fraction of the pairings respects the order of the walk. A low number
  means the two strokes wandered onto different pieces of the same border.
- Do not chase the whole curve. A shared piece is enough — keep it and add
  another edge on the same face; the plane is made of everything together.
- If the curve really is a circle, `c` writes `(circle-mark …)` instead, with the
  diameter. That is a *declaration* — "this curve is a circle" — not a different
  measurement.
- A curve's points are drawn only if it asks: `:show :prove`. Forty dots per
  curve is exactly the clutter that key exists to prevent; its name still marks
  the spot on the photograph.

## See also

- `edit-edge-mark` — the same gesture for a straight edge
- `curve-mark` — the resting form this pairs with
- `plane-from-edges` — what a measured curve is evidence for
- `circle-mark` — when the curve is a circle
