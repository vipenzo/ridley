---
name: reset-material
category: registration-visibility
since: ""
status: stable
---

# reset-material

## Signature

`(reset-material)`

## Description

Reset the turtle's global material state to defaults. After the call,
subsequent meshes created at the REPL or via `register` no longer
inherit any previously-set `color` or `material` properties. **Modifies
turtle state**: the change persists across subsequent calls until
overridden again.

Has no effect on already-registered meshes — they keep whatever material
was baked into them at creation time.

## Parameters

None.

## Example

{{example: reset-material-basic}}

<!-- example-source: reset-material-basic -->
```clojure
(material :metalness 0.8 :color 0xff0000)
(register part-a (box 10))            ;; metallic red
(reset-material)
(register part-b (do (f 20) (box 10))) ;; default appearance
```
<!-- /example-source -->

Set a material, build a part, reset, build a second part with the
default material.

## See also

- **Related:** `color`, `material`
