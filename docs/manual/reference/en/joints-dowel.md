---
name: joints-dowel
category: positioning-assembly
since: ""
status: stable
---

# joints-dowel

## Signature

`(joints/dowel m r l t)`

## Description

Part of the built-in **joints** library (activate it in the libraries
panel; functions are called with the `joints/` prefix). Every joint cuts
the mesh at the turtle's current pose — plane = position, normal = heading,
exactly like `mesh-split` — and builds the joint on the joint axis =
heading, centred on the position, straddling the cut plane. It returns
`{:a mesh :b mesh :extras [mesh …]}`: `:a` is the `:ahead` side and carries
the male part, `:b` the `:behind` side with the female part, `:extras` the
pieces to print separately.

`dowel` is the separate pin: identical holes in `:a` and `:b`, the pin
itself in `:extras`. Both pieces stay flat on the cut face, the easiest
orientation to print.

## Parameters

- `m`, `r`, `l`, `t` — as in `joints-tenon`.

## Example

```clojure
(def ab (joints/dowel (box 40 40 40) 5 20 0.2))
(register A (:a ab))
(register B (:b ab))
(register pin (first (:extras ab)))
```

## Notes

- For several dowels on a wide face, place them with `layout-anchors`
  and build pins and holes with `on-anchors` instead.

## See also

- **Related:** `joints-tenon`, `layout-anchors`, `on-anchors`
