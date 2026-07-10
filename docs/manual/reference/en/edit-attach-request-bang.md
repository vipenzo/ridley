---
name: edit-attach-request!
category: live-interactive
since: ""
status: experimental
---

# edit-attach-request!

## Signature

`(edit-attach-request! quoted-mesh mesh-value quoted-body attached-value marker-kind)`

## Description

Low-level entry point of the **edit-attach** session: an interactive mode in which a viewport gizmo (translation arrows, rotation rings, stretch handles on the mesh's creation-pose) and the keyboard drive turtle commands to position, orient and deform a mesh or SDF node. On confirmation, the editor source is rewritten: the `(edit-attach ...)` form is replaced by a flat `(attach mesh cmd1 cmd2 ...)` form carrying the accumulated commands. On cancel (Esc) the prefix is stripped instead: the form reverts to the plain `(attach ...)` with its original body, or to the bare mesh expression if there was no body.

Most users never call `edit-attach-request!` directly. The user-facing forms are the `edit-attach` macro and its legacy alias `pilot`:

```clojure
(edit-attach cubo)                     ;; bare mesh, empty command list
(edit-attach cubo (f 10) (th 45))     ;; reopen with pre-existing commands
(pilot cubo)                           ;; legacy alias, same session
```

The macro evaluates the body once (the session opens on the scene as it already stands, with the pre-existing commands applied) and passes the pieces to `edit-attach-request!`. Pre-existing commands enter the session as verbatim items: preserved character for character, appended-to by new gestures, and removable one by one with undo. Confirming without gestures is the identity on the source.

## Parameters

- `quoted-mesh` — the mesh source form as written, unevaluated (e.g. `'cubo`).
- `mesh-value` — the evaluated mesh (a map with `:vertices`) or SDF node (a map whose `:op` is a string). Any other type raises a descriptive error.
- `quoted-body` — unevaluated seq of the pre-existing body command forms (empty for a bare mesh or for the `pilot` alias).
- `attached-value` — `mesh-value` with `quoted-body` already applied via `attach`: the value the current eval produces and previews.
- `marker-kind` — `:edit-attach` or `:pilot`: which literal head this invocation was written with, so the marker search targets only that head's occurrences. The form is located with paren-balanced matching, so multi-line bodies are fine.

## Example

{{example: edit-attach-basic}}

<!-- example-source: edit-attach-basic -->
```clojure
;; Enter edit-attach with the session's result registered:
;; the call sits where its value is consumed
(register cubo (edit-attach (box 20)))
;; → gizmo on the creation-pose: arrows translate, rings rotate,
;;   handles stretch; arrow keys nudge on the snap grid;
;;   OK rewrites the form to (register cubo (attach (box 20) ...)),
;;   Esc cancels back to (register cubo (box 20))
```
<!-- /example-source -->

After confirmation, the `(edit-attach (box 20))` form is replaced in place with an `(attach (box 20) (f 10) (th 30) ...)` form that captures the session's commands, consecutive compatible commands compacted; the surrounding `register` is untouched. A bare `(edit-attach ...)` at top level would edit a value that nothing consumes, and the result would be lost on confirm. A future re-evaluation of the file reproduces the result exactly, and the `attach` can be reopened with the "Edit" context-menu entry (or by re-adding the `edit-` prefix).

## Notes

- **Two input layers.** The gizmo is the coarse layer: each completed drag emits one command, snapped to the session's step/angle/scale grid (hold Shift for free movement). The keyboard is the precision layer, with the three historical modes (movement, rotation, scale) and quantized steps on the same parameters.
- **Object vs origin mode.** A panel toggle switches the gesture semantics: in origin mode the same handles emit the `cp-*` family (the anchor moves relative to the geometry), and a click on a mesh vertex snaps the pose there (Alt+click for the face centroid).
- **Stretch directions are material.** Stretch handles are drawn on, and stretch commands act along, the mesh's material axes; the pivot is the creation-pose position. The two frames diverge only when the history contains `cp` rotations.
- **Two contexts: REPL vs script.** REPL invocations skip the editor-rewrite step; script-mode invocations require the form to be present in the editor text or the call raises.
- **Single-slot interactive mode.** Edit-attach competes with the other modal evaluators (`tweak`, `edit-bezier`, `edit-path`, ...) for a global slot. Only one can be active at a time.
- **SDF support.** SDF nodes are accepted in addition to meshes; the session drives the node's creation-pose and the commands apply on confirm.
- **Modal session.** While the session is open the editor is **read-only** (the module rewrites its own source on confirm). **Switching workspace closes the session** before swapping the buffer.

## See also

- **Related:** `attach`, `tweak`, `edit-path`, `edit-bezier`, `cp-position`, `cp-rotation`, `stretch`
