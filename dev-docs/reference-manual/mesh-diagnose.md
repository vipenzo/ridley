---
name: mesh-diagnose
category: mesh-operations
since: ""
status: stable
---

# mesh-diagnose

## Signature

`(mesh-diagnose mesh)`

## Description

Compute topological invariants of a mesh without mutating it. The
primary triage tool when Manifold rejects a mesh as
`status 2 (NotManifold)` and you need to know why.

Returns a map with the following keys:

| Key                            | Meaning                                                                              |
|--------------------------------|--------------------------------------------------------------------------------------|
| `:n-verts`, `:n-faces`, `:n-edges` | Raw counts.                                                                       |
| `:edge-incidence-distribution` | `{n-incident-faces → edge-count}`. A healthy manifold has only `{2 N}`.              |
| `:open-edges`                  | Edges shared by exactly one face (boundary holes).                                   |
| `:non-manifold-edges`          | Edges shared by 3+ faces (T-junctions, duplicate walls).                             |
| `:degenerate-faces`            | Triangles with area below `1e-10`.                                                   |
| `:euler-characteristic`        | `V - E + F`. Closed manifold: 2 (sphere), 0 (torus). Each handle subtracts 2.         |
| `:is-watertight?`              | `true` iff `:open-edges` and `:non-manifold-edges` are both zero.                    |

`mesh-diagnose` is pure ClojureScript — no Manifold WASM, no Rust
server. Cheap enough to call inline during development to verify
watertightness before export or compare construction strategies.

## Parameters

- `mesh` — any mesh value (manifold or not).

## Example

{{example: mesh-diagnose-basic}}

<!-- example-source: mesh-diagnose-basic -->
```clojure
(register body (mesh-difference (box 30) (cyl 6 40)))

(let [d (mesh-diagnose body)]
  (out (str "watertight? " (:is-watertight? d)))
  (out (str "open edges: " (:open-edges d)))
  (out (str "Euler χ:    " (:euler-characteristic d))))
```
<!-- /example-source -->

A CSG result is inspected. A well-formed result reports `:is-watertight?
true`, zero open edges, and an Euler characteristic of 2 (genus 0).

## Variations

{{example: mesh-diagnose-broken}}

<!-- example-source: mesh-diagnose-broken -->
```clojure
;; Diagnose a perforated shell (shell with voronoi style is not manifold)
(def shelled
  (-> (sphere 12) (shell 2 :style :voronoi :n 12)))

(let [d (mesh-diagnose shelled)]
  (out (str "watertight? " (:is-watertight? d)))
  (out (str "open edges: " (:open-edges d)))
  (out (str "edge dist:  " (:edge-incidence-distribution d))))
```
<!-- /example-source -->

A perforated shell renders fine in the viewport and prints fine, but
`mesh-diagnose` confirms it has open edges and is therefore rejected
by `mesh-smooth` and the boolean operations.

## Notes

- `mesh-diagnose` does not attempt repair. The companion utilities are:
  `merge-vertices` for coincident vertices, `mesh-simplify` for
  decimation, `mesh-laplacian` for selective smoothing on
  non-manifold meshes.
- Euler characteristic confirms topology: an isolated sphere has
  χ = 2, a torus has χ = 0, every additional handle subtracts 2.
  Useful to detect that a "single" mesh is actually multiple
  disconnected components.

## See also

- **Guide:** placeholder → cap. 7 (Mesh operations)
- **Related:** `find-sharp-edges`, `merge-vertices`, `mesh-simplify`,
  `mesh-laplacian`, `mesh-smooth`
