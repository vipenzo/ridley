---
name: reverse-path
category: path
since: ""
status: stable
---

# reverse-path

## Signature

`(reverse-path path)`

## Description

Return a new path that traces `path`'s waypoints in reverse order (last
point first). The result is rebuilt from points (`set-heading` + forward
commands) and shifted to start at the origin, so following it retraces
the original backward from the current turtle pose:

```clojure
(follow-path (reverse-path p))   ; walk p's shape, in reverse
```

It is most useful together with `mirror-path`: to complete a symmetric
curve from one half, mirror the half and reverse it so the two pieces
join head-to-tail (see `mirror-path`).

Works in 3D — the full turtle frame (position, heading, up) is carried.
Commonly used on 2D profiles fed to `stroke-shape` / `extrude`.

## Parameters

- `path` — any path (a `path` recording, `poly-path`, etc.).

## Example

<!-- example-source: reverse-path-basic -->
```clojure
(def h (path (bezier-to [36 9 0] [18 0 0] [29 2 0])))
;; full symmetric corner: the half, then its mirror reversed to join on
(register corner
  (extrude (stroke-shape
            (path (follow-path h)
                  (follow-path (reverse-path (mirror-path h)))) 3)
           (f 10)))
```
<!-- /example-source -->

See `examples/spigolo-quattro-modi.clj` for the full worked example.

## Notes

- Operates on the path's waypoints, so a curve made of many small
  segments (e.g. a `bezier-to`) comes back as the same dense polyline,
  reversed.
- The result starts at the origin (relative path): it continues from
  wherever the turtle is when you `follow` it.

## See also

- **Related:** `mirror-path`, `follow`, `path`, `poly-path`
