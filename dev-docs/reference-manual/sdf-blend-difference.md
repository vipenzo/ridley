---
name: sdf-blend-difference
category: sdf-modeling
since: ""
status: stable
---

# sdf-blend-difference

## Signature

`(sdf-blend-difference a b k)`

## Description

Smooth subtraction of `b` from `a`: removes `b`'s volume from `a` with
a soft concavity of radius `k` along the cut. Returns a new SDF tree.

`sdf-blend-difference` is the dual of `sdf-blend` for the subtraction
case. Where `sdf-difference` produces a sharp edge along the boundary,
`sdf-blend-difference` rounds the lip into a smooth depression — useful
for cavities, finger grooves, and any concavity that should look
organic rather than CNC-cut.

Anchors are inherited from the minuend (`a`) only, as with
`sdf-difference`.

> Desktop only: requires the libfive backend.

## Parameters

- `a` — the minuend SDF node.
- `b` — the subtrahend SDF node.
- `k` — concavity radius in world units. `0` reduces to a sharp
  difference; larger values broaden the smooth transition.

## Example

{{example: sdf-blend-difference-thumb-rest}}

<!-- example-source: sdf-blend-difference-thumb-rest -->
```clojure
;; Carve a thumb-rest into a block with a soft concavity
(register grip
  (sdf-blend-difference
    (sdf-rounded-box 50 30 20 4)
    (translate (sdf-sphere 8) 0 0 14)
    3))
```
<!-- /example-source -->

The sphere subtracts a depression; `k = 3` softens the rim so it does
not bite into the block as a sharp circle.

## Notes

- For mesh-side subtraction with rounded transitions you would normally
  combine `mesh-difference` with a separate `fillet` pass.
  `sdf-blend-difference` does both at once at the implicit level.
- Like `sdf-blend`, anchors come from the minuend; the subtrahend
  contributes nothing to the anchor map.

## See also

- **Sharp variant:** `sdf-difference`
- **Inverse (smooth union):** `sdf-blend`
- **Related operations:** `sdf-shell`, `sdf-offset`, `sdf-morph`
- **Mesh-side equivalent (multi-step):** `mesh-difference` + `fillet`
