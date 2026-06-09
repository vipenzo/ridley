---
name: mirror-path
category: path
since: ""
status: stable
---

# mirror-path

## Signature

`(mirror-path path)`
`(mirror-path path normal)`

## Description

Return `path` reflected across the **plane through its end point**. The
intended use is completing a curve that should be symmetric about that
plane: author one half, mirror it, and reverse the mirror so the two
pieces join head-to-tail into the whole.

```clojure
(path (follow-path half)
      (follow-path (reverse-path (mirror-path half normal))))
```

With one argument the mirror plane's **normal is the heading at the end**
of the path — so the plane itself is the turtle's right/up plane there,
the natural smooth-completion plane. A path ends facing the **true tangent**
of its last segment (a `bezier-to` records the analytic end tangent, just
like the turtle-level `bezier-to`; straight segments are exact too), so the
default is accurate and you normally don't need to name the axis. Pass the
normal `[nx ny]` / `[nx ny nz]` explicitly only to mirror across a specific
plane regardless of the end heading.

For a corner whose chord runs along the 45° diagonal, that explicit normal
would be the diagonal `[1 1]`; the symmetry plane is the turtle's right/up
plane at the midpoint, which the default already picks.

Works in 3D: the full turtle frame (position, heading, up) is carried, so
non-planar half-curves mirror correctly too. 2D profiles are the planar
special case.

## Parameters

- `path` — the half curve to reflect.
- `normal` — optional `[nx ny]` / `[nx ny nz]` normal of the mirror plane
  through the end point. Omit to use the end heading as the normal.

## Example

<!-- example-source: mirror-path-basic -->
```clojure
;; A symmetric corner from one authored half (O → midpoint M):
(def half (path (bezier-to [36.06 8.94 0] [18.09 0 0] [29.34 2.21 0])))
(register corner
  (extrude (stroke-shape
            (path (follow-path half)
                  (follow-path (reverse-path (mirror-path half)))) 3)
           (f 10)))
```
<!-- /example-source -->

The half is reflected across the turtle's right/up plane at its end (no
axis named), then reversed; the full curve is symmetric by construction.
See `examples/spigolo-quattro-modi.clj` for four ways to draw the same
corner.

## Notes

- The reflected path is rebuilt with `set-heading` commands in absolute
  (world) orientation, so `follow-path` continues it correctly from the
  current position regardless of the turtle's incoming heading.
- The default (normal = end heading) is accurate: the path ends facing the
  true tangent (beziers record the analytic end tangent; straight segments
  are exact). Pass an explicit `normal` only to mirror across a plane that
  differs from the turtle's right/up plane at the end.

## See also

- **Related:** `reverse-path`, `follow-path`, `path`, `stroke-shape`
