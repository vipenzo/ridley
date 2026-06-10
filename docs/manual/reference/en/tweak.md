---
name: tweak
category: live-interactive
since: ""
status: stable
---

# tweak

## Signature

`(tweak expr)`
`(tweak n expr)`
`(tweak [n1 n2 ...] expr)`
`(tweak :all expr)`
`(tweak :reg-name)`
`(tweak :reg-name expr)`
`(tweak filter :reg-name)`
`(tweak filter :reg-name expr)`

## Description

Macro for interactive parameter exploration with live sliders. Evaluates `expr`, displays the result in the viewport, and creates sliders for the numeric literals it finds in the source form. Moving a slider re-evaluates the expression with the substituted value (~100 ms debounce) and updates the preview.

Numeric literals are collected depth-first, left-to-right and indexed from `0`. A negative index counts from the end (Python-style). A vector of indices selects multiple literals; `:all` selects every literal in the form. **With no filter at all** — `(tweak expr)` — every literal gets a slider, the same as `:all` (the common case: tweak the whole thing).

When the first argument is a keyword, `tweak` switches to **registry mode**: it operates on the named registered mesh, hides the original during the session, re-registers the result on **OK**, and restores the original on **Cancel**. A bare `(tweak :name)` re-runs the source form that `register` stored automatically; the explicit form `(tweak :name expr)` overrides that with a new expression.

Each slider has a default range of `[value * 0.1, value * 3]` (or `[-50, 50]` when the literal is `0`) and zoom buttons (`-`/`+`) that re-centre and narrow or widen the range. Pressing **OK** confirms and prints the final expression; **Cancel** or **Escape** discards. Entering a new REPL command auto-cancels the session.

## Parameters

- `expr` — the expression to preview and tweak. Numeric literals inside it become slider-controlled.
- `n` — integer index of the literal to tweak (negative counts from the end).
- `[n1 n2 ...]` — vector of indices; one slider per selected literal.
- `:all` — slider for every numeric literal in the form (also the default when no filter is given).
- `:reg-name` — keyword naming a registered mesh; switches to registry mode.
- `filter` — index, index-vector, or `:all`; combines with a registry name.

## Example

{{example: tweak-default}}

<!-- example-source: tweak-default -->
```clojure
;; Default (no filter): one slider per literal — same as :all
(tweak (extrude (circle 15) (f 30)))
```
<!-- /example-source -->

Two sliders appear, for `15` and `30`. Drag them to see the cylinder's radius and length change in real time; press OK to commit the values back into the form. Pass an index (or vector, or `:all`) to narrow the set.

## Variations

{{example: tweak-index}}

<!-- example-source: tweak-index -->
```clojure
;; Index 2 picks the third literal — here, the rotation angle
;; Literals are collected depth-first: 0=15, 1=30, 2=90, 3=20
(tweak 2 (extrude (circle 15) (f 30) (th 90) (f 20)))
```
<!-- /example-source -->

Indices count every numeric literal in source order, including those nested inside sub-forms. Use `(tweak -1 ...)` to grab the last one without counting.

{{example: tweak-multi}}

<!-- example-source: tweak-multi -->
```clojure
;; Tweak the first and the last literal simultaneously
(tweak [0 -1] (extrude (circle 15) (f 30) (th 90) (f 20)))

;; All numeric literals at once
(tweak :all (extrude (circle 15) (f 30) (th 90) (f 20)))
```
<!-- /example-source -->

Vectors mix positive and negative indices freely. `:all` opens one slider per literal — useful when the form is small and you want to explore the whole parameter space at once.

{{example: tweak-registry}}

<!-- example-source: tweak-registry -->
```clojure
;; Register first, then tweak the stored source form
(register vase (extrude (circle 20) (f 50)))

;; No filter: sliders for every literal of the stored form (radius and length)
(tweak :vase)

;; Explicit :all is the same as the bare form
(tweak :all :vase)

;; Override the source: tweak with an explicit expression bound to :vase
(tweak :vase (extrude (circle 25) (f 60)))

;; Filter + registry + explicit expression
(tweak [0 -1] :vase (extrude (circle 25) (f 60)))
```
<!-- /example-source -->

Registry mode keeps the scene clean: the original `:vase` is hidden while you tweak and re-registered with the new value on **OK**, or restored to its previous form on **Cancel**.

## Notes

- **Source-form requirement.** `(tweak :name)` needs a stored source form. `register` stores it automatically, but the form must be self-contained — if you wrote `(register vase (make-vase 1))`, the `make-vase` function must still be defined at tweak time. Keep generator functions and registered meshes under distinct names (e.g. `make-vase` and `vase`).
- **Literal collection is syntactic, not semantic.** A literal `15` inside a comment or string is not collected; a `15` inside a quoted form is. Wrap a number in `(+ 0 N)` to hide it from the collector when you do not want a slider for it.
- **Live preview detects the result type.** Meshes, shapes, and paths each render with the appropriate preview widget; non-renderable results show a placeholder.
- **`anim-proc!` for time-driven exploration.** When the parameter you want to sweep is `t` rather than a discrete pick, `anim-proc!` gives you a 0→1 timeline and a `gen-fn` per frame; `tweak` is for ad-hoc value picking.
- **Modal session.** While a `tweak` session is open the editor is **read-only** (it rewrites its own source on confirm, so a hand-edit would break the substitution) and only one modal session — `tweak`, `edit-bezier`, or `pilot` — runs at a time. **Switching workspace closes the session** before swapping the buffer.

## See also

- **Related:** `register`, `anim-proc!`
