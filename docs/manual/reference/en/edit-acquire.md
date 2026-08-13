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
- `opts` — the same map `acquire` carries (`:proxy`, `:pose`, `:shapes`,
  `:marks`, `:edges`). On a first open you usually give only `:proxy`.

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
| `d` | trace an outline on a declared face |
| `k` | place a named mark · `m` arm the physical marker |
| `v` | hide the proxy to read the photograph under it |
| `[` `]` | previous / next photograph |
| `Esc` | leave the current mode, then the session |

## Reading the numbers

Each photograph carries its reprojection residual in pixels, on its thumbnail
and in the panel. It is the one number that says whether a registration is worth
building on — but read it knowing what it cannot tell you:

- **A low residual does not prove the orientation.** A crown of equal marks is
  symmetric, so an identity rotated by one mark reprojects just as perfectly.
  Only the zero-index — the disc set inside the crown — says which way the plate
  faces. If it is covered by the object, the session says so and asks you to turn
  the plate; that refusal is the feature.
- **A joint refine that changes nothing is accusing the measurements**, not the
  poses. If `R` moves the residual by a few percent, the picks themselves are
  wrong somewhere, and the per-photograph lines it prints will say which one.
- **A residual that will not come down, and swings from photograph to
  photograph, is usually the plate.** See below.

## Measuring the plate — `C`

Everything here is measured against the registration plate, and until you press
`C` the plate is an assumption: the marks are wherever `registration-plate`
computed them. A printed plate does not oblige. On the first ⌀300 we measured,
three marks stood over a millimetre out of the plane and nine were within a
quarter — and that is what a residual which changes as the plate turns looks
like, since a mark standing proud projects differently depending which way the
camera looks across it.

`C` triangulates every mark from every view that saw it, re-solves the poses
against what it found, and repeats. It reports each mark's deviation split two
ways — out of the plane, and along the radius — because those mean different
things: the first is a warped plate, the second a print that came out the wrong
size or shape.

Three things are worth knowing before you use it:

- **Run it after `R`.** It reads the poses, so it inherits whatever is wrong with
  them.
- **It is filed under the plate, not the session** — `~/.ridley/plates/`, keyed by
  diameter and crown count. Every later session that declares the same plate picks
  it up and says so on entry. If you re-print that plate, delete the file: the
  store cannot tell two ⌀300 plates apart, and it will announce the one it has.
- **It measures shape, never size.** A plate 5% larger photographed 5% further
  away makes the identical image, so no set of views can separate them. Scale
  stays with `:d` and a caliper.

If the result would move a mark more than 3% of the crown radius, `C` refuses
instead of reporting. A deviation that large is not a plate that is out of true —
it is a registration that went wrong upstream, and adopting it would refer every
later measurement to a ruler invented to fit a mistake.

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
- `acquire-union` — fusing two shooting sessions of the same object
