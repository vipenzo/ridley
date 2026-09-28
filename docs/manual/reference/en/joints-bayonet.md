---
name: joints-bayonet
category: positioning-assembly
since: ""
status: stable
---

# joints-bayonet

## Signature

`(joints/bayonet m r depth t & {:keys [n lug lr angle sense]})`

## Description

Part of the built-in **joints** library (activate it in the libraries
panel; functions are called with the `joints/` prefix). Every joint cuts
the mesh at the turtle's current pose — plane = position, normal = heading,
exactly like `mesh-split` — and builds the joint on the joint axis =
heading, centred on the position, straddling the cut plane. It returns
`{:a mesh :b mesh :extras [mesh …]}`: `:a` is the `:ahead` side and carries
the male part, `:b` the `:behind` side with the female part, `:extras` the
pieces to print separately.

`bayonet` is the twist-lock: a plug of radius `r` protruding by `depth`
from `:a` with `n` radial lugs near its tip; a bore in `:b` with `n`
L-shaped slots — an axial channel from the cut plane to the lug's depth,
then an arc of `angle` degrees. Insert with the pieces turned by `angle`,
twist, and the two pieces come back **aligned**: the channel sits `angle`
after the lug, the arc runs back to the lug and a little past it, so the
whole lug rests in its seat.

## Parameters

- `m` — the mesh to cut.
- `r` — plug radius.
- `depth` — how far the plug protrudes.
- `t` — clearance per side. **Printed 2026-09-28 with 0.3: easy fit,
  cube reassembled true.**
- `:n` — number of lugs (default `2`).
- `:lug` — radial protrusion of a lug (default `2`).
- `:lr` — lug radius (default `1.5`).
- `:angle` — locking rotation in degrees (default `45`).
- `:sense` — `1` or `-1`, the direction of the arc (default `1`).

## Example

```clojure
(def ab (joints/bayonet (box 30 30 30) 8 12 0.3))
(register A (:a ab))
(register B (:b ab))
(tweak (mesh-board {:A A :B B} {:solid true :views [[:section :cut :offset 3]]}))
```

## Notes

- Slide the section's `:offset` to the lugs' depth (`depth − lr − 1`)
  to check channel, arc and lug in one picture.

## See also

- **Related:** `joints-thread`, `joints-tenon`, `mesh-board`, `tweak`
