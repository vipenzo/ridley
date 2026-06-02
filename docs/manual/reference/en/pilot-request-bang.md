---
name: pilot-request!
category: live-interactive
since: ""
status: experimental
---

# pilot-request!

## Signature

`(pilot-request! quoted-arg value)`

## Description

Low-level entry point of **pilot mode**: an interactive session in which the keyboard drives turtle commands to position or orient a mesh or SDF node directly in the viewport. On confirmation, the editor source text is rewritten — the `(pilot ...)` form is replaced by an `(attach! ...)` form carrying the commands you typed during the session.

Most users never call `pilot-request!` directly. The user-facing form is the `pilot` macro:

```clojure
(pilot :cubo)              ;; macro expands to (pilot-request! ':cubo :cubo)
(pilot (get-mesh :cubo))   ;; expands to (pilot-request! '(get-mesh :cubo) (get-mesh :cubo))
```

The macro captures the source form (for use as a search target in the editor) and the evaluated value (the mesh or SDF node to drive interactively).

When invoked, `pilot-request!` validates the value (must be a mesh map with `:vertices`, or an SDF node), locates the literal `(pilot ...)` text in the editor (REPL invocations skip this step), claims the global interactive-mode slot, and stashes the request. Control returns to the evaluator; the actual UI panel and keyboard handler are wired up afterward by `enter!`. During the interactive session, the arrow keys translate, **Shift+arrows** rotate, digit keys feed a vim-style count buffer, **Enter** confirms, and **Esc** cancels. **OK** rewrites the source; **Cancel** restores the original mesh and leaves the editor untouched.

## Parameters

- `quoted-arg` — the source form as written, unevaluated. Used both as a label in error messages and as the search pattern when locating `(pilot ...)` in the editor text.
- `value` — the evaluated mesh value (a map with `:vertices`) or an SDF node (a map whose `:op` is a string). Any other type raises a descriptive error.

## Example

{{example: pilot-basic}}

<!-- example-source: pilot-basic -->
```clojure
;; Register a mesh, then enter pilot mode to position it interactively
(register cubo (box 20))

;; Use the pilot macro — it expands to (pilot-request! 'cubo cubo)
(pilot cubo)
;; → enters interactive mode: arrows to move, Shift+arrows to rotate,
;;   Enter to confirm (rewrites the source as an attach! form), Esc to cancel
```
<!-- /example-source -->

After confirmation, the editor text containing `(pilot cubo)` is replaced with an `(attach! cubo (f 10) (rt 30) ...)` form that captures the commands you executed during the session. The mesh keeps its new position; a future re-evaluation of the file reproduces it exactly.

## Notes

- **Experimental.** The API surface and the keyboard mapping are still evolving; expect changes between releases. See `dev-docs/project_pilot_mode.md` for the current implementation roadmap.
- **Two contexts: REPL vs script.** Invocations from the REPL skip the editor-rewrite step (there is no `(pilot ...)` literal to find); script-mode invocations require the form to be present in the editor text or `pilot-request!` raises an error.
- **Single-slot interactive mode.** Pilot competes with other interactive modes (e.g. `tweak`) for a global slot. Only one can be active at a time; the second one raises.
- **SDF support.** Pilot accepts SDF nodes in addition to meshes. The interactive session drives the node's creation-pose; on confirm, the attached commands apply to the SDF.

## See also

- **Related:** `tweak`, `attach!`
