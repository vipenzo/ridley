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
| `Enter` | confirm · `Esc` cancel |

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
