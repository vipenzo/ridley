---
name: curve-mark
category: acquisition
since: ""
status: stable
---

# curve-mark

## Signature

`(curve-mark points-map)`

## Description

A **measured curved edge** of an acquisition: the 3D points recovered from the
photographs. It **returns the map unchanged**, like the rest of the family.

```clojure
:edges {:profilo (curve-mark {:points [[-12.4 3.1 8.0] [-11.8 4.2 8.1] …]})}
```

It carries **no pose**, because a curve has none — and that is the whole of what
it is for. A curve's value is the **plane** it lies on, and a plane wants
evidence: several curves, and straight edges too, pooled together.

```clojure
:marks {:coperchio (plane-from-edges :profilo :bordo-alto)}
```

Because it is evidence rather than an anchor, `(turtle A :at :profilo …)` does
**not** find it: the anchor machinery skips curves on purpose. Reach for the
plane you made from it instead.

## Parameters

- `points-map` — `{:points [[x y z] …]}`, at least three of them, plus the
  display keys below.

Display keys, honoured by the stage:

- `:show` — `false` hides it entirely, `:prove` draws its points; **absent draws
  nothing but its name**, because forty dots per curve is exactly the clutter
  that key exists to prevent;
- `:label` — `false` for no name over the photograph, a string for a different
  one.

## Notes

- To measure one, write `(edit-curve-mark)` at the key you want and Run.
- It lives in the source so it can be renamed, deleted and kept between
  sessions as text. A bench inside the gesture would need a button for each of
  those verbs, and would vanish when the gesture closed.
- The written points are thinned to a dozen: a walked curve arrives forty-odd
  points long, and a literal that size buries the form it lives in. A dozen,
  evenly spaced, still describes the zone.
- If the curve really is a circle, `(circle-mark …)` says so with a radius and
  gives you a usable pose.

## See also

- `edit-curve-mark` — measure one
- `plane-from-edges` — what it is evidence for
- `circle-mark` — when the curve is a circle
- `edge-mark` — the straight sibling
