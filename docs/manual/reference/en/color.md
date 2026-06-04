---
name: color
category: registration-visibility
since: ""
status: stable
---

# color

## Signature

`(color hex)`
`(color r g b)`
`(color name-or-mesh hex)`
`(color name-or-mesh r g b)`

## Description

Set the colour of subsequently created meshes (global form), of a
specific registered mesh (per-name form), or return a coloured copy of
a mesh value (pure form). Polymorphic on the first argument:

- **First argument is a hex int or RGB triple** — set the **global**
  colour on the turtle's material state. Every mesh created after the
  call inherits this colour until `reset-material` or another `color`
  call overrides it. **Modifies turtle state.**
- **First argument is a registered name (keyword / string / symbol)** —
  update the material of that registered mesh. Side effect on the
  registry; turtle state is unchanged.
- **First argument is a mesh value** — return a **new mesh** with the
  colour merged into its material. Pure: no state mutation.

The colour itself may be a hex integer (`0xff0000`) or three 0–255 RGB
components.

## Parameters

- `hex` — hex integer (e.g. `0xff0000` = red).
- `r`, `g`, `b` — 0–255 components.
- `name-or-mesh` — registered name (per-name form) or mesh value (pure
  form).

## Example

{{example: color-global}}

<!-- example-source: color-global -->
```clojure
(color 0xff0000)            ;; subsequent meshes are red
(register part-a (box 10))
(color 0 200 80)            ;; switch to green
(register part-b (do (f 20) (sphere 6)))
```
<!-- /example-source -->

Global form: turtle state holds the active colour and propagates it to
new meshes.

## Variations

{{example: color-by-name}}

<!-- example-source: color-by-name -->
```clojure
(register knob (sphere 5))
(color :knob 0xffaa00)      ;; recolour the registered mesh
```
<!-- /example-source -->

Per-name form: update the colour of an existing registered mesh without
re-creating it.

{{example: color-pure-on-mesh}}

<!-- example-source: color-pure-on-mesh -->
```clojure
(register bowl (color (make-bowl 30 20) 0xff8800))
;; or via threading:
(register bowl (-> (make-bowl 30 20) (color 255 128 0)))
```
<!-- /example-source -->

Pure form: pass a mesh value, get back a coloured copy. Composes well
inside `register` / `tweak` expressions.

## Notes

- The global form affects everything created after the call. Pair with
  `reset-material` to restore defaults.
- The pure form is the safest in larger scripts: no global state, easy
  to compose.
- Per-name updates trigger a viewport refresh so the change is visible
  immediately.

## See also

- **Related:** `material`, `reset-material`, `register`
