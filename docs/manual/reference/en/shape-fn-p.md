---
name: shape-fn?
category: generative-operations
since: ""
status: stable
---

# shape-fn?

## Signature

`(shape-fn? x)`

## Description

Predicate. Returns `true` if `x` is a shape-fn — a function carrying the
`{:type :shape-fn}` metadata tag attached by `shape-fn` and the built-in
shape-fn constructors (`tapered`, `twisted`, `noisy`, …). Returns `false`
otherwise. Does not modify turtle state.

Useful in user code that accepts both plain shapes and shape-fns and
needs to dispatch on the kind.

## Parameters

- `x` — any value.

## Example

{{example: shape-fn-p-basic}}

<!-- example-source: shape-fn-p-basic -->
```clojure
(shape-fn? (tapered (circle 20) :to 0))   ; => true
(shape-fn? (circle 20))                    ; => false
(shape-fn? #(scale-shape %1 (- 1 %2)))     ; => false (plain fn, no tag)
```
<!-- /example-source -->

Only values that went through `shape-fn` or a built-in shape-fn
constructor carry the metadata; a plain `fn` does not.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `shape-fn`, `shape?`
