---
name: font-loaded?
category: text
since: ""
status: stable
---

# font-loaded?

## Signature

`(font-loaded?)`

## Description

Predicate. Return `true` if the default Roboto font has finished loading
and is ready for `text-shape` / `extrude-text` / `text-on-path` calls
that do not pass an explicit `:font` option. Return `false` if loading
has not yet completed.

The default font loads asynchronously at app startup. Most user code
runs after it is ready, but startup hooks or eagerly evaluated cells
may fire before it. Use `font-loaded?` to gate calls that would
otherwise return `nil`.

For non-default fonts, the result of `load-font!` is the source of
truth — `font-loaded?` only tracks the bundled default.

## Example

{{example: font-loaded-gate}}

<!-- example-source: font-loaded-gate -->
```clojure
;; Build text only when the default font is ready
(when (font-loaded?)
  (register title (extrude (text-shape "RIDLEY" :size 40) (f 5))))
```
<!-- /example-source -->

A safe pattern for code that may run before the default font has
finished loading.

## Notes

- The predicate only reports the **default** font's state. If you pass
  a `:font` from `load-font!`, you do not need to check `font-loaded?`
  for that font.
- Returns a plain boolean, not a promise. To wait for loading, use the
  promise returned by `load-font!` directly.

## See also

- **Related:** `load-font!`, `text-shape`, `extrude-text`,
  `text-on-path`, `text-width`
