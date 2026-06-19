---
name: path
category: path
since: ""
status: stable
---

# path

## Signature

`(path & body)`

## Description

Record turtle movement commands as a reusable data value. The body is
executed in a recording context where turtle-movement names (`f`, `th`,
`tv`, `tr`, `u`, `d`, `rt`, `lt`, `arc-h`, `arc-v`, `bezier-to`, …) are
shadowed: instead of moving the live turtle, they append commands to an
internal recorder. Returns a path map.

A path is not directly renderable. It is an input to `extrude`, `loft`,
`revolve`, `follow-path`, `with-path`, and the various path utilities.

The body is ordinary Clojure code — `let`, `dotimes`, `for`, `if`,
conditional helpers, all work. Only the turtle-movement names are
rebound; everything else runs as normal.

Inside `path`, the following additional commands are available beyond
turtle movement: `mark`, `follow`, `side-trip`, `inset`, `move-to`,
`play-path`, `stretch-f` / `stretch-rt` / `stretch-u`, and the
creation-pose shifts `cp-f` / `cp-rt` / `cp-u` / `cp-th` / `cp-tv` /
`cp-tr`. Plain `scale` is rejected inside a path body — use the
`stretch-*` family for local-axis scaling. See each command's reference
card for details.

## Parameters

- `body` — one or more turtle-movement forms and arbitrary Clojure code.
  Returns are ignored unless the body returns a path itself and emitted
  no commands, in which case `path` is a pass-through.

## Example

{{example: path-basic}}

<!-- example-source: path-basic -->
```clojure
(def my-path (path (f 30) (th 45) (f 20)))
(register tube (extrude (circle 5) my-path))
```
<!-- /example-source -->

A path is recorded once and reused as the trajectory for an extrusion.

## Variations

{{example: path-with-code}}

<!-- example-source: path-with-code -->
```clojure
(def zigzag
  (path
    (dotimes [_ 4]
      (f 15) (th 60) (f 15) (th -60))))
(register sweep (extrude (rect 3 1) zigzag))
```
<!-- /example-source -->

Arbitrary Clojure code (`dotimes`, `let`, `for`, …) runs as expected;
only turtle-movement names are intercepted.

{{example: path-passthrough}}

<!-- example-source: path-passthrough -->
```clojure
(def base (path (f 20) (th 90) (f 20)))
;; (path base) returns base unchanged when the body emits no commands
(register reuse (extrude (circle 3) (path base)))
```
<!-- /example-source -->

When the body emits no commands and returns a path, `path` is a
pass-through. Useful in helper functions that may receive either inline
movements or an already-recorded path.

## Notes

- Inside `path`, plain `scale` raises an error. Use `stretch-f`,
  `stretch-rt`, `stretch-u` for local-axis scaling along heading, right,
  or up — they are recorded as commands and applied on replay.
- `path` reads the current global turtle's `resolution` and `joint-mode`
  at recording time and stores them in the path map. Subsequent
  recordings inherit whatever is active at the moment of the `path`
  call.
- Paths nest: `(path A (follow B) C)` splices the commands of path `B`
  into the new recording. See `follow` for details.
- The `path` recorder is a dynamic atom restored on exit, so nested
  `path` blocks compose without leakage even when an exception escapes
  the body.

## See also

- **Related:** `path-2d`, `extrude`, `loft`, `revolve`, `follow-path`, `with-path`,
  `quick-path`, `poly-path`, `mark`, `follow`, `side-trip`, `path?`
