---
name: path-to
category: path
since: ""
status: stable
---

# path-to

## Signature

`(path-to anchor-name)`

## Description

Build a path from the turtle's current position to a named anchor,
returning a path map. Two things happen:

1. The live turtle is reoriented to look at the anchor (its heading
   rotates to point toward the anchor's position).
2. A straight-line path from the current position to the anchor is
   returned, ready to be used as the trajectory of `extrude`, `loft`,
   or `revolve`.

`path-to` only makes sense inside a scope where the anchor is
resolvable — typically a `with-path` body, or after `attach-path` has
been called on a mesh. The anchor name is looked up in the active
anchor table.

## Parameters

- `anchor-name` — a keyword identifying an anchor reachable in the
  current scope.

## Example

{{example: path-to-basic}}

<!-- example-source: path-to-basic -->
```clojure
(def arm-sk (path (mark :shoulder) (f 30) (mark :elbow) (th 40) (f 25) (mark :wrist)))

(with-path arm-sk
  (goto :shoulder)
  (register upper (extrude (circle 1.5) (path-to :elbow))))
```
<!-- /example-source -->

Inside `with-path`, the marks of `arm-sk` become anchors. `path-to`
builds a straight extrusion trajectory from the turtle's current
position to `:elbow`, after orienting the turtle to face it.

## Variations

{{example: path-to-chain}}

<!-- example-source: path-to-chain -->
```clojure
(def arm-sk (path (mark :shoulder) (f 30) (mark :elbow) (th 40) (f 25) (mark :wrist)))

(with-path arm-sk
  (goto :shoulder)
  (register upper (extrude (circle 1.5) (path-to :elbow))))

(with-path arm-sk
  (goto :elbow)
  (register fore (extrude (circle 1.2) (path-to :wrist))))
```
<!-- /example-source -->

Each segment of an articulated assembly uses `path-to` to draw the
next bone from the current mark to the next.

## Notes

- `path-to` is a turtle-mutating call: it adjusts the turtle's heading
  as a side effect, so subsequent extrusions or registrations from the
  same position start with the correct orientation.
- The returned path contains a single forward segment plus the
  necessary orienting turns. For a curved trajectory between two
  anchors, use `bezier-to-anchor` instead.

## See also

- **Related:** `with-path`, `goto`, `look-at`, `bezier-to-anchor`,
  `mark`, `anchors`
