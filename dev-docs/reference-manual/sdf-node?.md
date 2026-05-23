---
name: sdf-node?
category: sdf-modeling
since: ""
status: stable
---

# sdf-node?

## Signature

`(sdf-node? x)`

## Description

Predicate. Return `true` if `x` is an SDF tree node (a lazy implicit
surface description), `false` otherwise — including for meshes,
shapes, numbers, and `nil`.

Use `sdf-node?` in polymorphic helpers that branch on representation:
when the same code path can produce either an SDF or a mesh, this is
how you tell them apart before deciding whether to materialize.

> Desktop only: requires the libfive backend.

## Parameters

- `x` — any value.

## Example

{{example: sdf-node-branch}}

<!-- example-source: sdf-node-branch -->
```clojure
(defn ensure-volume [x]
  (if (sdf-node? x)
    (sdf-ensure-mesh x)
    x))
```
<!-- /example-source -->

A polymorphic helper that materializes SDFs but leaves meshes
untouched. `sdf-ensure-mesh` already does this internally — this
example shows the predicate's role in user code.

## Notes

- The predicate inspects the value's `:op` key. Custom maps that
  happen to carry `:op` could be mistaken for SDFs; do not name your
  own keys this way.
- Returns `false` for `nil`, numbers, vectors, strings, and meshes.

## See also

- **Materialization:** `sdf-ensure-mesh`, `sdf->mesh`,
  `sdf-resolution!`
- **All SDF primitives:** `sdf-sphere`, `sdf-box`, `sdf-cyl`,
  `sdf-rounded-box`, `sdf-torus`
