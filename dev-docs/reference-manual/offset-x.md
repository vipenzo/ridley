---
name: offset-x
category: path
since: ""
status: stable
---

# offset-x

## Signature

`(offset-x path dx)`

## Description

Shift every waypoint of a path by `dx` in X. The typical use is to
move a `revolve` profile inward or outward relative to the revolve
axis: the path's local X coordinate represents radial distance, so
adding to it grows the swept body radially.

Returns a new path. The original is unchanged.

## Parameters

- `path` — a recorded path map.
- `dx` — signed X offset, in turtle units.

## Example

{{example: offset-x-basic}}

<!-- example-source: offset-x-basic -->
```clojure
(def profile (path (f 5) (th 90) (f 20)))

;; Inner skin: profile moved closer to the axis
(def inner (offset-x profile -2.5))

(register outer (revolve (path-to-shape profile)))
(register inner-cavity (revolve (path-to-shape inner)))
```
<!-- /example-source -->

The same source profile is offset radially to produce a thin-walled
revolve shell.

## Notes

- The implicit origin point of a path is dropped during the shift: the
  output starts from the first real waypoint. This avoids a degenerate
  zero-length segment at the start when the offset is non-zero.
- For Y-axis clipping use `subpath-y`. For a uniform scale of the
  entire path use `fit` or build the path with scaled distances at
  recording time.

## See also

- **Guide:** placeholder → cap. 5 (Paths and anchors)
- **Related:** `subpath-y`, `path-to-shape`, `revolve`, `fit`
