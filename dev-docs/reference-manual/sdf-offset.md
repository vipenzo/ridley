---
name: sdf-offset
category: sdf-modeling
since: ""
status: stable
---

# sdf-offset

## Signature

`(sdf-offset a amount)`

## Description

Shift the surface of an SDF by `amount`: positive values expand it,
negative values contract it. Returns a new SDF tree.

**Important caveat.** `sdf-offset` shifts the entire distance field by
`amount`, which produces a result that is no longer a true SDF away
from the surface. Combining the offset shape with `sdf-intersection`,
`sdf-difference`, or `sdf-blend` against other SDFs may produce
artefacts: the gradient is not unit-magnitude, so booleans can carve
the wrong volume near the offset.

When the goal is a rounded box, prefer `sdf-rounded-box` — that is a
true SDF and combines cleanly. When the goal is to thicken a shell
shape, you usually want `sdf-shell` instead. Reserve `sdf-offset` for
quick experiments and stand-alone offset shapes that you never combine
with another SDF.

> Desktop only: requires the libfive backend.

## Parameters

- `a` — an SDF node.
- `amount` — offset in world units. Positive expands; negative
  contracts.

## Example

{{example: sdf-offset-stand-alone}}

<!-- example-source: sdf-offset-stand-alone -->
```clojure
;; Expand a box by 2 units in every direction (stand-alone use)
(register pad (sdf-offset (sdf-box 18 18 18) 2))
```
<!-- /example-source -->

Used alone, the resulting mesh is exactly what you expect; the
non-SDF caveat only matters when you combine with other SDFs.

## Notes

- For corner rounding that combines cleanly: `sdf-rounded-box`.
- For wall hollowing: `sdf-shell`.
- Negative offsets larger than the smallest feature of `a` will
  collapse the shape (the implicit volume goes to zero).

## See also

- **Prefer for rounding:** `sdf-rounded-box`
- **Prefer for shells:** `sdf-shell`
- **Related:** `sdf-blend`, `sdf-displace`, `sdf-morph`
