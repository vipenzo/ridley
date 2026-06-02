---
name: sample-heightmap
category: generative-operations
since: ""
status: stable
---

# sample-heightmap

## Signature

`(sample-heightmap hm u v)`

## Description

Sample a heightmap at `(u, v)` with bilinear interpolation. The inputs
are wrapped modulo 1 before sampling, so the heightmap tiles
automatically. Returns the normalised value in `[0, 1]`. Does not
modify turtle state.

Used internally by the `heightmap` shape-fn and by custom shape-fns that
read directly from a heightmap.

## Parameters

- `hm` — a heightmap value.
- `u`, `v` — sampling coordinates, automatically wrapped to `[0, 1]`.

## Example

{{example: sample-heightmap-basic}}

<!-- example-source: sample-heightmap-basic -->
```clojure
(def hm (weave-heightmap :threads 4 :resolution 128))
(sample-heightmap hm 0.5 0.5)       ; centre value
(sample-heightmap hm 1.25 0.75)     ; wraps around modulo 1
```
<!-- /example-source -->

Read a single value at `(0.5, 0.5)`, then at `(1.25, 0.75)` — the
sampler wraps so the second call is equivalent to `(0.25, 0.75)`.

## Variations

{{example: sample-heightmap-custom-shape-fn}}

<!-- example-source: sample-heightmap-custom-shape-fn -->
```clojure
(def hm (weave-heightmap :threads 4 :resolution 128))
(register custom-tex
  (loft (displaced (circle 20 96)
                   (fn [p t]
                     (* 1.5
                        (- (sample-heightmap hm
                                             (/ (+ (angle p) Math/PI)
                                                (* 2 Math/PI))
                                             t)
                           0.5))))
        (f 40)))
```
<!-- /example-source -->

Build a fully custom displacement shape-fn that samples the heightmap
directly. The `heightmap` shape-fn is a thin wrapper around this same
pattern.

## Notes

- Sampling is bilinear: nearby cells are blended for smooth results.
- Wrapping is automatic; no clamping required even for negative or
  out-of-range inputs.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `heightmap`, `mesh-to-heightmap`, `weave-heightmap`,
  `heightmap-to-mesh`
