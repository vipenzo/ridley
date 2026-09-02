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
`(registration-cage :d 176 :phases {:x 2.5})`
`(registration-cage :d 176 :flips #{:y} :phases {:y 0})`  ; a ring glued turned over
`(registration-cage :d 176 :index-phase 0)`   ; a cage printed before 2026-08-22

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
- `:phases` — how far each ring is turned about its **own** axis on the cage you
  actually glued, in degrees, as `{:x 2.5 :y 0 :z 0}` (`:x` the largest ring,
  `:z` the smallest), positive by the right-hand rule on that axis. Default 0,
  meaning nominal. See *The rotation you cannot impose* below — this is a
  number you **measure**, not a tolerance you try to hit.
- `:flips` — which rings were glued **turned over**, as a set of axes
  (`#{:y}`). The other mounting freedom, and the same philosophy as `:phases`:
  a constant of the cage you built, declared once — never a reason to reprint.
  See *The ring glued turned over* below. Declaring a flip **changes what that
  ring's phase measures**, so re-measure it afterwards.

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
`:zero-zp` and friends — offered as pick targets like any other disc, and the
most useful ones on the cage. See *One click on the index pins the ring*.

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
(register Big  (acquire-cage/make-print-ring 176 :big))    ; flat, ready to print
(register Cage (acquire-cage/make-cage-ring 176))          ; assembled, to look at
```

`make-print-ring` hands you the ring **lying down**, tabs upward — the pose it
prints in. `make-cage-ring` hands you the same ring where it sits in the cage,
which is what you want to look at the thing assembled but is standing on edge
for two rings out of three. Both return a **vector of meshes** — the light ring
and its dark discs, two meshes because two colours — which `register` takes as
it is.

The distinction is not cosmetic. In a slicer a ring and its discs are two
objects, so rotating one and not the other leaves the discs behind, and the part
prints perfectly — with no marks on it. A file that arrives already flat has
nothing to rotate and nothing to forget.

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

The three files arrive **flat and ready**; nothing needs rotating. Put all three
rings on one plate — moving each ring *together with its discs* — and the colour
changes then happen by **height** rather than per part — two of them, with a minimal purge tower. Print with a
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

Glue the **smallest and middle** rings first: their own tabs impose their
rotation, so those two are fixed by construction. Then anchor the part, and
close with the **largest** ring, whose rotation is the one nobody can impose
(see *The rotation you cannot impose*) — doing it last leaves the whole assembly
with exactly one free number instead of three. Use **epoxy**, not cyanoacrylate:
the cage gets handled a great deal while being turned.

The face against the build plate comes out sharper than the other one, and there
is no way to have both in a single print. That is expected: what the printer gets
wrong is measured afterwards, not chased beforehand.

## The assembly key: una tacca e una spina

Cages printed after 2026-08-24 carry a key: a small **pin** on one of the middle
ring's tabs, and a matching **notch** in the largest ring's rim. Sliding the
largest ring in, the pin meets its rim and the ring will not seat — at *any*
wrong rotation, and flipped face-for-face too — until the notch admits it. Push
until it clicks home, glue, done: the one rotation no joint used to impose is now
imposed.

It exists because that rotation was found *glued wrong* on the reference cage —
a quarter turn, invisible to the eye because at whole steps the tabs land neatly
between marks again — and because the error is silent in the photographs until
the zero-index contradicts them (see above). The key was Vincenzo's proposal,
verbatim: "una tacca e una spina". It costs nothing to print: the pin sits at
the tab's bed end and prints as first-layer footprint; the notch is a cut in a
flat part.

A cage glued **before** the key exists is not wrong — its turn is a property of
the part, measured once and declared forever: `:phases {:x 90}` (the reference
cage's own number).

## Holding the part: sticks, not pegs

Cages printed after 2026-08-25 carry **two stick-slots per ring**: small blocks
with an elliptical channel pointing at the cage's centre. A printed **stick**
(slightly elliptical in section) slides through; push it until it touches the
part, give it a quarter turn, and it locks — the stick is its own cam, no
levers, no loose hardware. Four or five sticks entering from different rings
cage any part at the centre.

This matters to registration, not just to convenience: the clothes-pegs and
stems that held the part in the first real sessions are exactly what covered
marks and fed the detector its false candidates. The slots sit at 60° and 240°
on each ring — 15° clear of the nearest crown disc, 30° clear of the joints,
35° from the zero-index — and take no part in the solve.

The kit, all from the tested sections (stick 4.0×3.6 mm, channel 4.4×4.0):

```clojure
(register Stick (acquire-cage/stick))        ; 60mm, il collaudato
(register Lungo (acquire-cage/stick 80))     ; per il centro dall'anello grande
(register Punta (acquire-cage/punta-tricuspide))
```

`punta-tricuspide` is an optional three-pointed foot that mounts on a stick's
tip by the same insert-and-twist principle — three contacts neither slip nor
roll on a convex surface. Print sticks **lying down**: the slight flat the
bridge side loses falls where the fit has clearance, not where it bites.

## The rotation you cannot impose

Concentricity and squareness are imposed by the tabs and their stops. So is the
rotation of the small and medium rings about their own axes: their tabs only
reach their partners when those rings are turned right. The **largest ring is
the exception** — it has no tabs of its own, it is held by the other two
pressing on its face, and it can turn while staying perfectly seated.

Nominally every joint lands exactly **halfway between two marks** (the crowns
are turned by half a step for this reason, giving 15° of clearance at twelve
marks), and that is the visual check: a contact point sitting *on* a disc means
that ring is round. But finding that midpoint by eye while the epoxy sets is
hard, and at r=85mm **one degree is 1.5mm**. So glue it as it comes, and treat
each ring's rotation as a number to measure — the same move `plate-calib` makes
for the plate, shifting the difficulty off the fabrication and onto an
instrument.

`edit-acquire` measures it for you. Register one photograph with `p`, clicking
marks on **all three** rings, and the REPL prints:

```
  fase degli anelli (da questa foto):
    anello x (5 mark): 2.53° ±0.06  =  3.75mm sulla corona
    anello y (4 mark): 0.04° ±0.31  =  0.05mm sulla corona
    anello z (4 mark): -0.02° ±0.28 =  -0.03mm sulla corona
  → l'anello x sembra INCOLLATO GIRATO di 2.5°…
```

Each ring is measured against a pose solved **without** it, which is what makes
the number trustworthy: measured against the fit that used it, a ring 3.0° round
reads 1.0° and smears the rest over the two innocent rings, because the fit
rotates the whole cage to split the difference. A turned ring also drags the
*other* rings' hold-out poses, so once the evidence is confident about one, it
is corrected and everything re-measured — which returns the innocent rings to
zero instead of leaving them accused of about a degree apiece.

The evidence is the **±**, not the size. A ring merely clicked sloppily gives
estimates that scatter as widely as their own mean; a ring genuinely glued round
gives the same offset from every one of its marks. Declare what it says:

```clojure
(edit-acquire "/Users/me/scans/testina"
  {:proxy (registration-cage :d 176 :phases {:x 2.5})})
```

and re-solve. If the residual collapses, that was it. Measured on a synthetic
cage: 4° of undeclared turn costs **22px of rms** and puts the camera **15mm**
out of place — large enough to fail a session, and with nothing in the number
itself to say the geometry, rather than the solver or your clicking, was at
fault.

A **90° error is not one of these** and needs no correction: with marks every
30°, turning a ring by 90° only changes which mark is number zero, and the
zero-index says which.

Printing is unaffected — `acquire-cage` always prints the nominal cage. `:phases`
describes the one you built.

## The ring glued turned over — `:flips`

The joints leave one more freedom than the phase: a ring can be glued **turned
over**, face for face, and still seat (verified physically on the reference
cage, 2026-08-28 — its Y ring is mounted that way). Like an unlucky phase this
is not a defect to fix by reprinting: the cage is epoxied, the mounting is a
constant of the object, and the model can carry it:

```clojure
(registration-cage :d 176 :flips #{:y} :phases {:y 0 :x 180})
```

A flip is modelled as the physical motion it is — a 180° **proper rotation** of
the printed ring about one of its own diameters, then the ring's `:phases`
turn. No mirror is involved: a real ring cannot be mirrored, only turned over,
and whichever diameter it actually turned about, the difference is an in-plane
rotation the phase absorbs. Which is also the caveat: **declaring a flip
changes what that ring's phase measures.** A phase fitted while the model
assumed the ring unflipped is void for that ring; re-measure it on the as-built
model (the phase machinery above works unchanged, and the drawn double dots
let you check the result by eye).

Mark ids keep the **print's** labels: after `:flips #{:y}`, `:yp…` still names
the discs of the printed p face — which now faces −y. That is the same
convention your eyes use: the zero-index figure is chiral, and its chirality
belongs to the *print*, so the reading rule (from the big disc to the small
inner one: counter-clockwise in the image = p) gives the same answer whether
the ring is mounted flipped or not. What changes is *which side of the cage*
that face looks out of — and the editor's face-from-pose derivation accounts
for it, so the toggles light up with the print's labels, correctly.

How you notice one: the virtual cage refuses to match the photographed one —
the glue tabs sit on the wrong side of the ring, the double dots land mirrored
— and, at the bench, the ring's index detects cleanly but in the mirrored
housing (this is exactly how the reference cage's Y ring was found). Declare
the flip, re-measure that ring's phase, and the drawn cage becomes the glued
one, tabs and all.

Printing is unaffected, same as `:phases`: `acquire-cage` prints the nominal
cage, and `printable-ring` must be taken from a model declared **without**
`:flips`.

## The zero-index is off-axis, and that is what tells the two faces apart

Each marked face carries a thirteenth disc, the zero-index, sitting radially
inside the crown. It is **not** on mark 0's axis: it is turned a third of a mark
step (10° at twelve marks) toward mark 1.

That offset is the difference between a cage that works and one that cannot. With
the index on the axis, a crown is mirror-symmetric about it — and because both
faces of a ring are the *same discs seen through the plastic*, the two faces then
present an identical figure. Nothing in a photograph can say which face you are
looking at, the numbering runs the opposite way on each, and a mark named as its
mirror twin produces a **rotated pose with a perfectly low residual**. The error
is invisible exactly where you would look for it.

Off the axis the figure is **chiral**: no rotation reproduces its mirror image. So
the face is legible, and with it the direction the numbers run.

Read it like this: find the disc with the small inner one beside it — that is
mark 0 — and count **toward** the inner disc's side. A third of a step keeps it
unmistakably nearer mark 0 (10°) than mark 1 (20°). Half a step would sit exactly
between them and be symmetric again, which is why the constant is a third.

> **A cage printed before 2026-08-22** has its index on the axis. Model it with
> `(registration-cage :d 176 :index-phase 0)`, or the solver looks for marks where
> they are not.

## One click on the index pins the ring

The index is a pick target, and clicking it is worth more than clicking three
crown marks.

A crown of twelve equal marks is invariant under rotation, and from its other
face it reads mirrored. So its own picks can never say which mark is which:
measured on a real photograph, **all 48 readings of one crown fit to the same
32.5px**. There is nothing to choose between them, and the offered numbers are
guesses from wherever the proxy happens to sit.

Add one click on that ring's index and the 48 collapse to **2** — the truth and
its face twin, both at the same residual. The remaining pair differ only by the
3mm of plastic between the two faces, which no residual can see and the physical
guard settles: those discs were photographed, so the camera was in front of them.

Practically: on a fresh photo, click the double dot of whichever ring shows it
clearly, then three or four crown marks of the same ring. The index is drawn in
white and labelled `⊙xm`, `⊙yp` and so on. When no ring shows its index — it is
often hidden on at least one — fall back to marks on two rings and let the
per-ring search work, which is weaker but usually enough.

## Reading the marks: let the editor name them

Do not count the discs. On a cage, counting is where sessions are lost: three
crowns cross in one frame, both faces of a ring carry the *same* discs at the
same angles, and the numbering reverses between them — so the direction you must
count in changes from ring to ring within a single photograph, depending on which
side of each ring the camera happens to be.

Press **`n`** (or the **Nomi** button) and `edit-acquire` writes every visible
mark's name on the photograph itself. It reads exactly what the solver reads, so
it is honest about being wrong: if the names land nowhere near the discs, the
proxy is out of pose, and that is the reading.

Which gives the order of work that avoids the whole difficulty:

1. Click **four marks on ONE ring** — the largest is easiest to trace. Four
   coplanar marks determine a planar pose exactly.
2. Press **`r`**. Expect a low residual from those four alone.
3. The names now land on the right discs on **every** ring, including rings with
   no picks at all, and the editor auto-places the marks it can find.

Step 3 is the one that can fail quietly, and `a` (below) is what checks it: the
names land right only if the crown was read right, and the ring you clicked
cannot say whether it was.

Measured on a real session (2026-08-20, ⌀176 cage, 48mm-equivalent phone shots):
four hand clicks on the largest ring, twenty-five marks found automatically
across all three rings, **11.8px** with two rejected. The same photograph, worked
the other way — reading each ring's numbers by eye — had six clicks of one crown
landing on discs of three different rings, a residual of 514px, and no
relabelling that could undo it.

If a ring's numbers really do have to be read by hand, the sense is fixed:
**`xp`/`yp`/`zp` run counter-clockwise, `xm`/`ym`/`zm` run clockwise**, each seen
from the side it is printed on. Mark 0 is the one with the zero-index disc just
inside it, toward the ring's centre.

## When the clicks are right and the names are not — `a`

Press **`a`** (or **Auto — leggi la gabbia**) after clicking four marks on one
ring, and the editor decides how that crown should be read, then places every
other mark it can account for and solves on all of them.

It exists for a failure that is *silent*, which is the only kind worth a key of
its own. Start counting three marks late and every click is on a real disc, every
label is wrong, the residual comes out at 5px, and the camera genuinely is in
front of every disc you clicked — so no guard fires and the session records a
success. Measured on a real photograph: that reading leaves the other two rings'
marks **92–157px** from the discs actually in the frame, while the right reading
leaves them at 4–21px. Nothing in the ring you clicked can tell the two apart —
all 48 readings fit it to 5.3–5.4px, which is the whole difficulty — and nothing
you can see can either, because the numbers you would judge by are the ones in
question.

What settles it is the rest of the cage. The detector finds the dark discs across
the whole frame, and each of the 48 readings is scored not on the ring it came
from but on **how much of the whole cage its pose explains** against those discs.
On the same photograph that collapses the 48 to two poses differing by a half
turn, and between those the physical guard decides: the discs were photographed,
so the camera was in front of them.

So the order of work is:

1. Click **four marks on ONE ring**, plus its zero-index if you can see it.
2. Press **`a`**.
3. Read what it says. `i nomi che avevi dato erano giusti` means your counting was
   right. `i tuoi click erano giusti, i NOMI no` means it was not, and it has been
   fixed without touching a single click.

If it answers that it cannot read the cage, the usual cause is a frame that shows
**one ring only** — straight down an axis, the other two are edge-on and have
nothing to say. A few degrees off the axis brings them back. If instead it warns
that several readings explain the cage equally well, believe it: that photograph
does not contain the answer, and another one will.

`a` is **seeded**, not zero-click, and that is not a shortcut left unfinished. A
crown of equal marks cannot say which of them is mark zero — no photograph can —
so four clicks are what the automatic path stands on. Everything after them is
the machine's job.

**Click the zero-index too, always.** It is not just a better pick: it is the one
disc that can catch the one assembly error nothing else sees. If a ring was glued
a whole number of steps round from nominal — 90° is three steps, and the tabs
land neatly between marks again, so the glued cage *looks* right — then every
crown fits every rotation of its own labels, and the misfit appears only as the
other rings sitting k steps round. Scored on the photograph alone, the readings
that explain most are the ones that quietly throw your index click away. `a`
refuses that trade: the index cannot be outvoted. When the candidates and your
clicked zero disagree, it keeps your names — the zero proves them — and reports
the real culprit:

> *l'anello X sembra INCOLLATO girato di 90°. Riapri la sessione dichiarandolo
> nel proxy: `(registration-cage :d 176 :phases {:x 90})` — se le facce di Y/Z
> escono invertite, usa −90. I click fatti restano validi.*

Declare the phase, reopen, press `a` again: same clicks, whole cage. This
happened on the reference cage itself — its big ring, the one whose rotation no
joint imposes, is glued at 90° — and was found by exactly this disagreement.

## Tracing on a plane you place yourself

The retrace (`d`) used to ask which of the proxy bounding box's **six faces** you
were tracing on, plus a shift along that face's normal. That was right while the
proxy WAS the part: the six faces were the part's own faces. A cage ends it — the
proxy is now the reference *around* the part, its bounding box is a cube enclosing
an object a quarter its size, and "which face" has no answer.

So the tracing plane is a **pose**: place and orient it with the gizmo, exactly
as you place anything else. The six face buttons remain, as presets that set that
pose, and the offset slider still slides the plane along its own normal.

The plane is then emitted as the traced shape's `:mark`, so the outline arrives
with its own frame:

```clojure
(let [q (:contorno (:shapes A))]
  (turtle (:mark q) (extrude (:shape q) (f 3))))
```

The plane's **origin** is drawn as a white ball, and it is not decoration: that
point becomes the emitted mark's `:position`, and the traced outline is written
in the plane's own frame around it. So where you put the plane is where the
shape's origin is — `(turtle (:mark q) …)` starts there.

The plane follows the handle **during** the drag, not only at release, and the
**wheel zooms the photograph** (right-button drag pans it once zoomed). The zoom
is a change to which part of the frustum is rendered, never a camera move: an
acquire session keeps the camera frozen on the photograph's solved pose, so
orbiting to look closer would break the registration being worked on. Changing
photo resets the zoom — a window that framed one shot frames nothing on the next.

Dragging the plane does **not** clear the points already traced on it (changing
face still does — those points would be meaningless elsewhere): moving a plane
slightly is usually a correction to a trace already under way.

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
