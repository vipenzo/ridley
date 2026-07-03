---
name: extrude+
category: generative-operations
since: ""
status: stable
---

# extrude+

## Signature

`(extrude+ shape & path-commands)`

## Description

Chainable variant of `extrude`. Instead of returning a bare mesh, it
returns a map `{:mesh <mesh> :end-face {:shape <shape> :pose {…}}}`. The
`:end-face` records the shape and pose of the final ring, ready to be
fed as input to a follow-up segment. Useful for building multi-segment
geometry that bends, branches, or composes several profiles. Does not
modify turtle state.

For sequencing multiple `extrude+` / `revolve+` calls without manual
unpacking, see the `transform->` macro.

## Parameters

- `shape` — a 2D profile (the starting cross-section).
- `path-commands` — turtle movement forms or a recorded path.

## Example

{{example: extrude-plus-basic}}

<!-- example-source: extrude-plus-basic -->
```clojure
(def seg1 (extrude+ (circle 5) (f 20)))
(:mesh seg1)        ;; the mesh of segment 1
(:end-face seg1)    ;; {:shape <circle> :pose {:position … :heading … :up …}}
```
<!-- /example-source -->

Capture both the mesh and the end-face pose so a follow-up segment can
continue from the same orientation.

## Variations

{{example: extrude-plus-chained}}

<!-- example-source: extrude-plus-chained -->
```clojure
(def seg1 (extrude+ (circle 5) (f 20)))
(def seg2 (turtle (:pose (:end-face seg1))
            (extrude+ (:shape (:end-face seg1)) (f 25))))
(register chain (mesh-union (:mesh seg1) (:mesh seg2)))
```
<!-- /example-source -->

Chain two segments manually: pose the turtle at the previous end-face,
extrude again with the same profile, then union. `transform->` automates
this same pattern when many segments are involved.

## Notes

- The map shape is `{:mesh :end-face}` with `:end-face` itself a map
  `{:shape :pose}` whose pose is `{:position :heading :up}`.
- Standard `extrude` is unchanged: it still returns just a mesh.
- Pair with `revolve+` for bend / corner segments, and combine with
  `transform->` for compact chains.

## See also

- **Related:** `revolve+`, `loft+`, `transform->`, `extrude`, `mesh-union`
