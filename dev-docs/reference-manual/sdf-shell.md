---
name: sdf-shell
category: sdf-modeling
since: ""
status: stable
---

# sdf-shell

## Signature

`(sdf-shell a thickness)`

## Description

Convert an SDF solid into a hollow shell with uniform wall thickness.
Returns a new SDF tree representing the wall.

The shell is built around the original surface: walls extend half the
thickness inward and half outward of the original boundary, so the
external silhouette stays close to `a` while the interior is hollowed
out. Anchors are inherited from `a`.

`sdf-shell` is the building block for vases, containers, and any
printable solid that needs a defined wall. For containers with an open
top, combine with `sdf-difference` and a half-space (see
`sdf-half-space`).

When the shell becomes thin relative to the meshing resolution, the
auto-mesher automatically boosts resolution to keep at least 3 voxels
across the thinnest wall.

> Desktop only: requires the libfive backend.

## Parameters

- `a` — an SDF node to hollow out.
- `thickness` — wall thickness in world units.

## Example

{{example: sdf-shell-basic}}

<!-- example-source: sdf-shell-basic -->
```clojure
;; Hollow sphere with a 1-unit wall
(register shell (sdf-shell (sdf-sphere 15) 1))
```
<!-- /example-source -->

The result is a thin spherical shell, not a solid ball.

## Variations

{{example: sdf-shell-vase}}

<!-- example-source: sdf-shell-vase -->
```clojure
;; Vase from a revolved profile, then shelled to make it printable
(register vase
  (sdf-shell
    (sdf-revolve
      (sdf-formula '(- x (+ 30 (* 10 (cos (* y 0.05)))))))
    2))
```
<!-- /example-source -->

`sdf-revolve` produces the solid vase silhouette; `sdf-shell` hollows
it out with a 2-unit wall ready for printing.

## Notes

- Thin shells stress meshing resolution. The auto-resolver guarantees
  at least 3 voxels across the thinnest wall; for very thin or very
  large shells you may want to bump `sdf-resolution!` higher or supply
  explicit bounds via `sdf->mesh`.
- For an open container, subtract a half-space from the shell
  (e.g. `(sdf-difference (sdf-shell …) (sdf-half-space :cut-ahead))`).

## See also

- **Related:** `sdf-offset`, `sdf-rounded-box`, `sdf-difference`,
  `sdf-half-space`
- **Resolution control:** `sdf-resolution!`, `sdf->mesh`,
  `sdf-ensure-mesh`
