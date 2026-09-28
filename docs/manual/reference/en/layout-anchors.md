---
name: layout-anchors
category: positioning-assembly
since: ""
status: stable
---

# layout-anchors

## Signature

`(layout-anchors piece anchor & {:keys [inset spacing n seed prefix]})`

## Description

Propose where the joints go on a cut face. Returns a map of **anchors**
`{:pin-1 pose :pin-2 pose …}` lying on the plane of `anchor`, with its
heading and up, spread evenly inside the admissible zone (see
`joint-zone`) by Lloyd relaxation (`spread-points`). It measures and
suggests; it builds nothing.

The poses are world poses, so the same map serves the piece **and its
twin**: a cylinder centred on one straddles the cut plane and is
subtracted from both pieces, and the holes coincide by construction.

`piece` is a mesh out of `mesh-split` (or its registered name); `anchor`
is the name of a cut anchor on it — `:cut` for a single cut, the mark's
name (`:cut-1`) with a path. Together they pick "this piece, this face":
a middle piece has two.

## Parameters

- `:inset` — **required.** Minimum distance of a joint's centre from the
  piece's surface: pin radius plus wall. No default, it depends on
  material and printer.
- `:spacing` — minimum distance between joints, and a **guarantee**: each
  island starts from `round(area / spacing²)` anchors and the count is
  lowered until no two anchors are closer than about `0.9·spacing` (a
  narrow strip would otherwise pack two zig-zag rows well under the
  declared distance). Keep it at or above `2·inset`, or the pins will
  touch; below that a line is printed.
- `:n` — explicit total instead of `:spacing`, apportioned by area with
  at least one per island.
- `:seed` — generator seed (default `0`). Same seed, same anchors: a
  re-run leaves the pins where they were.
- `:prefix` — anchor name prefix (default `"pin"`).

Metadata `:layout` on the result holds `{:zones [shape …] :rejected
[{:area a} …] :min-distance d}`: islands of the face too narrow for the
inset are reported (and printed), not silently dropped, and the closest
pair is measured. With an explicit `:n` the count is never lowered; a
closest pair under `2·inset` is printed instead.

## Example

```clojure
(def halves (mesh-split (box 20 60 40)))
(def L (layout-anchors (:behind halves) :cut :inset 5 :spacing 15))
(register pins (on-anchors L "pin" :align (cyl 3 10)))
(register B (mesh-difference (:behind halves) pins))
(register A (mesh-difference (:ahead halves) pins))
```

Two pins along the 60-unit side of the face, a hair over 5 from every
edge; `A` and `B` carry matching holes.

## Notes

- On a thin ring with few pins the points sit a little inside the mid
  radius: the area centroid of a wide annular sector is pulled inward.
  More pins, narrower sectors, closer to the middle.
- A face whose spine is thinner than `2·inset` splits into islands, each
  of which still gets its minimum of one anchor.
- If pins do overlap (a deliberate `:n`, or a spacing under the pin
  diameter), build them with `(on-anchors L :union "pin" …)`: the default
  `:concat` leaves interior faces that break the later `mesh-difference`.

## See also

- **Related:** `joint-zone`, `spread-points`, `mesh-split`, `on-anchors`,
  `anchors`, `slice-mesh`
