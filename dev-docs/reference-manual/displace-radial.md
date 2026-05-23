---
name: displace-radial
category: generative-operations
since: ""
status: stable
---

# displace-radial

## Signature

`(displace-radial shape offset-fn)`

## Description

Displace each point of a 2D shape radially from its centroid by an
amount returned by `offset-fn`. The direction of displacement is the
unit vector from the centroid to the original point; positive offsets
push outward, negative offsets push inward. Holes are displaced in
lockstep. Returns a new shape. Does not modify turtle state.

Used to implement the built-in displacement shape-fns (`rugged`,
`fluted`, and the body of `displaced` for each ring). Reach for it
directly when writing a custom shape-fn that needs the same
radial-from-centroid behaviour without the full `displaced` /
`shape-fn` plumbing.

## Parameters

- `shape` — a 2D shape.
- `offset-fn` — function `(fn [point] -> number)` returning the radial
  offset for each vertex.

## Example

{{example: displace-radial-basic}}

<!-- example-source: displace-radial-basic -->
```clojure
(def lumpy
  (displace-radial (circle 15 64)
                   (fn [p] (* 1.5 (Math/sin (* (angle p) 5))))))

(register lumpy-prism (extrude lumpy (f 10)))
```
<!-- /example-source -->

A static 2D shape with a 5-fold sine displacement. Unlike `rugged` (a
shape-fn used by `loft`), this is a one-shot transformation usable with
`extrude` or shape booleans.

## Notes

- The offset direction is always from the centroid outward — for
  arbitrary direction displacements, build the new point coordinates
  directly with `make-shape`.
- Holes are also displaced, using the **outer** shape's centroid as
  origin. This keeps relative hole geometry consistent under most uses.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `displaced`, `rugged`, `fluted`, `angle`, `make-shape`
