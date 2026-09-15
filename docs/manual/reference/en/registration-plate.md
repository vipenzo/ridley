---
name: registration-plate
category: acquisition
since: ""
status: stable
---

# registration-plate

## Signature

`(registration-plate :d 300)`
`(registration-plate :d 130 :marks 12 :disc 2.5 :h 3)`

## Description

The **registration plate**: the printed disc the object stands on while you
photograph it, as a Ridley mesh with its crown of marks already named. It is the
usual `:proxy` of an `(acquire …)`, and needs no file import — the geometry and
the map of marks come from the same place, so what the solver looks for and what
you print can never drift apart.

```clojure
(acquire "/Users/me/scans/collare"
  {:proxy (registration-plate :d 300)
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

- `:d` — plate diameter in mm. **Required, and deliberately without a default.**
  It is the one number that ties this model to the object in the room, and a wrong
  one does not announce itself: a uniform scale error on the target is absorbed
  exactly by the camera distance, so the registration succeeds with clean residuals
  and every measurement comes out silently scaled. ⌀130 used to be the default for
  no better reason than being the first plate we built, and on 2026-08-13 a session
  was registered against it while a ⌀300 plate sat on the turntable — eight views,
  1.7px, and the whole scene 2.3× too small.
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

## The plate you printed may not be the plate in the file

It should be close and it need not be exact. A ⌀300 disc warps as it cools; paper
glued to a base lifts where the glue is thin; a printer lays its ink a hair off.

That kind of error hides. A plate that sits crooked, off-centre or wobbling is
absorbed entirely: it is just a different camera pose, and every photograph
solves its own. But a plate whose *marks are not where the model says* is a ruler
with the wrong numbers on it, and what it produces is a residual that changes as
the plate turns — because a mark standing proud of the plane projects differently
depending which way the camera looks across it.

So the plate can be **measured** rather than assumed. In a registered session,
`C` in `edit-acquire` triangulates every mark from every view that saw it and,
*if the result survives being tested on photographs that did not help produce
it*, files it under `~/.ridley/plates/`, keyed by diameter and crown count. From
then on every session that says `(registration-plate :d 300)` measures against
the plate you actually own. You calibrate once, per plate, not per session.

**Expect it to refuse, and take the refusal seriously.** The first real ⌀300 we
tried it on looked convincingly warped — 1.97mm at the worst mark, its own
residual falling from 2.18 to 1.78px — and was not warped at all. Six of its
twelve photographs carried exactly one blown pick each, 6 to 9px against eleven
sub-pixel marks; with those dropped the session sits at 0.6px instead of 2.1, and
the plate measures flat to a quarter of a millimetre. Its owner confirmed it by
hand, spinning it against a fixed point: one rise, under a millimetre.

A plate that fails the test is usually a plate that is fine, and the failure is
usually pointing at the clicks.

The measurement takes the plate's **shape** and leaves its **size** alone, and
that division is not a shortcut — it is what photographs can and cannot see. A
plate 5% larger, photographed 5% further away, makes exactly the same image; no
number of views separates them. Scale comes from `:d` and from a caliper. Only
the shape comes from the pictures.

## Notes

- The marked face's normal is `+Z`, and that is also the turntable axis after
  the object has been straightened.
- The face is flat by construction, which the registration exploits: coplanar
  marks are solved with the planar method (a homography), not the general one.
- The `acquire-plate` library prints from THIS function's own `:anchors`, so the
  discs on the paper are the marks the solver looks for — not a copy of them.

## See also

- `acquire` — the form this is usually the `:proxy` of
- `plane-from-edges` — measuring the object standing on it
