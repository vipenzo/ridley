---
name: flash-face
category: faces
since: ""
status: stable
---

# flash-face

## Signature

`(flash-face mesh face-id)`
`(flash-face mesh face-id duration-ms)`
`(flash-face mesh face-id duration-ms color)`

## Description

Highlight one face of `mesh` for a fixed duration, then remove the
overlay automatically. Side-effecting: mutates the viewport, not the
mesh.

`flash-face` is the "transient" counterpart of `highlight-face`. Use it
when you want to confirm a pick without leaving a marker behind — for
example inside a function called repeatedly during exploration, or as a
visual cue in interactive tools.

The flash only removes the highlight it added; other persistent
highlights remain. To clear everything at once, use `clear-highlights`.

## Parameters

- `mesh` — a mesh value with `:face-groups`.
- `face-id` — a keyword or integer face identifier.
- `duration-ms` (optional) — milliseconds the highlight stays visible
  (default `2000`).
- `color` (optional) — hex integer like `0xff0000` (default `0xff6600`).

## Example

{{example: flash-face-default}}

<!-- example-source: flash-face-default -->
```clojure
(register b (box 20))
(flash-face :b :top)   ; orange for 2 seconds, then gone
```
<!-- /example-source -->

A two-second flash on the top face — useful as a non-intrusive "did you
pick the right one" confirmation.

## Variations

{{example: flash-face-custom}}

<!-- example-source: flash-face-custom -->
```clojure
(register b (box 20))
(flash-face :b :front 3000 0x00ff00)   ; green for 3 seconds
```
<!-- /example-source -->

Override duration and colour to differentiate flashes when multiple are
triggered in quick succession.

## Notes

- The removal is scheduled with `setTimeout`. Re-evaluating the code
  during the flash window leaves the timer running; it will still remove
  the highlight when it fires.
- For a persistent marker (e.g. while inspecting from multiple camera
  angles), use `highlight-face`.

## See also

- **Related:** `highlight-face`, `clear-highlights`, `face-info`,
  `find-faces`
