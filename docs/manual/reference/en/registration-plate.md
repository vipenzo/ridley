---
name: registration-plate
category: acquisition
since: ""
status: stable
---

# registration-plate

## Signature

`(registration-plate)`
`(registration-plate :d 130 :marks 12 :disc 2.5 :h 3)`

## Description

The **registration plate**: the printed disc the object stands on while you
photograph it, as a Ridley mesh with its crown of marks already named. It is the
usual `:proxy` of an `(acquire …)`, and needs no file import — the geometry and
the map of marks come from the same place, so what the solver looks for and what
you print can never drift apart.

```clojure
(acquire "/Users/me/scans/collare"
  {:proxy (registration-plate)
   :pose  {:position [0 0 0] :heading [0 0 1] :up [0 1 0]}})
```

Why a plate at all: registration needs landmarks that exist as **points**. On a
real object "the corner" is not a point — it is a fillet a millimetre or two
wide, which at photograph scale is tens of pixels of ambiguity. Printed discs
are found to sub-pixel precision, and the crown gives enough of them, spread
widely, for every photograph.

The marks travel on the mesh as `:anchors` — `m00`, `m01`, … around the crown,
plus `:zero`, the asymmetric index that tells the solver which way round the
plate is.

## Parameters

- `:d` — plate diameter in mm (default 130).
- `:marks` — how many marks in the crown (default scales with the diameter:
  denser when bigger).
- `:disc` — diameter of each printed mark in mm (default 2.5).
- `:h` — plate thickness in mm (default 3).

## Notes

- The marked face's normal is `+Z`, and that is also the turntable axis after
  the object has been straightened.
- The face is flat by construction, which the registration exploits: coplanar
  marks are solved with the planar method (a homography), not the general one.
- `examples/param-acq-plate.clj` is the single source for both the printable
  plate and this map of marks — print from there, not from a copy.

## See also

- `acquire` — the form this is usually the `:proxy` of
- `plane-from-edges` — measuring the object standing on it
