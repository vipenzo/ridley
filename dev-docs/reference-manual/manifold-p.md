---
name: manifold?
category: mesh-operations
since: ""
status: stable
---

# manifold?

## Signature

`(manifold? mesh)`

## Description

Predicate: convert the mesh through the Manifold WASM library and report whether the result is a valid solid — closed, watertight, with consistent orientation and non-zero volume. Returns `true` when Manifold accepts the mesh and the resulting volume is greater than zero, `false` otherwise.

`manifold?` is the canonical "can I run boolean operations on this?" check. It is more authoritative than the pure-ClojureScript `mesh-diagnose` because it runs the same validation pipeline that `mesh-union` / `mesh-difference` / `mesh-intersection` / `mesh-smooth` use internally — what `manifold?` accepts is what those operations will accept.

The check has runtime cost: it converts the Ridley mesh into a `Manifold` WASM object, queries its status enum and computed volume, then disposes of the object. Use it for assertions, post-CSG validation, and conditional branches — not in a per-vertex hot loop.

## Parameters

- `mesh` — any mesh value.

## Example

{{example: manifold-p-basic}}

<!-- example-source: manifold-p-basic -->
```clojure
(register cube (box 30))
(register shelled (shell (sphere 15) 2 :style :voronoi :n 10))

(out (str "cube manifold? "    (manifold? cube)))
(out (str "shelled manifold? " (manifold? shelled)))
```
<!-- /example-source -->

A primitive box is manifold; a voronoi-perforated shell has open apertures and fails the check. The result tells you whether the mesh can flow through further boolean operations.

## Variations

{{example: manifold-p-guard}}

<!-- example-source: manifold-p-guard -->
```clojure
;; Use as a guard before chaining mesh-smooth
(register body (mesh-difference (box 40 40 20) (cyl 12 30)))

(register finished
  (if (manifold? body)
    (mesh-smooth body :sharp-angle 100 :refine 3)
    body))                                ; fall back to the unsmoothed body
```
<!-- /example-source -->

`mesh-smooth` rejects non-manifold input with an error. Gating the call with `manifold?` lets the surrounding pipeline degrade gracefully when the boolean upstream produced an open-edge result.

## Notes

- **Requires Manifold WASM.** Unlike `mesh-diagnose` (pure CLJS), `manifold?` needs the Manifold library to be initialised. In the standard Ridley runtime this happens at startup; in a headless context (e.g. tests run before init), the call may return falsy regardless of the geometry.
- **Watertight + oriented + non-zero volume.** A mesh that diagnoses as watertight by Euler / edge-incidence metrics can still fail `manifold?` if its face winding is inconsistent or its volume is degenerate. Conversely, `manifold?` is the more conservative test of the two and is what booleans actually require.
- **Tear-it-apart triage.** When `manifold?` returns `false`, run `mesh-diagnose` to find out *why* — open edges, non-manifold edges, or degenerate faces. The two functions are complementary: `manifold?` is the verdict, `mesh-diagnose` is the report.
- For a richer status object (volume, surface area, status keyword), use the internal `mesh-status` — `manifold?` is the boolean shorthand.

## See also

- **Related:** `mesh-diagnose`, `mesh?`, `merge-vertices`, `mesh-smooth`,
  `mesh-union`, `mesh-difference`, `mesh-intersection`
