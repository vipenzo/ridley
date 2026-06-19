---
name: path-2d
category: path
since: ""
status: stable
---

# path-2d

## Signature

`(path-2d & body)`
`(path-2d :closed & body)`

## Description

Record a **planar** path — a 2D profile, as opposed to `path`'s 3D rail. `path-2d`
traces in the same plane a shape stamps into (the turtle's `(right, up)` plane), so
a profile fed to `path-to-shape` is never "rotated" relative to how you drew it. The
defining invariant: `(follow-path P)` and `(stamp (path-to-shape P))` land on the
**same world points**.

Like `path` it is pose-less (the consumer supplies absolute placement) and the
result is tagged `:species :2d`. Planar consumers (`path-to-shape`, `stroke-shape`,
`bounds-2d`) normalize it through `ensure-path-2d`; **rail** consumers
(`extrude`-along-path, `loft`) keep a 3D path. So `path-2d` is fully non-breaking —
an ordinary `path` is `:3d` and behaves exactly as before.

## Parameters

- `:closed` — optional **leading** flag: closes the path, so the closing segment
  (last → first) becomes a real, editable seam (used by `path-to-shape` to drop the
  doubled seam vertex).
- `body` — turtle-movement forms, plus `mark`, `side-trip`, and a leading `move-to`.
  Because a plane has only one in-plane turn and one strafe, the commands collapse
  inside `path-2d`:

| You write | Means | Sign |
|-----------|-------|------|
| `th` = `tv` = `tr` | the single in-plane turn | `+` = left |
| `rt` = `u`         | strafe | `+` one way |
| `lt` = `down`      | strafe | `+` the other way |
| `arc-h` = `arc-v`  | the single in-plane arc | sign = direction |
| `f` / `b`          | forward / back | unchanged |

## Example

<!-- example-source: path-2d-basic -->
```clojure
(def L (path-2d (f 20) (th 90) (f 8) (th 90) (f 12) (th 90) (f 8)))
(register part (extrude (path-to-shape L) (f 5)))
```
<!-- /example-source -->

## Notes

- `edit-path-2d` is the interactive pen tool that produces a `path-2d` (and bakes one
  on confirm).
- The choice between `path` and `path-2d` is **which plane the trace lands in** plus
  ergonomics, not a different shape: a `:3d` `path` and the matching `path-2d` give
  the same `path-to-shape` profile.

## See also

- **Related:** `path`, `edit-path-2d`, `path-to-shape`, `stroke-shape`, `follow-path`
