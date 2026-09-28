---
name: joints-tenon
category: positioning-assembly
since: ""
status: stable
---

# joints-tenon

## Signature

`(joints/tenon m r l t)`

## Description

Part of the built-in **joints** library (activate it in the libraries
panel; functions are called with the `joints/` prefix). Every joint cuts
the mesh at the turtle's current pose — plane = position, normal = heading,
exactly like `mesh-split` — and builds the joint on the joint axis =
heading, centred on the position, straddling the cut plane. It returns
`{:a mesh :b mesh :extras [mesh …]}`: `:a` is the `:ahead` side and carries
the male part, `:b` the `:behind` side with the female part, `:extras` the
pieces to print separately.

`tenon` is the integral pin: a cylinder of radius `r` protruding from `:a`
by half of `l`, a blind hole in `:b`.

## Parameters

- `m` — the mesh to cut.
- `r` — nominal pin radius.
- `l` — total pin length; half protrudes from the cut plane.
- `t` — clearance per side, in mm (pin radius `r − t`, hole `r + t`).
  0.2–0.3 for PLA.

## Example

```clojure
(def ab (joints/tenon (box 40 40 40) 5 20 0.2))
(register A (:a ab))
(register B (:b ab))
(mesh-board {:A A :B B} {:solid true :views [[:section :cut :offset 3]]})
```

## Notes

- The pin needs support-free printing only if `:a` is printed with the
  pin pointing up; `dowel` avoids the problem entirely.

## See also

- **Related:** `joints-dowel`, `joints-bayonet`, `joints-thread`,
  `mesh-split`, `layout-anchors`, `mesh-board`
