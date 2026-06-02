---
name: reset
category: turtle-movement
since: ""
status: stable
---

# reset

## Signature

`(reset)`
`(reset [x y z])`
`(reset [x y z] & {:keys [heading up]})`

## Description

Reset the turtle's pose without clearing any accumulated geometry or
meshes. Default reset sends the turtle back to the origin facing `+X`
with up `+Z`; an explicit position vector relocates the turtle without
changing the default orientation; keyword overrides set heading and/or
up directly. **Modifies turtle state.**

`reset` does not touch the registry — registered meshes stay registered
and visible.

## Parameters

- `[x y z]` — target position. Optional; default `[0 0 0]`.
- `:heading [hx hy hz]` — heading vector. Optional; default `[1 0 0]`.
- `:up [ux uy uz]` — up vector. Optional; default `[0 0 1]`.

## Example

{{example: reset-basic}}

<!-- example-source: reset-basic -->
```clojure
(f 30) (th 45) (f 20)        ;; turtle is somewhere off-origin
(reset)                       ;; back to origin, default heading and up
```
<!-- /example-source -->

Send the turtle home after exploring.

## Variations

{{example: reset-position}}

<!-- example-source: reset-position -->
```clojure
(reset [10 20 30])
;; turtle is at [10 20 30], default heading +X, up +Z
```
<!-- /example-source -->

Relocate to a specific position keeping the canonical orientation.

{{example: reset-pose}}

<!-- example-source: reset-pose -->
```clojure
(reset [0 0 0] :heading [0 1 0] :up [0 0 1])
;; turtle at origin, heading +Y, up +Z
```
<!-- /example-source -->

Full pose control via keyword overrides.

## Notes

- For an **isolated** reset that doesn't touch the global turtle, use
  `(turtle :reset …)`.
- `reset` does not clear pen lines from the current evaluation — the
  REPL clears lines automatically between commands.

## See also

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `turtle` (`:reset`), `f`, `th`, `tv`, `tr`
