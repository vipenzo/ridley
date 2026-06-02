---
name: clear-rulers
category: faces
since: ""
status: stable
---

# clear-rulers

## Signature

`(clear-rulers)`

## Description

Remove every ruler overlay from the viewport. Side-effecting; returns
`nil`.

Rulers also clear automatically on code re-evaluation (Cmd+Enter), so
calling `clear-rulers` is only needed when you want to remove rulers
without re-running the buffer — for example to take a clean screenshot
or to reset the workspace mid-session.

## Example

{{example: clear-rulers-reset}}

<!-- example-source: clear-rulers-reset -->
```clojure
(register a (box 20))
(register b (translate (box 20) 60 0 0))
(ruler :a :b)
(ruler :a :top [0 50 0])
;; ...inspect, then reset:
(clear-rulers)
```
<!-- /example-source -->

Two rulers were placed; one call removes both.

## Notes

- Only affects rulers — face highlights survive. For highlights use
  `clear-highlights`.
- Safe to call when no rulers are present.

## See also

- **Related:** `ruler`, `distance`, `clear-highlights`
