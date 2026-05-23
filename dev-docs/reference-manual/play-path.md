---
name: play-path
category: positioning-assembly
since: ""
status: stable
---

# play-path

## Signature

`(play-path path)`

Body-only inside `attach` / `attach!`.

## Description

Replay the commands of a recorded path as if they had been typed verbatim inside the surrounding `attach` / `attach!` body. The turtle advances, rotates, and runs `mark` / `move-to` according to the path's recording; the attached mesh or SDF is transformed along with it.

`play-path` exists to solve a binding-capture issue: when a helper function returns a path, its body references the global `f` / `th` / `tv` bindings, *not* the rebound attach-aware versions. Calling the helper inline inside an attach would advance a recording turtle but never the live mesh. `play-path` takes the resulting path value and replays each command through the active attach machinery so the mesh is transformed correctly.

```clojure
(defn branch-path [l]
  (path (tv 90) (f (/ l 8)) (tv -90) (f l)))

;; This does NOT work — branch-path captures global f / tv:
;; (attach! :mesh (branch-path 30))
;; This DOES work — play-path replays through the attach context:
(attach! :mesh (play-path (branch-path 30)))
```

`play-path` may be combined with further turtle commands in the same body — recordings before and after are concatenated transparently.

## Parameters

- `path` — a recorded path map. Any path constructed via `path`, `quick-path`, `poly-path`, `bezier-as`, or returned by a helper function is valid.

## Example

{{example: play-path-helper}}

<!-- example-source: play-path-helper -->
```clojure
;; A parametric path helper that we want to apply to an attach body
(defn lift-and-rotate [h angle]
  (path (f h) (tv 90) (th angle) (tv -90)))

(register marker (cone 4 12))

;; Replay the helper's path through the attach machinery
(attach! :marker (play-path (lift-and-rotate 20 45)))
```
<!-- /example-source -->

The helper returns a path value; inside `attach!` we cannot simply splice it in (the helper's `f`/`tv`/`th` are the recording versions, not the attach versions). `play-path` runs the recording through the attach context so the cone is translated and rotated correctly.

## Variations

{{example: play-path-combined}}

<!-- example-source: play-path-combined -->
```clojure
;; Combine play-path with extra turtle commands on either side
(def swing (path (tv 30) (f 25)))

(register arm (extrude (circle 1.2) (f 10)))
(attach! :arm
  (play-path swing)
  (th 90)
  (f 5))
```
<!-- /example-source -->

The recorded `swing` segment is replayed first; the subsequent `th 90` and `f 5` continue from the pose `play-path` left the turtle in. There is no semantic difference between commands before/inside/after a `play-path` — they share the same turtle.

## Notes

- **Body-only.** `play-path` is bound only inside `attach` / `attach!` bodies (and inside `path` itself, where it splices the recording — see `follow` for the path-recording-time equivalent).
- **Why not just splice?** Inside an attach body, `f` / `th` / `tv` are rebound to attach-aware variants that transform the mesh. A path *value* contains pre-resolved commands referencing the recording-time bindings; splicing it inline would run those bindings instead of the active ones. `play-path` is the explicit replay primitive that always uses the active context.
- **All recordable commands work.** Anything you can record in a `path` — moves, rotations, `mark`, `move-to`, `cp-*`, `stretch-*` — replays correctly through `play-path`. The transform on the mesh is the composition of all the per-command transforms in order.
- **Compose with non-recorded commands freely.** The replay leaves the turtle at the path's end pose; any commands after `play-path` start from there.

## See also

- **Related:** `attach`, `attach!`, `path`, `follow`, `with-path`,
  `move-to`, `stretch-f`
