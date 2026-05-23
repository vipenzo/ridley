---
name: load-font!
category: text
since: ""
status: stable
---

# load-font!

## Signature

`(load-font! url-or-key)`

## Description

Load an opentype-compatible font (TTF, OTF, WOFF) asynchronously.
Returns a JavaScript promise that resolves to the font object. The font
object is cached, so repeated calls with the same key/URL return the
cached value without re-fetching.

Pass either a URL string (any TTF/OTF accessible to the browser) or a
keyword for a built-in font. The promise resolves to an opentype.js
font object, suitable as `:font` in `text-shape`, `extrude-text`,
`text-on-path`, and `text-width`.

This is the only way to use fonts other than the default Roboto.
Initial app startup calls `init-default-font!` internally to populate
the default; gated checks should use `font-loaded?` rather than this
promise.

**Built-in fonts:**

- `:roboto` — Roboto Regular (also the default).
- `:roboto-mono` — Roboto Mono (monospace).

## Parameters

- `url-or-key` — a URL string (e.g. `"/fonts/custom.ttf"`) or a
  built-in keyword (`:roboto`, `:roboto-mono`).

## Example

{{example: load-font-builtin}}

<!-- example-source: load-font-builtin -->
```clojure
;; Load a built-in font and use it in a text mesh
(-> (load-font! :roboto-mono)
    (.then (fn [mono]
             (register code
               (extrude (text-shape "fn x ()" :size 18 :font mono) (f 3))))))
```
<!-- /example-source -->

The promise resolves with the font object once parsing finishes; the
`.then` callback then has it available as `:font` for the text
functions.

## Variations

{{example: load-font-url}}

<!-- example-source: load-font-url -->
```clojure
;; Load a font from a URL (must be reachable from the browser)
(-> (load-font! "/fonts/inter-bold.ttf")
    (.then (fn [inter]
             (register title
               (extrude (text-shape "RIDLEY" :size 60 :font inter) (f 5))))))
```
<!-- /example-source -->

Custom URLs work the same as built-in keys. The font is cached on
success, so subsequent calls with the same URL skip the network.

## Notes

- The function is named with a trailing `!` because it mutates the
  font cache. The cache survives REPL re-evaluations within a session.
- Errors (network failure, invalid font file) reject the promise.
  Attach `.catch` to handle them, otherwise the failure surfaces as an
  unhandled promise rejection.
- Calls inside synchronous Ridley code must be coordinated with the
  promise: build the mesh inside the `.then` body, or use a separate
  cell that runs after the font has loaded.

## See also

- **Related:** `font-loaded?`, `text-shape`, `extrude-text`,
  `text-on-path`, `text-width`, `char-shape`
