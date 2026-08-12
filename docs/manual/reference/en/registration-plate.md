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
`(registration-plate :d 300)`   ; a 12-inch turntable

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
- `:marks` — how many marks in the crown (default 12, and never more — see
  *Bigger plates* below before pinning a larger number).
- `:disc` — diameter of each printed mark in mm. Defaults to **a fraction of
  `:d`** (2.5mm on the reference ⌀130), so a bigger plate gets bigger marks.
- `:h` — plate thickness in mm (default 3). The only measurement that does *not*
  scale: thickness is about stiffness, not about what the camera sees.

## Bigger plates

A plate of another size is a **scaled copy** of the ⌀130 reference: the crown
radius, the mark diameter and the zero-index gap are all fractions of `:d`. Framed
to fill the shot, a ⌀300 plate therefore puts the same imaged geometry in front of
the detector as a ⌀130 one — which matters, because the detector's thresholds are
in pixels.

`(registration-plate :d 300)` suits a 12-inch record player used as a turntable.

**The crown does not get denser, and that is deliberate.** Recovering which mark is
which enumerates subsets over the marks a frame actually shows, and an object
standing on the plate always hides a few: with 12 marks and 3 hidden that search is
about 4 000 candidates, with 24 it is 85 000, with 36 half a million. A denser crown
buys no accuracy — twelve marks spread around a ⌀300 plate condition the pose better
than twelve around a ⌀130 — but it buys a registration that stops working the moment
three marks are covered.

**Printing one.** The sheet is the plate plus a 36mm margin, so ⌀154 is about the
largest that fits an A4 at 100%. Never print "fit to page": it silently rescales the
one thing that has to be exact, and the result does not look like an error — it looks
like a successful registration with the wrong scale. For anything larger,
`marks->svg-halves` cuts the sheet along a diameter into two pages (⌀200 fits two
A4s, up to ⌀350 fits two A3s). The seam passes *between* marks, each half carries its
own scale bars, and two alignment crosses on the seam pin the halves together when
you glue them.

**On a record player**, pass `:spindle-d` to the sheet: punch the centre hole, drop
it over the spindle, and the plate is centred and coaxial with the rotation — which
is the one thing the turntable machinery assumes and otherwise has to discover.

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
