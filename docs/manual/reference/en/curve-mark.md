---
name: curve-mark
category: acquisition
since: ""
status: deprecated
---

# curve-mark

## Signature

`(curve-mark points-map)`

## Description

A **curved edge measured before 2026-08-10**, kept as its 3D points. It still
counts as evidence for a plane, and it still draws — but **curves can no longer
be measured**, and there is no gesture that writes one.

```clojure
:edges {:profilo (curve-mark {:points [[-12.4 3.1 8.0] [-11.8 4.2 8.1] …]})}
:marks {:coperchio (plane-from-edges :profilo :bordo-alto)}
```

### Why it went

Measuring a curve means **pairing points between two photographs** — this point
on the first is that point on the second — and pairing is precisely what this
channel was built to avoid. It is where the ghosts came from, it needs the order
test to catch them, and in practice it asked the hand to paint the same stretch
of the same border twice, from different sides. It was the hardest thing here by
a distance.

And it was not needed: a curve's value was the **plane** it lies on, and two
non-parallel **straight** edges give that plane exactly, measured without
pairing anything. So the difficult road led where the easy one already went.

The form stays so that a source holding one keeps running, and so its points
keep feeding `plane-from-edges`.

## Parameters

- `points-map` — `{:points [[x y z] …]}`, plus `:show` (`false` hides it,
  `:prove` draws its points; absent draws only its name) and `:label`.

## Notes

- A face bounded only by curved borders can still get a plane, with the older
  **Piano** gesture: click the same point on two or more photographs, three
  times. It is the harder gesture, and the reason edges were built — but it is
  there.
- The stage now refuses an arc out loud when you paint one: it says the border
  is curved and asks for a straight stretch instead.

## See also

- `edge-mark` — what to measure instead
- `plane-from-edges` — the plane these points can still feed
- `acquire` — the form whose `:edges` this lives in
