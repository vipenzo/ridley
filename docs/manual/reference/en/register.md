---
name: register
category: registration-visibility
since: ""
status: stable
---

# register

## Signature

`(register name expr)`
`(register name expr :hidden)`
`(r name expr)`
`(r name expr :hidden)`

## Description

Macro. Bind a name to a value and add it to the scene registry. For
renderable values (meshes, panels) the object also becomes visible by
default. Subsequent re-evaluations update the underlying value but
preserve the current visibility state.

`register` is polymorphic: it dispatches on the type of `expr`:

- **Mesh** — adds to the mesh registry, marks visible.
- **Vector / map of meshes** — each entry is registered with a
  sub-name (`name/0`, `name/1`, …, or `name/k` for maps) so prefix
  matching by `show` / `hide` reaches whole subtrees.
- **Panel** — registers as a renderable panel (use `out` / `append` /
  `clear` to set content).
- **Path** — abstract, no visibility. Stored for use by `with-path`,
  `extrude`, `revolve`, etc.
- **Shape** — abstract, no visibility. Available to extrusion ops.
- **Other values** — bound to the name; no scene side-effect.

`r` is a short alias of `register` with identical semantics.

## Parameters

- `name` — a symbol. A var is `def`'d with this name; the registry key
  is the corresponding keyword.
- `expr` — the value to bind. Type drives the dispatch above.
- `:hidden` — flag. Registers the value but starts hidden (skip the
  initial show). Applies to renderable types.

## Example

{{example: register-mesh}}

<!-- example-source: register-mesh -->
```clojure
(register torus (extrude-closed (circle 5) (path (dotimes [_ 4] (f 20) (th 90)))))
;; binds the var `torus`, registers as :torus, shows it in the viewport
```
<!-- /example-source -->

Register a mesh — single line creates the var, makes the object
addressable as `:torus`, and shows it.

## Variations

{{example: register-hidden}}

<!-- example-source: register-hidden -->
```clojure
(register support-frame (extrude (rect 20 20) (f 50)) :hidden)
;; registered but not shown — use (show :support-frame) when needed
```
<!-- /example-source -->

The `:hidden` flag is useful for support geometry, debug overlays, or
parts that are toggled on demand.

{{example: register-assembly}}

<!-- example-source: register-assembly -->
```clojure
(register puppet {:torso (box 12 6 20)
                  :head  (do (f 10) (sphere 5))})
;; registers :puppet/torso and :puppet/head — prefix matching with show/hide reaches both
```
<!-- /example-source -->

A map of meshes builds a small assembly: each entry becomes a registered
sub-mesh under the parent name. `(hide :puppet)` hides every sub-mesh
with the matching prefix.

{{example: register-alias}}

<!-- example-source: register-alias -->
```clojure
(r quick (sphere 8))
;; identical to (register quick (sphere 8))
```
<!-- /example-source -->

Use `r` when you want the shortest possible form.

## Hierarchical assemblies

When `register` is used with a **map literal** inside a `with-path` scope, it
behaves as an articulated-assembly constructor: nested keys produce qualified
names, and parent/child links are inferred from the `goto` calls that precede
each inner expression. The pattern is the recommended way to build skeletons,
puppets, and any rig where parts move together.

Three rules drive the behaviour:

- **Name prefixing.** Each map entry registers under `parent/key`. Nested map
  literals add further prefix segments, so an arm's upper segment ends up
  registered as `:puppet/r-arm/upper`.
- **Link inference.** When a body starts with `(goto :anchor)`, the resulting
  mesh is automatically linked to its parent at that anchor — no explicit
  `link!` call needed. All inferred links carry `:inherit-rotation true` by
  default.
- **Auto-attach to skeletons.** Inside `with-path`, the path is auto-attached
  to the first mesh produced in that frame, so subsequent `goto` calls resolve
  the skeleton's marks relative to that mesh.

Show/hide then operate on the qualified names with prefix matching, so a whole
subtree can be hidden in one call.

{{example: register-hierarchical-puppet}}

<!-- example-source: register-hierarchical-puppet -->
```clojure
(def body-sk (path (mark :shoulder-r) (rt 7) (mark :shoulder-l) (rt -14)))
(def arm-sk (path (mark :top) (f 15) (mark :elbow)))

(with-path body-sk
  (register puppet
    {:torso (box 12 6 20)
     :r-arm (do (goto :shoulder-r)
                (with-path arm-sk
                  {:upper (cyl 3 15)
                   :lower (do (goto :elbow) (cyl 2.5 12))}))}))

;; Creates:
;;   :puppet/torso                              (root)
;;   :puppet/r-arm/upper -> :puppet/torso       at :shoulder-r
;;   :puppet/r-arm/lower -> :puppet/r-arm/upper at :elbow
;; All inferred links have :inherit-rotation true.
;; The body skeleton auto-attaches to :puppet/torso; the arm skeleton
;; auto-attaches to :puppet/r-arm/upper.
```
<!-- /example-source -->

The torso registers as the root. The right arm's upper segment is registered
under `:puppet/r-arm/upper` and, because its body starts with
`(goto :shoulder-r)`, gets linked to the torso at the `:shoulder-r` anchor.
The lower segment follows the same rule against its parent's `:elbow` anchor.
The two skeletons auto-attach to the first mesh in their respective `with-path`
frames, so the `goto` calls resolve correctly.

Prefix matching lets show/hide reach entire subtrees:

```clojure
(hide :puppet/r-arm)             ;; hides :upper and :lower together
(show :puppet)                   ;; shows every :puppet/* mesh
(hide :puppet/r-arm/lower)       ;; hides just the forearm
```

See also `with-path`, `goto`, `attach-path`, `link!`, `show`, `hide`.

## Notes

- For abstract types (paths, shapes), `register` is the recommended way
  to make the value available across REPL evaluations without manually
  managing `def`.
- Inside `with-path`, a map literal under `register` triggers the
  hierarchical assembly system: qualified names and link inference from
  `goto` calls (see Spec §13 *Hierarchical assemblies*).
- Re-registering a name preserves its current visibility (so a hidden
  object stays hidden across edits).

## See also

- **Related:** `show`, `hide`, `show-all`, `hide-all`,
  `show-only-objects`, `objects`, `registered`, `panel`, `with-path`
