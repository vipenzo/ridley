---
name: material
category: registration-visibility
since: ""
status: stable
---

# material

## Signature

`(material & {:keys [color metalness roughness opacity flat-shading]})`
`(material name & {:keys [color metalness roughness opacity flat-shading]})`
`(material mesh & {:keys [color metalness roughness opacity flat-shading]})`

## Description

Set material properties. Polymorphic dispatch:

- **First argument is a material keyword** (`:color`, `:metalness`,
  `:roughness`, `:opacity`, `:flat-shading`) — set the **global**
  material on the turtle. Every mesh created after the call inherits
  these properties until `reset-material` is called. **Modifies turtle
  state.**
- **First argument is a registered name (keyword / string / symbol)** —
  update the material of the named mesh. Registry side effect; turtle
  state is unchanged.
- **First argument is a mesh value** — return a **new mesh** with the
  material properties merged in. Pure: no state mutation.

The kw/value pairs after the first argument define the material
properties to apply.

## Parameters

- `:color` — hex integer.
- `:metalness` — 0..1.
- `:roughness` — 0..1.
- `:opacity` — 0..1 (use values < 1 for translucency).
- `:flat-shading` — `true` / `false`.
- `name` — registered name (per-name form).
- `mesh` — mesh value (pure form).

## Example

{{example: material-global}}

<!-- example-source: material-global -->
```clojure
(material :metalness 0.8 :roughness 0.2)
(register knob (sphere 8))   ;; metallic, slightly rough
```
<!-- /example-source -->

Global form: subsequent meshes inherit the material properties.

## Variations

{{example: material-by-name}}

<!-- example-source: material-by-name -->
```clojure
(register bowl (make-bowl 30 20))
(material :bowl :opacity 0.3 :color 0xff0000)
```
<!-- /example-source -->

Per-name form: update an existing registered mesh in place. Useful when
the geometry was already built and only the appearance changes.

{{example: material-pure}}

<!-- example-source: material-pure -->
```clojure
(register glass (material (sphere 20) :opacity 0.3))
(tweak (material (sphere 20) :opacity 0.5))
```
<!-- /example-source -->

Pure form: pass a mesh value, get a new mesh with the material applied.
Works well inside `register` / `tweak` / threading.

## Notes

- The first-argument keyword disambiguates: a known material property
  triggers global form; anything else is treated as a registered name.
  Pass a mesh value directly to use the pure form.
- The global form composes with subsequent `color` calls — `color` sets
  only the `:color` slot of the active material.
- Use `reset-material` to clear the global material back to defaults.

## See also

- **Related:** `color`, `reset-material`, `register`, `tweak`
