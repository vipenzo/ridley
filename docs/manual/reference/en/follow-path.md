---
name: follow-path
category: path
since: ""
status: stable
---

# follow-path

## Signature

`(follow-path path)`

## Description

Execute a recorded path on the live turtle. Each command in the path
is applied to the global turtle state: position, heading, and up are
advanced segment by segment; marks created during recording are NOT
re-emitted (the live turtle has no recorder). If the pen is down,
lines are drawn just as if the commands had been issued directly.

`follow-path` is the turtle-level counterpart of `follow`. On the live
turtle it walks the path; **inside a `(path …)` recording it splices the
path's commands into the recording** (the same as `follow`), so the two
names are interchangeable there. This is what lets you stitch paths
together, e.g. `(path (follow-path a) (follow-path b))`.

## Parameters

- `path` — a recorded path map (`{:type :path …}`). Other values are
  rejected.

## Example

{{example: follow-path-basic}}

<!-- example-source: follow-path-basic -->
```clojure
(def detour (path (f 30) (th 90) (f 20)))

;; Replay the path on the turtle, then draw from there.
(follow-path detour)
(register tip (sphere 3))
```
<!-- /example-source -->

The turtle walks `detour` and lands at its endpoint; a sphere is then
registered at that pose.

## Variations

{{example: follow-path-as-skeleton}}

<!-- example-source: follow-path-as-skeleton -->
```clojure
(def skel
  (path
    (f 20) (mark :a)
    (th 60) (f 20) (mark :b)
    (th 60) (f 20) (mark :c)))

;; Walk the skeleton so the turtle is left at the last endpoint.
(reset)
(follow-path skel)
(register pin (cyl 1 5))
```
<!-- /example-source -->

When a path is used both as an extrusion trajectory and as a way to
position something at its endpoint, `follow-path` is the explicit
positioning primitive.

## Notes

- Marks recorded in the path are silently dropped on the live turtle
  — marks only exist inside a `with-path` scope. To materialise marks
  as named anchors, use `with-path` instead.
- The current `joint-mode` of the live turtle is unaffected; turning
  commands modify only the turtle's pose.
- For executing a path inside an `attach` body without affecting the
  outer turtle, use `play-path` (a recording command meaningful only
  in attach context).

## See also

- **Related:** `path`, `follow`, `with-path`, `play-path`, `turtle`
