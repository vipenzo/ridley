---
name: edit-mesh-split
category: live-interactive
since: ""
status: stable
---

# edit-mesh-split

## Signature

`(edit-mesh-split mesh)`
`(edit-mesh-split mesh path)`
`(edit-mesh-split mesh path marks-vector)`

## Description

Decompose a mesh into pieces interactively, one plane cut at a time —
a guillotine session for `mesh-split`. Run it from the **definitions
panel** (Cmd+Enter).

**The turtle is the cut plane** — position and heading define it
directly, no second representation to keep in sync. Arrows move and
rotate the live pose the same way `pilot` does; a semi-transparent
quad renders the plane, with a small cone pointing along `+heading`
(the same direction the turtle's own nose always points) into
`:ahead` — `Enter` accepts the *opposite* side, `:behind`, same
convention as `extrude` (material trails behind the direction of
travel). You can also drag the plane directly in the viewport: the
same handle gizmo `edit-attach` uses (translate arrows + rotation
rings — no stretch, a cut plane has no scale gesture). The plane
itself (quad and cone) tracks the pointer every frame at zero cost —
the pieces never rigidly move with it, so the drag never reads as "the
mesh is moving". Colors update live during the drag too, not only on
release — throttled to whatever the mesh can actually afford (measured
live: from near-instant on a simple mesh up to a couple hundred ms
between updates on a dense one), so a cheap mesh feels continuous and
an expensive one still updates without freezing the interaction.

**Live semaphore.** Every keystroke (or gizmo drag tick) re-splits the
current remaining piece at the live pose and checks `convex?` on both
halves. The plane is colored by what the cut would do: **grey** when
`:behind` is empty (a no-op cut — `Enter` is disabled), a **dedicated
color** when `:ahead` is empty (*terminal placement* — the plane has
cleared the whole remaining piece), otherwise a neutral active tint.
The two live halves are colored independently by their own convexity:
**green** convex, **red** concave — and by solidity: `:behind` (what
`Enter` accepts) renders solid/opaque, `:ahead` renders washed out, so
which side is which reads at a glance regardless of hue. A status line
in the panel quantifies both sides by volume percentage, e.g. `behind
42% (convex) — ahead 58% (concave)`. Accepting a concave `:behind` is
allowed — the guillotine model has known limits — but never silent,
since red is already the warning.

**Single-action confirm.** `Enter` always means *accept `:behind` as
the definitive piece*; what that does follows from the plane's state,
not a separate key:

- grey (no-op) → disabled.
- active (both halves non-empty) → the cut is pushed onto the
  session's undo stack, the remainder becomes the new live piece, and
  the plane stays put — unless the new remainder is already convex,
  in which case the plane auto-jumps to terminal placement so a
  single further `Enter` closes cleanly.
- terminal placement (`:ahead` empty) → closes the session and emits,
  using the current remainder as the final piece. This does **not**
  add another cut — closing is stopping, not cutting.

`Ctrl`/`Cmd`+`Enter` is sugar for "teleport the plane to terminal
placement, then accept" — the same emission as if you'd moved there
by hand and pressed `Enter`. Useful to close out early with a
still-concave remainder; revisit it later by re-entering
`edit-mesh-split` on that piece. `Backspace` undoes the last accepted
cut. `Esc` cancels the whole session unconditionally, emitting
nothing. Accepted pieces stay visible in the viewport as a grey
wireframe (no convexity tint, no solid fill — that's reserved for the
current cut's two live halves) so the already-cut pieces read as
unmistakably done at a glance, never confusable with the live cut.

**On confirm**, the marker is rewritten to the composite `mesh-split`
form with a nested destructuring `let` and named pieces:

```clojure
(let [{piece-1 :behind {piece-2 :behind remaining :ahead} :ahead}
      (mesh-split block
                  (path (f 10) (mark :cut-1) (th 25) (f 7.25) (rt 3.38) (mark :cut-2))
                  [:cut-1 :cut-2])]
  [piece-1 piece-2 remaining])
```

The keystroke transcript is never what's emitted — for each accepted
cut, the tool synthesizes the minimal canonical `(th …)(tv …)(tr
…)(f …)(rt …)(u …)` delta between the previous cut's pose (or the
entry pose, for the first) and this cut's pose, writing only the
components that aren't negligible (a straight cut emits just
`(f …)`). Numbers are never snapped to a grid — free values are the
norm for a perceptual tool, editable by hand afterward.

## Parameters

- `mesh` — the mesh (or keyword name, or SDF node) to decompose.
- `path`, `marks-vector` (optional) — re-entry: reopen an
  already-emitted `mesh-split` composite for further editing (or
  rename `mesh-split` → `edit-mesh-split` at the call site). The
  session's undo stack is rebuilt directly from the evaluated
  composite, not by replaying source text, so re-entering and
  immediately closing again reproduces byte-identical output.

## Example

{{example: edit-mesh-split-basic}}

<!-- example-source: edit-mesh-split-basic -->
```clojure
(register block (extrude (rect 20 20) (f 30)))
(edit-mesh-split (get-mesh :block))
```
<!-- /example-source -->

## Notes

- Linear, not a general BSP: each accepted cut permanently detaches a
  piece from the remainder, so a wrong early cut can't be edited in
  place later without invalidating everything after it — fix one by
  editing the emitted form's `path` directly, or re-enter on the
  piece that needs it.
- Keymap mirrors `edit-attach`'s step/angle scheme (minus its scale
  target): `Tab` cycles step ↔ angle, digits set the active value,
  arrows move (`f`/`rt`) or rotate (`tv`/`th`), Shift+arrows move
  vertically (`u`) or roll (`tr`). The step/angle grid also governs the
  gizmo drag's snap; Shift+drag bypasses it for a free value.
- Mouse and keyboard produce the same thing either way: a pose. The
  emitted form never distinguishes how a cut's pose was reached.

## See also

- **Related:** `mesh-split`, `split-parts`, `convex?`, `edit-attach`
