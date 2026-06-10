---
name: add-mark
category: path
since: ""
status: stable
---

# add-mark

## Signature

`(add-mark path name fraction)`

## Description

Return a **new** path (the input is left untouched) with a mark named
`name` inserted at `fraction` (0..1) of the path's total spine arc
length.

The path is walked along its spine — the top-level movement commands
(`:f` / `:u` / `:rt` / `:lt`); side-trips and pure rotations have zero
spine length. The single movement command that straddles the target
distance is split into `(move a) (mark) (move b)` so the mark lands
*exactly* at the requested fraction.

Because the result is just a path with an extra mark, that mark rides
`extrude` / `loft` / `revolve` into the produced mesh as an `:anchor`
(see [Profile marks → anchors](../../guides/en/11-curve-avanzate.md)).
This is the key use: a mark **on the curve** measures the realized
geometry, whereas a fixed construction mark measures a target. To track
a bezier's actual bow as you tweak its tension, mark its midpoint and
rule to it:

```clojure
(register wall
  (extrude (stroke-shape
            (add-mark (path (bezier-to-anchor ps :at :end :tension 0.5))
                      :apex 0.5) 3)
           (f 10)))
(ruler :wall :at :start :wall :at :apex)   ; reads less than the target when under-bowed
```

## Parameters

- `path` — any path (a `path` recording, `poly-path`, etc.).
- `name` — the mark name (keyword), e.g. `:apex`.
- `fraction` — position along the spine, `0` (start) to `1` (end),
  clamped to that range. `0.5` is the arc-length midpoint — the apex for
  a symmetric curve.

## Example

<!-- example-source: add-mark-apex -->
```clojure
(def a 45) (def D 51)
(def ps (path (mark :start) (f a) (th 90) (f a) (mark :end)))

;; mark the real midpoint of the smoothed corner, then measure it
(register wall
  (extrude (stroke-shape
            (add-mark (path (mark :start)
                            (side-trip (th 90) (f a) (th -135)
                                       (mark :center) (f D) (mark :D))
                            (bezier-to-anchor ps :at :end :tension 0.5))
                      :apex 0.5) 3)
           (f 10)))

(ruler :wall :at :center :wall :at :apex)   ; the curve's true bow on the diagonal
```
<!-- /example-source -->

See `examples/spigolo-quattro-modi.clj` for the full worked example,
where each method's wall carries a `:center → :apex` ruler.

## Notes

- The fraction is by **arc length**, not the curve's parameter `t`. For a
  symmetric curve the two coincide; in general arc-length `0.5` is the
  more intuitive "centre".
- Side-trips contribute no spine length (they restore the pose), so a
  mark at `0.5` of a profile that begins with a side-trip lands at the
  midpoint of the *drawn* curve.
- The split preserves geometry exactly (the two halves sum to the
  original segment, sign included).

## See also

- **Related:** `mark`, `anchors`, `ruler`, `distance`, `path`,
  `path-length`, `bezier-to-anchor`
