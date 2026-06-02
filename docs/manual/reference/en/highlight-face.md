---
name: highlight-face
category: faces
since: ""
status: stable
---

# highlight-face

## Signature

`(highlight-face mesh face-id)`
`(highlight-face mesh face-id color)`

## Description

Add a persistent coloured overlay to one face of `mesh` in the viewport.
Returns `true` if the face was found and highlighted, `nil` otherwise.
Side-effecting: mutates the viewport, not the mesh.

`highlight-face` is the debugging counterpart of `find-faces` /
`face-at`: paint the face you picked to confirm it visually before
committing to a transform. Highlights stack — call repeatedly to mark
several faces. They persist across REPL evaluations until you call
`clear-highlights`.

For a temporary highlight that fades on its own, use `flash-face`.

## Parameters

- `mesh` — a mesh value with `:face-groups` (registered names are
  resolved at the call site).
- `face-id` — a keyword on primitives, or an integer on auto-grouped
  meshes.
- `color` (optional) — hex integer like `0xff0000`. Default `0xff6600`
  (orange).

## Example

{{example: highlight-face-confirm-pick}}

<!-- example-source: highlight-face-confirm-pick -->
```clojure
(register b (box 20))
;; Mark the face you intend to extrude before running the operation
(highlight-face :b :top)
;; → orange overlay on the top face
```
<!-- /example-source -->

Highlights survive code re-evaluation, so you can leave a face marked
while editing surrounding code and inspecting from different angles.

## Variations

{{example: highlight-face-custom-color}}

<!-- example-source: highlight-face-custom-color -->
```clojure
(register b (box 20))
(highlight-face :b :top    0xff0000)   ; red
(highlight-face :b :bottom 0x0066ff)   ; blue
```
<!-- /example-source -->

Multiple highlights coexist; pick distinct colours to mark "source" vs
"target" faces in a multi-step operation.

## Notes

- The overlay is offset slightly along the face normal to avoid
  z-fighting with the underlying mesh.
- Highlights are visual only — they do not affect mesh geometry, exports,
  or any other operation.
- To clear, call `clear-highlights`. There is no per-face un-highlight.

## See also

- **Related:** `flash-face`, `clear-highlights`, `face-info`,
  `find-faces`, `face-at`
