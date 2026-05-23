---
name: joint-mode
category: generative-operations
since: ""
status: stable
---

# joint-mode

## Signature

`(joint-mode mode)`

## Description

Set the corner geometry mode for `extrude`, `extrude-closed`, `loft`, and
`bloft`. **Modifies turtle state**: the chosen mode persists on the
turtle until changed again, so it affects every subsequent extrusion
that turns through a corner.

The three modes control how consecutive rings are joined when the path
direction changes (e.g. at a `(th 90)`):

- `:flat` (default) — sharp corners; rings meet at an angle.
- `:round` — smooth rounded corners; intermediate rings are inserted.
- `:tapered` — bevelled / tapered corners; intermediate rings shrink
  toward the bend.

## Parameters

- `mode` — `:flat`, `:round`, or `:tapered`.

## Example

{{example: joint-mode-basic}}

<!-- example-source: joint-mode-basic -->
```clojure
(joint-mode :round)
(register pipe (extrude (circle 5) (f 20) (th 90) (f 20)))
(joint-mode :flat)   ;; restore default
```
<!-- /example-source -->

Switch to rounded joints, build a pipe with a 90° turn, then restore the
default. Without the `joint-mode` call the corner would be sharp.

## Variations

{{example: joint-mode-tapered}}

<!-- example-source: joint-mode-tapered -->
```clojure
(joint-mode :tapered)
(register bracket (extrude (rect 10 5) (f 30) (th 120) (f 20)))
(joint-mode :flat)
```
<!-- /example-source -->

`:tapered` creates a bevelled corner — useful for stylised brackets and
mitred joins.

## Notes

- `joint-mode` mutates turtle state. Reset it explicitly (or rely on
  scoping constructs like `turtle` blocks) if subsequent extrusions
  should use a different mode.
- The setting has no effect on `revolve` (corner geometry is implicit in
  the revolution) or on `shell` / `woven-shell` (their wall layout is
  driven by per-ring thickness, not the path-corner setting).

## See also

- **Guide:** placeholder → cap. 4 (Estrusione)
- **Related:** `extrude`, `extrude-closed`, `loft`, `bloft`
