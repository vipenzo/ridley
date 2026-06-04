---
name: stamp-visibility
category: 2d-shapes
since: ""
status: stable
---

# stamp-visibility

## Signature

`(show-stamps)`
`(hide-stamps)`
`(stamps-visible?)`

## Description

Toggle the global visibility of stamp overlays in the viewport. Stamps
created with `stamp` are rendered as semi-transparent surfaces; the three
functions on this card control whether those surfaces are drawn.

Calling these functions does not modify the underlying stamps — they
remain registered and reappear when visibility is turned back on. Does
not modify turtle state.

The viewport toolbar also exposes a "Stamps" toggle button that drives the
same setting.

## Parameters

None — all three are nullary.

## Example

{{example: stamp-visibility-basic}}

<!-- example-source: stamp-visibility-basic -->
```clojure
(stamp (circle 20))
(f 30)
(stamp (rect 30 10))
(hide-stamps)            ; hide the overlay
(stamps-visible?)        ; => false
(show-stamps)            ; bring them back
```
<!-- /example-source -->

Create two stamps, hide them all at once for a clean rendering pass,
check the current state, then restore visibility.

## Notes

- The visibility flag is global to the current scene — `show-stamps` and
  `hide-stamps` affect every existing stamp uniformly.
- `stamps-visible?` returns the current state as a boolean.

## See also

- **Related:** `stamp`
