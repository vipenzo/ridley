---
name: panel?
category: registration-visibility
since: ""
status: stable
---

# panel?

## Signature

`(panel? x)`

## Description

Predicate. Returns `true` when `x` is a panel value (a map carrying
`:type :panel`), `false` otherwise. Useful in dispatch code that
accepts both meshes and panels. Does not modify turtle state.

## Parameters

- `x` — any value.

## Example

{{example: panel-p-basic}}

<!-- example-source: panel-p-basic -->
```clojure
(panel? (panel 40 20))    ;; => true
(panel? (box 10))         ;; => false
(panel? :label)           ;; => false (just a keyword)
```
<!-- /example-source -->

The predicate only matches actual panel values, not their registry
names.

## See also

- **Related:** `panel`, `out`, `append`, `clear`, `shape?`,
  `shape-fn?`, `path?`
