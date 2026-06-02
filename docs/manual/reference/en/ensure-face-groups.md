---
name: ensure-face-groups
category: faces
since: ""
status: stable
---

# ensure-face-groups

## Signature

`(ensure-face-groups mesh)`

## Description

Return `mesh` unchanged if it already carries `:face-groups`; otherwise
compute groups via `auto-face-groups` and assoc them. Pure function;
returns a (possibly new) mesh value.

`ensure-face-groups` is the idempotent prep step before any face-aware
operation. It is called internally by `find-faces`, `face-at`,
`face-nearest`, `largest-face`, and `face-shape`, so most user code does
not need to invoke it. Use it explicitly when you want to compute groups
once and reuse them across many queries, or when you want a single point
to control where the auto-grouping happens in your pipeline.

## Parameters

- `mesh` — a mesh value (no registry lookup; this is a raw mesh function).

## Example

{{example: ensure-face-groups-prep}}

<!-- example-source: ensure-face-groups-prep -->
```clojure
;; Prepare a CSG mesh once, then query it freely
(def m (ensure-face-groups (difference (box 30) (sphere 12))))
(map :id (list-faces m))
;; => (0 1 2 3 ...)   ; numeric ids assigned by auto-grouping
```
<!-- /example-source -->

After the prep step, `list-faces` and friends work on the CSG result as
they would on a primitive.

## Notes

- The function only computes groups when they are missing. Calling it a
  second time is free.
- Uses `auto-face-groups`' default threshold (`0.996`). If you need a
  different threshold, call `auto-face-groups` directly and assoc the
  result yourself.

## See also

- **Related:** `auto-face-groups`, `list-faces`, `find-faces`, `face-info`
