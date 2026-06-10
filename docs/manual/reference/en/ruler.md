---
name: ruler
category: faces
since: ""
status: stable
---

# ruler

## Signature

`(ruler p q)`
`(ruler mesh-name face-id q)`
`(ruler p mesh-name face-id)`
`(ruler mesh-name face-id other-name other-face-id)`
`(ruler target :at anchor-name target :at anchor-name)`

## Description

Draw a visible distance ruler between two point specifications in the
viewport, and return the measured distance. Side-effecting (adds to the
ruler overlay) but also returns the numeric value, so `ruler` doubles as
a measurement call. Argument forms are identical to `distance`.

A point specification is one of:

- a `[x y z]` vector (a 2D `[x y]` is accepted too, padded to `z=0`),
- a registered mesh name (keyword) — resolves to its centroid,
- a mesh name followed by a face id (keyword) — resolves to that face's
  centre,
- `<target> :at <name>` — a named anchor / profile mark in world space
  (as placed by `extrude` / `loft` / `revolve`), so the ruler stays
  attached to the geometry; on a path it resolves a mark in the path's
  own frame.

The helpers `mid` (midpoint of two points, or of a path segment via
`(mid path i)`) and `seg-mid` produce points to measure to — handy with
the control-polygon `bezier-as :control`, whose curve passes through the
segment midpoints: e.g. `(ruler [0 45] (mid poly 1))`.

Rulers persist across REPL evaluations: useful for keeping reference
distances visible while iterating on geometry. Re-evaluating the buffer
(Cmd+Enter) clears rulers automatically; call `clear-rulers` to remove
them manually.

When the underlying mesh is being tweaked with `tweak`, rulers
re-resolve live as slider values change — so you can drag a parameter
and watch a distance update in real time.

## Parameters

- Two point specifications, in any combination of the forms above.

## Example

{{example: ruler-between-meshes}}

<!-- example-source: ruler-between-meshes -->
```clojure
(register a (box 20))
(register b (translate (box 20) 60 0 0))
(ruler :a :b)
;; → line drawn between the two centroids, with label "60"
```
<!-- /example-source -->

The ruler appears as a line with endpoint markers and a floating label
showing the distance.

## Variations

{{example: ruler-face-to-point}}

<!-- example-source: ruler-face-to-point -->
```clojure
(register a (box 20))
(ruler :a :top [0 50 0])     ; from top face centre to a point above
```
<!-- /example-source -->

Mix mesh+face and raw points freely. The argument shapes match
`distance`, so you can swap one for the other to toggle the visual.

## Notes

- Multiple rulers accumulate. To remove a specific one you must clear
  all and re-add the ones you want.
- For a silent measurement (no visual), use `distance` with the same
  arguments.
- Shift-clicking on a mesh in the viewport places ruler endpoints
  interactively — see Spec §8 Measurement for the input details.

## See also

- **Related:** `distance`, `clear-rulers`, `area`, `bounds`, `face-info`
