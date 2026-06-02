---
name: selected
category: live-interactive
since: ""
status: stable
---

# selected (and the picking family)

## Signature

`(selected)`
`(selected-mesh)`
`(selected-face)`
`(selected-name)`
`(source-of mesh-name)`
`(origin-of mesh-name)`
`(last-op mesh-name)`

## Description

Seven REPL-callable helpers that expose the viewport's **picking state** — the currently selected mesh, the drilled face, and the construction history captured by `register`. Useful for live introspection while you click around the viewport, and as the data source for AI describe and custom debug overlays.

Picking has two drill levels: **object** (a whole mesh is selected) and **face** (after drilling in, a single coplanar face within that mesh). The helpers return `nil` when nothing is selected, so they are safe to call at any time.

| Function          | Returns                                                                                              |
|-------------------|------------------------------------------------------------------------------------------------------|
| `(selected)`      | Full selection map: `{:name :level :source-history}`, plus `:face-id` and `:face-info` at face level. |
| `(selected-mesh)` | The mesh data map for the selected object (vertices, faces, creation-pose, source-history). |
| `(selected-face)` | The integer face-id when at face drill level; `nil` otherwise.                                       |
| `(selected-name)` | The keyword name of the selected mesh; `nil` if nothing is selected.                                 |
| `(source-of n)`   | Pretty-prints each entry of `n`'s source-history (one line per op) and returns the history vector.   |
| `(origin-of n)`   | The first source-history entry for `n` — typically the `:register` op that introduced the mesh.     |
| `(last-op n)`     | The most recent non-`:register` source-history entry for `n` — the last operation applied to it.    |

`source-of`, `origin-of`, and `last-op` take a mesh name (keyword or string) and look it up in the scene registry; the others read from the live viewport picking state.

## Parameters

- `mesh-name` — keyword or string naming a registered mesh.

## Example

{{example: picking-debug-session}}

<!-- example-source: picking-debug-session -->
```clojure
;; Build a small scene
(register body (mesh-difference (box 30) (cyl 6 40)))
(register lid  (attach (cyl 14 4) (u 17)))

;; --- now click on the lid in the viewport ---

(selected-name)
;; => :lid

(selected-mesh)
;; => {:vertices [...] :faces [...] :creation-pose {...}
;;     :source-history [{:op :register :line 2 :source :definitions}
;;                      {:op :attach   :line 2 :source :definitions}]}

(source-of :lid)
;; prints:
;;   register L:2
;;   attach L:2
;; => same vector as :source-history above

(last-op :lid)
;; => {:op :attach :line 2 :source :definitions}

;; --- drill into a face (double-click), then ---

(selected-face)
;; => 7   (face-id of the picked coplanar face)

(:face-info (selected))
;; => {:normal [0 0 1] :center [0 0 19] :tri-count 12}
```
<!-- /example-source -->

The typical debug loop: pick a mesh in the viewport, call `selected-name` to confirm it, then `source-of` to see how it was built, and `last-op` to identify which operation produced the issue you are inspecting.

## Notes

- **Live state.** `selected`, `selected-mesh`, `selected-face`, and `selected-name` read the viewport's picking atom — values can change between two calls as you click around.
- **Source history is recorded by `register` and modifying ops.** Each entry carries `:op`, `:line`, and `:source` (`:definitions` for script-mode eval, `:repl` for REPL eval). The chain reads forward in time: `origin-of` is the oldest, `last-op` is the newest non-register entry.
- **Picking returns `nil` when nothing is selected.** Use `or` / `when` for safe defaults; these helpers never throw.
- **AI describe pipeline.** The describe-AI accessibility feature uses these helpers as its primary view of the current selection — adding more is a backward-compatible change.

## See also

- **Related:** `flash-face`, `highlight-face`, `info`
