---
name: objects
category: registration-visibility
since: ""
status: stable
---

# objects / registered / scene

## Signature

`(objects)`
`(registered)`
`(scene)`

## Description

Inspect the scene registry from code. Three flavours, each nullary:

- **`(objects)`** — names of all currently **visible** registered
  objects.
- **`(registered)`** — names of all registered objects, including the
  hidden ones.
- **`(scene)`** — info for every mesh in the scene, registered plus the
  anonymous ones (work-in-progress meshes from un-`register`ed
  expressions). Returns a sequence of info maps rather than just names.

Use them at the REPL to introspect what is currently visible, what's
defined, and what's been drawn anonymously.

## Parameters

None.

## Example

{{example: registry-query-basic}}

<!-- example-source: registry-query-basic -->
```clojure
(register part-a (box 10))
(register part-b (sphere 6) :hidden)
(box 5)                         ;; anonymous
(objects)                       ;; => (:part-a)            — only visible registered
(registered)                    ;; => (:part-a :part-b)    — all registered
(scene)                         ;; => seq with :part-a + anonymous mesh info
```
<!-- /example-source -->

Three calls, three perspectives: visible-and-named, all-named,
everything-rendered.

## Notes

- `objects` returns only what shows in the viewport. Hidden registered
  entries are excluded.
- `registered` is the inventory: it lists everything that responds to
  `show` / `hide` by name.
- `scene` is the broadest view — useful when chasing "where did this
  extra mesh come from?".

## See also

- **Related:** `register`, `show`, `hide`, `info`, `bounds`, `mesh`
