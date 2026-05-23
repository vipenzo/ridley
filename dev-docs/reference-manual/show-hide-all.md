---
name: show-all
category: registration-visibility
since: ""
status: stable
---

# show-all / hide-all

## Signature

`(show-all)`
`(hide-all)`

## Description

Toggle the visibility of every registered renderable object at once. No
arguments. Useful for clearing the viewport during exploration and
restoring everything in one keystroke. The camera framing is preserved —
neither call recenters the view.

`hide-all` only affects registered objects: anonymous meshes (those
created without `register`, but still rendered as work-in-progress) keep
their state. Use `show-only-objects` for the asymmetric "hide everything
that isn't named" case.

## Parameters

None.

## Example

{{example: show-hide-all-basic}}

<!-- example-source: show-hide-all-basic -->
```clojure
(register part-a (box 10))
(f 20)
(register part-b (sphere 6))
(hide-all)             ;; viewport empties
(show-all)             ;; both visible again
```
<!-- /example-source -->

Quick way to flip the whole scene between visible and hidden during a
modelling session.

## See also

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `show`, `hide`, `show-only-objects`, `objects`
