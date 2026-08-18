---
name: registration-cage
category: acquisition
since: ""
status: experimental
---

# registration-cage

## Signature

`(registration-cage :d 176)`
`(registration-cage :d 176 :marks 12 :disc 2.5 :h 3)`

## Description

The **registration cage**: three concentric, mutually orthogonal printed rings
with the part anchored in the middle, as a Ridley mesh with its six crowns of
marks already named. It is the alternative `:proxy` of an `(acquire …)`, and
like `registration-plate` it needs no file import — geometry and marks come from
the same place, so what the solver looks for and what you print cannot drift
apart.

```clojure
(edit-acquire "/Users/me/scans/testina"
  {:proxy (registration-cage :d 176)})
```

Why a cage rather than a plate. A plate is a plane, and a plane costs twice
over. Seen edge-on it **degenerates**: the photograph taken from behind, or level
with the table, cannot be registered at all, so the object has to be re-mounted
and the sessions fused. And its marks sit at the **table**, while the detail you
are tracing sits centimetres above it, so the pose error is levered up through
that gap. Both push the photographer the wrong way — to keep enough plate in
frame you shoot wide and far, which is the opposite of the close, aimed shot the
tracing wants.

The cage moves the reference **onto the object**. Every photograph carries its
own reference at the part's own depth; the turntable, the θ angles, the `NOTE.md`
and the multi-session fusion do not get smaller, they cease to exist, because
they were there to enforce a rigidity the mounting now supplies physically.

## Parameters

- `:d` — diameter in mm **over the largest ring**. **Required, and deliberately
  without a default**, for the reason spelled out under `registration-plate`: a
  uniform scale error is absorbed exactly by the camera distance, so a wrong one
  registers cleanly and silently scales every measurement. It is also the number
  a caliper across the assembled cage reads.
- `:marks` — marks per crown (default 12, on **every** ring — see *Ring identity*).
- `:disc` — diameter of each printed mark in mm. Defaults to a fraction of `:d`
  (2.5mm on the reference ⌀176).
- `:h` — ring thickness in mm (default 3). Does **not** scale: it is about
  stiffness, and it is the width of the band an edge-on ring paints across the
  part — 3mm against a 60mm part is 5% of it, which is the cage's whole
  occlusion cost.

## Why three rings, and why three sizes

For any viewing direction the best of three orthogonal axes satisfies
`max|v·axisᵢ| ≥ 1/√3`, so at least one ring is always seen at 35° or more from
its plane — an obliquity the ellipse fit already digests. From a general
direction **all three** are visible at once, which makes the marks non-coplanar:
the general (DLT) estimator comes back into service, all six degrees of freedom
are conditioned by the data, and the planar homography's mirror twin does not
arise.

The three rings are **different sizes** because rings have width. Two circles in
orthogonal planes can only meet on the line their planes share, and there they
sit at their own radii — but two *annuli* collide whenever their radial bands
overlap. Keeping the bands disjoint buys a cage with no interlocking slots, no
notches and nothing sprung: three whole rings, each printed flat with its marks
against the build plate, joined by six glued tabs.

**Ring identity comes free from the sizes.** The ratio of two ellipses imaged
together is invariant to distance, so the rings tell themselves apart with no
second colour and no second mark shape.

## Marks on both faces

Each ring is marked on **both** of its faces, and that is not a nicety. A ring
marked on one side is visible only from that side's half-space; three rings
marked on their +X/+Y/+Z faces leave a camera in the (−,−,−) octant seeing *not
one mark*, and the blind region the cage exists to abolish comes straight back.

The two faces' marks share their in-plane coordinates — they are the same disc
through the material — and which face is being looked at is never ambiguous,
because the camera must be on the side of the face it can see. Mark ids say so:
`:zp07` is mark 7 on the +Z face of the ring whose normal is Z, `:xm00` is mark 0
on the −X face of the X ring. Each marked face also carries its own zero-index,
`:zero-zp` and friends, which are not offered as pick targets.

## The one degenerate family, and it is known

Shot **straight down one of the cage's three axes**, the two rings containing
that axis are edge-on and their marks lie on the far side of 3mm of plastic — so
only one ring is pickable, which is a plate again, mirror twin included. Ten
degrees off the axis and the others come back.

`edit-acquire` guards this rather than leaving it to chance: a solution that puts
the camera **behind** a disc you say you clicked is impossible, not improbable,
and it is refused with that explanation. The remedy is to click marks on a second
ring, or to step off the axis and reshoot.

## Printing and assembling one

The `acquire-cage` library prints from **this function's own** `:anchors` and
`:tabs`, so the discs on the plastic are the marks the solver looks for — not a
copy of them.

```clojure
(register Big  (acquire-cage/make-cage-ring 176 :big))     ; :big :medium :small
(register Cage (acquire-cage/make-cage-ring 176))          ; :all, the default
```

`make-cage-ring` returns a **vector of meshes** — the light ring and its dark
discs, two meshes because two colours — which `register` takes as it is. Register
one ring at a time when you mean to export: the export writes the *scene*, so a
scene holding one ring yields a file holding that ring and nothing else to drag
by mistake.

To skip the scene and write all three files at once:

```clojure
(acquire-cage/save-3mf 176 "~/Downloads")   ; three files, one folder dialog
(acquire-cage/save-cradle 176 "~/Downloads")
```

**One ring per file, not one file with six objects.** A ring and its discs stay
separate objects in a slicer, and separate means draggable apart; a ring moved off
its own marks still slices, still prints, and is scrap.

Put all three rings on one plate — moving each ring *together with its discs* —
and the colour changes then happen by **height** rather than per part — two of them, with a minimal purge tower. Print with a
**brim** (a thin wide ring curls as it cools, and a warped ring is no longer
flat) and in **matte** filament (the detector looks for a dark round patch; a
specular highlight is exactly the opposite, and gloss supplies them by the
dozen).

Assembly is six glued lap joints. Four of them — the smallest ring's — carry a
**stop lip** just beyond the partner's rim, so the ring drops between a pair of
them and lands where it belongs: push until it stops, then glue. The remaining
two, between the middle and largest rings, are aligned by eye: slide until the
tab's tip is flush with the rim.

The two without stops are not an oversight. A stop only stops if it reaches
across the partner's plane, and the partner is fitted by sliding along that same
direction — so a lip there would block the assembly rather than the ring. The
four that exist sit outside the partner's outer radius, where nothing ever
passes.

Fit the two larger rings first, anchor the part, and close with the smallest ring
last — it carries four of the six tabs. Use **epoxy**, not cyanoacrylate: the
cage gets handled a great deal while being turned.

The face against the build plate comes out sharper than the other one, and there
is no way to have both in a single print. That is expected: what the printer gets
wrong is measured afterwards, not chased beforehand.

## Anchoring the part

The only requirement is that the part cannot **move relative to the cage during
the session** — its position in the cage is never assumed, only used as a frame,
so the mounting may be as ad hoc as you like. What it must not be is compliant:
elastic bands store energy and shift the part the moment gravity turns, and
threads only pull, so half of them go slack every time the cage is turned over.
Rods resist bending; a stiff stalk, or three or four skewers converging on the
part, hold in every orientation.

If the part does move, it is not silent: the marks go on agreeing between views
while your traced features stop.

## Notes

- `(acquire-cage/measured 176 175.4)` corrects the marks for what the printer
  actually produced, from one caliper reading across the largest ring. One number
  suffices where the plate needed two — a sheet can print to different scales on
  its two axes, three rings from one machine cannot, and a machine that did would
  make them oval, which is visible.
- The clear opening through the cage is the innermost ring's hole (⌀88 on the
  reference ⌀176): that is the largest part that can be got to the middle.

## See also

- `registration-plate` — the flat alternative, better when the part has a
  dominant plane and depth is not the problem
- `acquire` — the form this is a `:proxy` of
