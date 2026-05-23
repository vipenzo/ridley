---
name: show-only-objects
category: registration-visibility
since: ""
status: stable
---

# show-only-objects

## Signature

`(show-only-objects)`

## Description

Hide every anonymous mesh in the scene while keeping registered objects
visible. The complement is "show only the named things and drop the
work-in-progress noise". The camera framing is preserved. Does not
affect the visibility flag of registered objects (they retain whatever
state they had).

Anonymous meshes are those produced by expressions outside `register`
(e.g. one-off `(box 10)` calls at the REPL). They are normally rendered
for live preview; `show-only-objects` clears them in one call.

## Parameters

None.

## Example

{{example: show-only-objects-basic}}

<!-- example-source: show-only-objects-basic -->
```clojure
(box 5)                              ;; anonymous preview mesh
(box 10)                             ;; another anonymous
(register frame (extrude (rect 20 20) (f 30)))
(show-only-objects)                  ;; only :frame remains visible
```
<!-- /example-source -->

Clear scratch geometry without losing the registered frame.

## Notes

- To restore the anonymous meshes, the simplest path is to re-evaluate
  their expressions — the registry doesn't track unnamed meshes by
  identity once they're hidden.

## See also

- **Guide:** placeholder → cap. 1 (Primi passi)
- **Related:** `show-all`, `hide-all`, `show`, `hide`, `objects`,
  `registered`, `scene`
