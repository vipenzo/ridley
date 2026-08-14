---
name: plane-mark
category: acquisition
since: ""
status: stable
---

# plane-mark

## Signature

`(plane-mark pose-map)`

## Description

A **plane mark** of an acquisition: a named working plane on the photographed
object, expressed as an ordinary Ridley pose whose `:heading` is the surface
normal. It is what the acquisition stage's *Piano* gesture writes into the
`:marks` of an emitted `(acquire …)`.

```clojure
:marks {:coperchio (plane-mark {:position [0.86 12.56 18.3]
                                :heading [-0.48 -0.22 -0.85]
                                :up [-0.48 -0.74 0.47]
                                :from [[…] […] […]]})}
```

It **returns the map unchanged**. Its job is grammatical, not computational: it
is the resting form of the pair `edit-plane-mark ⇄ plane-mark`, so re-opening a
mark is the same gesture as every other editor in the family — put `edit-` in
front of the head and Run — instead of hand-wrapping a multi-line map.

It is gentle but not silent. It checks the little there is to check — that the
expected keys are present, and that `:heading` and `:up` are perpendicular — and
reports what looks wrong in the output panel **without changing or refusing the
data**. A mark that has drifted out of square is still the user's mark:
straightening it quietly would hide a real problem (a hand-edited heading, a
mark copied between objects), and rejecting it would break a source that
otherwise renders.

A bare `{…}` literal remains valid wherever a mark is accepted, so marks emitted
before this form keep working — they simply do not announce what they are.

## What it draws, and how to quieten it

On the acquisition stage every mark draws itself over the photo: a translucent
disc, its origin, and — when asked — the points it was fitted through. On an
object with a dozen marks that is a lot of ink, so each mark carries its own
display keys. They live on the mark because that is where the mark lives: they
survive closing the gesture, they can be set on one mark without touching the
others, and they are text, so they diff and undo like everything else.

```clojure
:marks {:coperchio (plane-mark {… :show false})           ; draw nothing
        :fianco    (plane-mark {… :label false})          ; no name next to it
        :base      (plane-mark {… :label "appoggio"})     ; that name instead
        :zona      (plane-mark {… :show :prove})}         ; also its fitted points
```

`:show :prove` is opt-in on purpose: the fitted points are evidence, worth
asking for and not worth carrying always.

## Parameters

- `pose-map` — `{:position … :heading … :up …}`, optionally with
  `:from [[x y z] …]`, the triangulated points the plane was fitted through
  (see `edit-plane-mark`), and the display keys `:show` (`true` by default,
  `false` to hide it, `:prove` to add its fitted points) and `:label` (`true`
  to write its name, a string to write that instead, `false` or absent for
  none).

  `:label` has two defaults, by context. Inside a mark gesture everything is
  named unless it says otherwise, because you are working through a list. On the
  ordinary stage nothing is named unless it asks — a fused acquisition can hold
  every mark of every session, and naming them all buries the photograph.

## Examples

```clojure
;; trace a contour on the plane
(turtle (:coperchio (:marks A)) (edit-path-2d))

;; or grow something out of it — :heading points away from the surface
(turtle (:coperchio (:marks A)) (extrude (circle 5) (f 2)))
```

## See also

- `edit-plane-mark` — re-open this mark on the acquisition stage
- `acquire` — the form whose `:marks` this lives in
- `turtle` — how a mark poses the turtle
