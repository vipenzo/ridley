---
name: edit-attach
category: live-interactive
since: ""
status: stable
---

# edit-attach

## Signature

`(edit-attach mesh)`
`(edit-attach mesh cmd …)`

## Description

Place a mesh **by hand**, and keep the placing as code. The session opens a gizmo
on the object — translation arrows, rotation rings, stretch handles — and every
drag or arrow key becomes a turtle command. On confirm, the form you ran is
rewritten into a plain `(attach …)` carrying the commands you made.

```clojure
(edit-attach coperchio)                  ;; start placing
(edit-attach coperchio (f 10) (th 45))   ;; reopen with what you already had
```

Confirming replaces `edit-attach` with `attach`; cancelling strips the `edit-`
and leaves the `attach` (or the bare mesh, if there was no body) exactly as it
was. So the two forms are one object in two states, and you move between them by
adding or removing five characters — the same round trip as `edit-path` and
`edit-acquire`.

Pre-existing commands come in **verbatim**: preserved character for character,
appended to by new gestures, removed one at a time by undo. Confirming without
touching anything is the identity on your source.

## Parameters

- `mesh` — the mesh or SDF node to place. It is placed *as it already stands* in
  the scene, so anything upstream of this form is already applied.
- `cmd …` — turtle commands the session starts from (`(f 10)`, `(th 45)`, `(u 3)`,
  `(scale 1.2)` …). This is simply what a previous session wrote.

## Mouse & keys

| | |
|---|---|
| drag arrows / rings / cubes | move · rotate · stretch |
| `Shift`+drag | free — bypass the snap |
| `Tab` | cycle the active value: step / angle / scale |
| digits | set the active value |
| `←` `→` `↑` `↓` | move by step (`f`, `rt`) · rotate by angle (`th`, `tv`) · stretch by scale |
| `Shift`+`↑` `↓` | `u` · `tr` · stretch along up |
| `Alt`+arrows | **cp** — slide the geometry under the pose (`Alt`+`Shift`+`↑↓` for `cp-u`) |
| `Backspace` | undo one command |
| **Simplify** | rewrite the list in the fewest commands, same placement |
| `Enter` | confirm · `Esc` cancel |

## SpaceMouse (desktop app)

In the desktop app on macOS, a 3Dconnexion SpaceMouse drives the session too. The
cap *is* the object, seen from behind the turtle:

| translation | | rotation | |
|---|---|---|---|
| push away / pull back | `f` | tilt the far edge down / up | `tv` |
| push right / left | `rt` | tilt the right edge down / up | `tr` |
| lift / press | `u` | twist clockwise / counter-clockwise | `th` |

The harder you push, the faster the values grow. The object glides along live,
and **the commands are written when you let go**, each rounded to its step (the
panel shows them as you go). Hold `Shift` for free values instead. `Backspace`
while still holding drops the gesture; `Enter` includes it. In Origin mode the
same gestures emit the `cp-` commands.

A panel button chooses which axes count, and is remembered:

- **all axes** — every movement of the cap is honored: you can move and turn in
  one gesture. Pushing the cap tends to tilt it a little too; small couplings
  round away to zero, larger ones show up as an extra command you can undo or
  correct.
- **Tab axes** — only the active mode's: step mode moves (`f` `rt` `u`), angle
  mode turns (`th` `tv` `tr`), one axis per gesture, so a push never turns the
  object. `Tab`, or the SpaceMouse's right button, switches mode.

The **left button** of the SpaceMouse (or the panel's **SpaceMouse: object /
view** button) hands the cap to the view and back: while it says *view*, the same
cap orbits the camera, as it does outside any session. Leaving the object writes
a gesture still in progress; coming back waits for the cap to return to rest.

While the Ridley window is in front the SpaceMouse belongs to Ridley; otherwise
macOS uses it as a mouse.

## Fewer commands

On confirm, the list is merged **exactly**: moves in a row are summed axis by
axis (`(f 10) (rt 5) (f 3) (rt -5)` → `(f 13)`), turns about the same axis in a
row are summed, stretches in a row are multiplied, and whatever cancels out
disappears. The values stay the ones you stepped. Plain moves and `cp-` moves
are condensed apart: one moves the pose, the other the geometry under it, and
the two don't interfere.

**Simplify** goes further: it rewrites each stretch of gestures as its minimal
form — at most `f rt u th tv tr`, and `cp-f cp-rt cp-u cp-th cp-tv cp-tr` for the
origin — rounded to 0.01. So `(f 10) (th 90) (f 10) (th -90)` becomes
`(f 10) (rt -10)`. The values are generally no longer round, so a stretch is
rewritten only when that makes it shorter. Commands already in the source when
the session opened are folded too when they are plain numbers (`(f 10)`);
anything else (`(mark …)`, `(move-to …)`) stays as written and condensing
stops at it. `Backspace` right after Simplify restores the list it replaced.

When the **SpaceMouse** has written commands in the session, confirming takes
the Simplify form by itself: a gesture's values are a gesture's anyway, while
values typed or stepped from the keyboard are kept as they are. Commands that
were already in the source are left alone on confirm; only the button folds
them.

## Two things move, and it matters which

The **Mode** button switches what a gesture acts on:

- **Object** — the mesh moves and its creation pose goes with it. This is
  placing.
- **Origin** — the pose stays and the *geometry slides underneath it*. This is
  choosing where the object's own origin sits, which is what everything attached
  to it later will be measured from.

In Origin mode, clicking a vertex snaps the pose onto it (`Alt`+click takes the
nearest point on a face instead). Up to three of those are undoable.

## Notes

- The readout above the buttons shows the value the **next** command will carry,
  not the last one — so you can see what a keypress is about to do.
- The gizmo measures the transform actually applied to the value, rather than
  assuming it is the identity: a mesh that arrives here already moved by code
  upstream still gets a gizmo on the object you can see, not on the origin.
- `pilot` is a legacy alias for the same session.
- `edit-attach-request!` is the low-level entry point this macro calls. You do
  not normally write it.

## See also

- `attach` — what this becomes when you confirm
- `edit-attach-request!` — the low-level form
- `move-to` — placing by mating one anchor to another, without a gizmo
- `edit-path` — the same round trip, for a path
