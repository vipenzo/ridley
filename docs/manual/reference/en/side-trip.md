---
name: side-trip
category: path
since: ""
status: stable
---

# side-trip

## Signature

`(side-trip & body)` — available inside `path` / `extrude` / `loft` /
`attach` bodies.

## Description

Record a sub-path that does NOT advance the spine of the enclosing
path. On replay, the turtle's position, heading, and up are saved; the
body runs (moves, marks, etc.); then the pose is restored. Anchors
created inside the body persist — only the spine cursor rewinds.

`side-trip` is the primitive for "drop a mark off the side of the main
axis and keep walking". Without it, every off-spine excursion would
have to manually undo each `(f)`, `(th)`, `(tv)` it issued before
marking the side point.

## Parameters

- `body` — one or more commands forming the sub-path. Anything legal in
  a `path` body works: turtle movements, marks, `follow`, nested
  `side-trip`s.

## Example

{{example: side-trip-basic}}

<!-- example-source: side-trip-basic -->
```clojure
(def skel
  (path
    (mark :start)         ; (0 0 0)
    (f 50)                ; spine at (50 0 0)
    (side-trip
      (th 90) (f 27)      ; off to the side
      (tv -90) (f 37)     ; and down
      (mark :branch))     ; mark at (50 27 -37)
    (mark :after)         ; back at (50 0 0)
    (f 10) (mark :end)))  ; (60 0 0)

(register layout (extrude (circle 0.6) skel))
```
<!-- /example-source -->

The side-trip drops `:branch` off the spine; `:after` lands at the same
place as the spine before the side-trip — the body's `(th 90) (f 27)
(tv -90) (f 37)` does not need to be manually undone.

## Variations

{{example: side-trip-helper}}

<!-- example-source: side-trip-helper -->
```clojure
(defn limb [side depth mname]
  (path
    (side-trip
      (th (if (pos? side) 90 -90))
      (f (abs side))
      (tv -90) (f depth) (tv 90)
      (mark mname))))

(def body
  (path
    (mark :pin-axis)
    (f 50) (follow (limb  27 37 :left-1)) (follow (limb -27 37 :right-1))
    (f -80) (follow (limb  27 37 :left-2)) (follow (limb -27 37 :right-2))))

(register skel (extrude (circle 0.6) body))
```
<!-- /example-source -->

Helper functions that drop marks become self-contained: the side-trip
guarantees the spine returns to its pre-call pose, no matter how the
helper navigates internally.

## Notes

- Marks inside the body are kept; the spine pose is restored. This is
  the inverse of `follow` for splicing: `follow` keeps every motion,
  `side-trip` undoes them.
- Nesting and composition work: `(side-trip (follow X) (mark :Y))` is
  legal — the inner `follow` splices into the side-trip's sub-path, and
  only that sub-path is scoped.
- `side-trip` is implemented as `(side-trip-fn (path body))`: the body
  builds an inner path, then a recording command is emitted that knows
  to restore the spine on replay. Failures inside the body raise the
  inner `path` error; the parent path is untouched.

## See also

- **Guide:** placeholder → cap. 5 (Paths and anchors)
- **Related:** `path`, `follow`, `mark`, `with-path`
