---
name: edit-acquire
category: acquisition
since: ""
status: stable
---

# edit-acquire

## Signature

`(edit-acquire dir)`
`(edit-acquire dir opts)`

## Description

Opens the **registration session** for a folder of photographs: the modal room
where you tell Ridley where each camera was standing. It is the form you write
first, and the only one you write by hand — everything else in the acquisition
family is written back into your source when you confirm.

```clojure
(def A (edit-acquire "/Users/me/scans/collare"
         {:proxy (registration-plate :d 300)}))
```

Run it from the **definitions panel** (Cmd+Enter), not the REPL: the session
rewrites the very form you ran, so it has to be able to find it in the buffer.

Confirming replaces `edit-acquire` with `acquire` and pours everything measured
into it. Closing without confirming strips the `edit-` and leaves the `acquire`
alone. So the two forms are the same object in two states — one being worked on,
one at rest — and you move between them by adding or removing four characters.

Registration itself is not something you do by hand. On a registration plate,
**`a`** finds the crown in every photograph and solves each camera with no
clicks at all; **`R`** then refines one focal length and every pose together,
which is what turns a handful of separately-plausible cameras into one
consistent set. The manual routes are there for the frames that resist.

## Parameters

- `dir` — absolute path to the session folder. It may be **empty**: a session
  you intend to fill by grabbing frames from a live camera starts with no
  photographs at all. Otherwise the folder holds the images, and `session.json`
  is built on first open from `NOTE.md`.
- `opts` — the same map `acquire` carries (`:proxy`, `:pose`, `:marks`,
  `:edges`, and `:shapes` for forms written before anchors moved to `:marks`).
  On a first open you usually give only `:proxy`.

## The proxy is a promise about the real world

`:proxy` is the object the solver looks for in the photographs, and it must be
the object that is actually there — which is why `:d` has no default and must be
said out loud. A `(registration-plate :d 300)` in your source and a ⌀250 plate on
the table will still register — the solver will happily put
the camera at the wrong distance and report a residual that looks fine, because
a wrong scale is indistinguishable from a wrong distance in a single view. What
comes out is not an error, it is a measurement that is quietly wrong.

If you printed the plate on paper, correct it for what the printer actually did:
`(acquire-plate/measured 300 100.2 99.8)` in place of `(registration-plate :d 300)`.

## Keys

| | |
|---|---|
| `a` | register every unregistered photograph, no clicks (plate) |
| `p` | register this photograph by clicking marks |
| `f` | fill the remaining photographs from the ones already registered |
| `R` | refine one focal and every pose **together** — do this once you have four |
| `C` | measure the plate itself — once per plate, after `R` |
| `g` | grab a frame from a live camera and register it on the spot |
| `d` | **add an anchor** — place and orient a plane, which becomes a named mark |
| `1` `2` `3` | (cage) put the anchor at the cage centre, in that ring's plane |
| `n` | write every visible mark's **name** on the photograph |
| `F` | offer **every** mark, including the faces the current pose thinks are turned away |
| `k` | place a named point · `m` arm the physical marker |
| `v` | hide the proxy to read the photograph under it |
| wheel | zoom the photograph · right-drag pans it |
| `[` `]` | previous / next photograph |
| `Esc` | leave the current mode, then the session |

## With a cage instead of a plate

`(registration-cage :d 176)` as the `:proxy` changes what the session can and
cannot do, and it is worth knowing which is which.

**Registration is by hand.** `a` (find the crown and solve with no clicks) and
`C` (measure the target itself) are built on the plate's single crown plus its
zero-index; a cage has six crowns and no `:zero`, and they refuse it saying so.
`p` is the route: four marks on ONE ring are enough to fix a pose, and the
editor then blob-snaps the rest across all three rings. Measured on a real
session: four hand clicks, twenty-five marks found, 11.8px.

**When the editor offers the wrong face, say so with `F`.** Which marks are
offered is decided by the proxy's pose — and the pose is what the picking is
trying to establish, so the default is circular: get it wrong and you are offered
`ym00…` while plainly looking at the `yp` face, with no way to place the marks
that would correct it. `F` drops the cull and offers all seventy-two. Click the
discs you can SEE: a disc in front of you is a fact, the pose is still a guess.

**Check the cage against the photograph before trusting anything.** Press `n` and
step through the views with `[` / `]`. Where the names sit on the printed discs,
that view is registered; where they sit beside them, it is not — and a view can
be wrong while its residual looks fine, so this is not a formality. On one real
session four photographs put the cage exactly on the discs and the fifth did not,
which the 98px residual had also said, but the eye settles it in a second.

**Anchors, not outlines.** `d` no longer traces a contour. It places a plane —
position and orientation, set with the gizmo — and that plane is emitted as a
named entry in `:marks`. The outline is drawn afterwards, outside the session,
with the full path editor on the anchor's own plane:

```clojure
(let [q (:ancora-1 (:marks A))]
  (turtle q (edit-path-2d)))
```

The six face presets are the proxy bounding box's faces, which mean something for
a box and nothing for a cage — so on a cage they are not offered. Use `1`/`2`/`3`
instead: they put the anchor at the cage **centre** in each ring's plane, which is
a known starting point and a useful one, since the part sits at the centre by
construction. The white ball is the anchor's point; the solid cage around it is
there so a plane passing **behind** a ring tells you its depth.

## Reading the numbers

Each photograph carries its reprojection residual in pixels, on its thumbnail
and in the panel. It is the one number that says whether a registration is worth
building on — but read it knowing what it cannot tell you:

- **A low residual does not prove the orientation.** A crown of equal marks is
  symmetric, so an identity rotated by one mark reprojects just as perfectly.
  Only the zero-index — the disc set inside the crown — says which way the plate
  faces. If it is covered by the object, the session says so and asks you to turn
  the plate; that refusal is the feature.
- **On a cage, a low residual does not prove the FACE either.** Both faces of a
  ring carry the same discs seen through the plastic, so a mark named as its
  mirror twin produces a rotated pose that reprojects perfectly. Cages printed
  from 2026-08-22 carry the zero-index off mark 0's axis, which makes the figure
  chiral and the face legible; an older cage must be declared
  `(registration-cage :d … :index-phase 0)` and read with care.
- **A joint refine that changes nothing is accusing the measurements**, not the
  poses. If `R` moves the residual by a few percent, the picks themselves are
  wrong somewhere, and the per-photograph lines it prints will say which one.
- **A residual that will not come down, and swings from photograph to
  photograph, is usually the plate.** See below.

## Measuring the plate — `C`

Everything here is measured against the registration plate, and until you press
`C` the plate is an assumption: the marks are wherever `registration-plate`
computed them. A printed plate need not oblige — a 300mm disc warps as it cools,
and a mark standing proud of the plane projects differently depending which way
the camera looks across it, which is what a residual that changes as the plate
turns looks like.

`C` triangulates every mark from every view that saw it, re-solves the poses
against what it found, and repeats. It reports each mark's deviation split three
ways — out of the plane, along the radius, and around the crown — because those
mean different things: a warp, a print that came out the wrong size, and one that
placed a mark at the wrong angle.

**Then it tries the result on photographs that had no part in producing it, and
throws it away unless they agree.** This is the important half. A calibration's
own residual always falls — it was chosen to make it fall — so it cannot
distinguish a plate that is really warped from a fit that has quietly swallowed
the noise of the views it was handed. Only a held-out photograph can, and the
difference matters: adopting a bad plate makes every future session worse in a
way nothing else will report.

Expect refusals, and read them as being about the clicks. The first real ⌀300
produced a convincing 1.97mm warp with its residual falling from 2.18 to 1.78px,
and held out it made **eight of twelve photographs worse**. Looked at per mark
rather than per photograph, every bad frame turned out to carry exactly one blown
pick — 6 to 9px, with the other eleven marks sub-pixel. With those dropped the
session sits at 0.6px instead of 2.1, and the same plate then measures flat to a
quarter of a millimetre. It was six wrong clicks wearing the shape of a warp.

When `C` refuses it prints the held-out numbers per photograph, which is where to
look next.

Three more things are worth knowing:

- **Run it after `R`.** It reads the poses, so it inherits whatever is wrong with
  them.
- **It is filed under the plate, not the session** — `~/.ridley/plates/`, keyed by
  diameter and crown count. Every later session that declares the same plate picks
  it up and says so on entry. If you re-print that plate, delete the file: the
  store cannot tell two ⌀300 plates apart, and it will announce the one it has.
- **It measures shape, never size.** A plate 5% larger photographed 5% further
  away makes the identical image, so no set of views can separate them. Scale
  stays with `:d` and a caliper.

There is a second, cruder refusal underneath the held-out one: if the result
would move a mark more than 3% of the crown radius, `C` stops before it even
tests. A deviation that large is not a plate that is out of true — it is a
registration that went wrong upstream.

By default a mark may only move **perpendicular** to the plate. That is a
physical claim: a printer places ink to about a tenth of a percent (0.13mm on a
133mm radius), so nothing in the making of a plate shifts a mark a millimetre
sideways, while the surface itself warps by millimetres. Constraining the fit
that way also leaves it less freedom to absorb noise, which the held-out test
confirms — on a synthetically warped plate under 1.5px of click noise, the
constrained fit comes back at 1.91px held out against the free fit's 2.06px.

## Notes

- The session is modal: it owns the viewport and the keyboard until you leave
  it. The non-modal half — measuring on registered photographs — is `acquire`.
- `session.json` (which photographs, and their turntable angles) and
  `acquire-state.json` (the camera poses, the clicks, the focal) live in the
  folder. Deleting the second one throws away the registration and nothing else.
- A live-grabbed frame has no turntable angle. It is kept as a *free* photograph,
  which the ring model leaves alone by design.
- On the desktop app the camera needs macOS's permission the first time. In a
  browser it works on `localhost`; on a plain `http://` address from another
  machine it cannot work at all, because the camera requires a secure context.

## See also

- `acquire` — what this becomes when you confirm
- `registration-plate` — the usual `:proxy`
- `acquire-plate/save-sheet` — printing a plate to stand the object on
- `edit-edge-mark` — measuring an edge once the cameras are registered
- `registration-cage` — the `:proxy` for when depth is the problem
- `edit-path-2d` — where an anchor's outline actually gets drawn
- `acquire-union` — fusing two shooting sessions of the same object
