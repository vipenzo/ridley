---
name: distance
category: faces
since: ""
status: stable
---

# distance

## Signature

`(distance p q)`
`(distance mesh-name face-id q)`
`(distance p mesh-name face-id)`
`(distance mesh-name face-id other-name other-face-id)`

## Description

Measure the Euclidean distance between two point specifications. Returns
a number, or `nil` if either side cannot be resolved. Pure function; does
not modify turtle state.

A point specification is one of:

- a `[x y z]` vector — used as-is;
- a registered mesh name (keyword) — resolves to the mesh's centroid;
- a mesh name followed by a face id (keyword) — resolves to the face's
  centre.

The arguments are parsed greedily from left to right: a keyword followed
by another keyword *that names a face on it* is interpreted as
mesh+face; otherwise the keyword alone is interpreted as mesh+centroid.

`distance` is the silent companion of `ruler`. They share the same
argument forms; `distance` returns the number only, `ruler` returns the
number *and* draws a visible overlay.

## Parameters

- Two point specifications, in any combination of the forms above.
  Total argument count is 2, 3, or 4 depending on the mix.

## Example

{{example: distance-mixed}}

<!-- example-source: distance-mixed -->
```clojure
(register a (box 20))
(register b (translate (box 20) 60 0 0))

(distance :a :b)              ; centroid to centroid
;; => 60.0

(distance :a :top :b :top)    ; top-face to top-face
;; => 60.0

(distance :a :top [0 30 0])   ; face centre to point
;; => 30.0
```
<!-- /example-source -->

The same call accepts mesh names, face ids, and raw points freely mixed.

## Notes

- The mesh-centroid form uses the **vertex centroid**, the arithmetic
  mean of all vertex positions. For axis-aligned bounding-box centre,
  use `(:center (bounds mesh))`.
- Face centres are taken from the face's `:center` field — the area-
  weighted centroid of the face's triangles.
- For a visible overlay matching the same query, use `ruler`.

## See also

- **Related:** `ruler`, `clear-rulers`, `area`, `bounds`, `face-info`,
  `face-at`
