---
name: clear-highlights
category: faces
since: ""
status: stable
---

# clear-highlights

## Signature

`(clear-highlights)`

## Description

Remove every face highlight from the viewport. Side-effecting: mutates
the viewport, not any mesh. Returns `nil`.

Both `highlight-face` (persistent) and `flash-face` (timed) add overlays
to the same highlight group; `clear-highlights` removes them all at once.
There is no per-face removal — use `clear-highlights` then re-apply the
ones you still want.

## Example

{{example: clear-highlights-reset}}

<!-- example-source: clear-highlights-reset -->
```clojure
(register b (box 20))
(highlight-face :b :top)
(highlight-face :b :bottom)
;; ...inspect, then reset:
(clear-highlights)
```
<!-- /example-source -->

Two highlights were added; one call clears both.

## Notes

- Does not affect rulers or other viewport overlays — only face
  highlights are removed. For rulers see `clear-rulers`.
- Safe to call when no highlights are present; it is a no-op.

## See also

- **Related:** `highlight-face`, `flash-face`, `clear-rulers`
