---
name: shape-offset-all
category: 2d-shapes
since: ""
status: stable
---

# shape-offset-all

## Signature

`(shape-offset-all shape delta)`
`(shape-offset-all shape delta & {:keys [join-type]})`

## Description

Like `shape-offset`, but returns a **vector** of shapes, one per region.
A negative delta can split a shape at a neck or erase an island
entirely; `shape-offset` keeps only the largest survivor, this keeps them
all — `[]` when nothing survives. Slivers under a thousandth of the input
area (round-join artefacts along a coarse contour) are dropped.

## Parameters

- `delta` — positive expands, negative contracts.
- `:join-type` — `:round` (default), `:square`, `:miter`.

## Example

```clojure
(count (shape-offset-all dumbbell -2))   ;; => 2 once the neck is gone
```

## See also

- **Related:** `shape-offset`, `joint-zone`
