---
name: with-path
category: path
since: ""
status: stable
---

# with-path

## Signature

`(with-path path-expr & body)`

## Description

Pin a recorded path at the live turtle's current pose, resolve all
its marks as named anchors, and run the body with those anchors in
scope. On exit, the previous anchor table is restored.

`with-path` is the bridge between paths (data) and anchors (runtime
references the turtle can navigate to). Marks recorded inside a `path`
are passive labels until a `with-path` block materialises them.

Inside the body, anchors set by the pinned path can be used by:

- `goto` — move and re-orient the turtle to an anchor.
- `look-at` — orient toward an anchor without moving.
- `path-to` — build a straight path from the current position to an
  anchor.
- `move-to` — snap a registered mesh onto an anchor.

`with-path` nests: an inner block shadows the outer anchor table for
its duration, then restores the outer on exit.

The body may also be a single map literal, in which case the entries
are expanded sequentially within the `with-path` scope. This is the
assembly form: each entry is one named sub-part, optionally itself
inside another `with-path`. See the §12 examples in `Spec.md` for the
full assembly pattern.

## Parameters

- `path-expr` — an expression evaluating to a path map. The path's
  marks are resolved relative to the turtle's current pose at the
  moment `with-path` is entered.
- `body` — one or more expressions evaluated with the path's anchors
  in scope. A single map-literal body switches `with-path` into
  assembly-expansion mode.

## Example

{{example: with-path-basic}}

<!-- example-source: with-path-basic -->
```clojure
(def arm-sk
  (path
    (mark :shoulder)
    (f 30) (mark :elbow)
    (th 40) (f 25) (mark :wrist)))

(with-path arm-sk
  (goto :shoulder)
  (register upper (extrude (circle 1.5) (path-to :elbow))))
```
<!-- /example-source -->

The skeleton path is pinned at the origin; its marks become anchors
named `:shoulder`, `:elbow`, `:wrist`. `goto` moves the turtle to the
shoulder anchor; `path-to` produces the trajectory between two
anchors and feeds it into `extrude`.

## Variations

{{example: with-path-nested}}

<!-- example-source: with-path-nested -->
```clojure
(def body-sk
  (path
    (mark :neck) (f 40) (mark :hip)
    (side-trip (th 90) (f 18) (mark :shoulder))))

(def arm-sk
  (path
    (mark :shoulder)
    (f 25) (mark :elbow)
    (th 30) (f 22) (mark :wrist)))

(with-path body-sk
  (goto :neck)
  (register head (sphere 5))
  ;; nested with-path: arm-sk anchors take over, body-sk anchors
  ;; restored when we leave the inner block
  (goto :shoulder)
  (with-path arm-sk
    (goto :shoulder)
    (register upper (extrude (circle 1.5) (path-to :elbow)))
    (goto :elbow)
    (register fore  (extrude (circle 1.2) (path-to :wrist))))
  (goto :hip)
  (register pelvis (cyl 8 6)))
```
<!-- /example-source -->

Nesting lets each sub-assembly bring its own anchor table. The outer
table is restored when the inner block ends, so subsequent `goto :hip`
still resolves correctly.

## Notes

- `with-path` reads the turtle's current pose when entered; anchors
  are baked at that moment. Subsequent `goto`s inside the body do NOT
  re-pin the path — they just navigate within the already-resolved
  anchor table.
- For inspecting a path's anchors without pinning them on the turtle
  (and without restoring scope), use `anchors`. `with-path` is the
  scoped, side-effecting version; `anchors` is the pure view.
- The single-map-literal body form is the entry point for the
  assembly system: each entry runs in an extended anchor scope and
  registered meshes get qualified names. See `register` and the §12
  example in `Spec.md`.
- `with-path` is a recording-aware macro: the resolved anchors live on
  the live turtle, not on any path being recorded. Calling
  `with-path` from inside a `path` body is not the intended pattern.

## See also

- **Related:** `path`, `goto`, `look-at`, `path-to`, `move-to`,
  `anchors`, `mark`, `register`
