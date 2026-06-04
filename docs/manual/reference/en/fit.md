---
name: fit
category: 2d-shapes
since: ""
status: stable
---

# fit

## Signature

`(fit obj & {:keys [x y]})`

## Description

Scale a path or a 2D shape so it fits target dimensions along one or both
axes. Polymorphic: dispatches on the argument type (`:path` or `:shape`).
Specify `:x` and/or `:y` to set the desired extent for each axis; the
unspecified axis is left as-is. Scaling is anchored relative to the
origin. Does not modify turtle state.

When applied to a path, marks are preserved at their corresponding
waypoint indices.

## Parameters

- `obj` — a path or a 2D shape.
- `:x` — target extent along X.
- `:y` — target extent along Y.

At least one of `:x` or `:y` must be supplied; otherwise `fit` is a no-op.

## Example

{{example: fit-shape}}

<!-- example-source: fit-shape -->
```clojure
(register card (extrude (fit (poly 0 0 100 0 100 60 0 60) :x 80 :y 50) (f 3)))
```
<!-- /example-source -->

Force a rectangular contour to a target width and height regardless of
its original size.

## Variations

{{example: fit-path}}

<!-- example-source: fit-path -->
```clojure
(def silhouette (path (f 5) (th 80) (f 15) (arc-h 5 -160) (f 15)))
(register tall-vase (revolve (fit silhouette :y 180)))
```
<!-- /example-source -->

Stretch a recorded path to a target height (Y extent) and revolve.
The X extent is left untouched, so the revolve radius stays unchanged.

## Notes

- `fit` is polymorphic over shape and path. The shape branch scales the
  outer contour and any holes; the path branch scales waypoints and
  re-emits the path while preserving marks.
- For uniform scaling instead of axis-by-axis target extents, use
  `scale-shape` (or the polymorphic `scale`).

## See also

- **Related:** `scale-shape`, `resample-shape`, `bounds-2d`,
  → `scale` (polymorphic, scheda futura)
