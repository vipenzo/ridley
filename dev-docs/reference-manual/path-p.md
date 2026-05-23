---
name: path?
category: path
since: ""
status: stable
---

# path?

## Signature

`(path? x)`

## Description

Return `true` if `x` is a recorded path — that is, a map whose
`:type` is `:path` — and `false` otherwise. Useful for helper
functions that accept either inline turtle movements or an
already-recorded path.

## Parameters

- `x` — any value.

## Example

{{example: path-p-basic}}

<!-- example-source: path-p-basic -->
```clojure
(def p (path (f 10) (th 90) (f 5)))

(out (str "(path? p)            = " (path? p)))
(out (str "(path? (circle 5))    = " (path? (circle 5))))
(out (str "(path? :keyword)      = " (path? :keyword)))
```
<!-- /example-source -->

A simple type test. Helper functions that want to accept "either a
path or a list of movements" can dispatch on it.

## Notes

- `path?` only checks the value's shape (`:type :path` in the map). It
  does not validate the command list or the recorded resolution
  /joint-mode.

## See also

- **Guide:** placeholder → cap. 5 (Paths and anchors)
- **Related:** `path`, `shape?`, `mesh?`
