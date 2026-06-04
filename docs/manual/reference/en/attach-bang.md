---
name: attach!
category: positioning-assembly
since: ""
status: stable
---

# attach!

## Signature

`(attach! :name & body)`

## Description

In-place version of `attach`. Looks up the registered mesh (or SDF,
or panel) by keyword, applies the body as turtle commands, and
writes the transformed value back to the registry under the same
name.

`attach!` is exactly equivalent to:

```clojure
(register name (attach name body…))
```

with two differences:

- The first argument must be a keyword. Bare mesh values are
  rejected — use `attach` for those.
- The result is committed to the registry, so subsequent code that
  refers to the same name sees the new pose.

The body accepts the same commands as `attach`. See
[attach](attach.md) for the command table, including movement,
rotation, curves, attach-specific (`move-to`, `play-path`,
`stretch-*`), and the `cp-*` family.

## Parameters

- `:name` — keyword. The name must be registered; otherwise
  `attach!` throws.
- `body` — one or more turtle / attach commands, exactly as in
  `attach`.

## Example

{{example: attach-bang-basic}}

<!-- example-source: attach-bang-basic -->
```clojure
(register cube (box 10))

;; Move the registered mesh in place — :cube now points to the moved geometry
(attach! :cube (f 20) (th 45))
```
<!-- /example-source -->

The mesh stored under `:cube` is replaced by the transformed result;
subsequent references to `:cube` see the moved version.

## Variations

{{example: attach-bang-relative}}

<!-- example-source: attach-bang-relative -->
```clojure
(register base (box 30))
(attach! :base (th -90) (f 40))         ; reposition base

(register sphere (sphere 8))
;; Snap the sphere onto base's current pose and stack it on top
(attach! :sphere (move-to :base) (tv 90) (f 25))
```
<!-- /example-source -->

`attach!` chains naturally with `move-to`: position one mesh,
then position the next relative to it. Both meshes end up in the
registry with their final poses.

## Notes

- `attach!` throws if the name is not registered. This is
  intentional — silently creating a registry entry would mask typos.
- Use `attach` (no `!`) when you want a transformed copy under a
  different name. Use `attach!` when you want to mutate the
  existing entry.
- For SDFs registered as a value (not yet materialized), `attach!`
  works the same: the body operates on the SDF tree.

## See also

- **Related:** `attach`, `move-to`, `register`, `play-path`,
  `stretch-f`, `stretch-rt`, `stretch-u`
