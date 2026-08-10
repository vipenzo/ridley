---
name: edit-edge-mark
category: acquisition
since: ""
status: stable
---

# edit-edge-mark

## Signature

`(edit-edge-mark)`
`(edit-edge-mark edge-map)`

## Description

**Measure an edge of the photographed object.** Written as a value inside an
evaluated `(acquire …)`'s `:edges`, it arms the gesture — there is no button to
press and no mode to switch on:

```clojure
:edges {:bordo-alto (edit-edge-mark)}
```

Run that, and the stage puts you inside a photograph with the measurement open.
**Paint over the edge** with the marker (drag; a single click works too when the
image can answer on its own), then change photograph with `]` and paint the
**same** edge from a different side. Two views are enough — a straight edge has
four degrees of freedom and each photograph pins two.

Press **Enter** and the measurement replaces the form where it stands:

```clojure
:edges {:bordo-alto (edge-mark {:position [-18.49 6.57 17.25] :heading [0.18 -0.98 0.03]
                                :up [-0.01 0.03 0.99] :a [-18.49 6.57 17.25]
                                :b [-15.66 -9.22 17.81] :length 16.0473})}
```

**The key you wrote is the name**, so it is yours: `:bordo-alto` rather than
`:spigolo-7`. That is what makes the next line readable —
`(plane-from-edges :bordo-alto :bordo-basso)`.

**Esc** gives up and leaves the source as it was: an empty form is removed with
its key, a form wrapped around an already measured edge goes back to
`(edge-mark {…})` with its body byte-identical. A second Esc leaves the
photograph.

While you paint you see what you are doing: the band at the marker's true width,
the path you have travelled — green while it is still straight — and a dashed
line from where you started to where your hand is, which is the reference to
keep straight against.

## Parameters

- `edge-map` — an already measured edge, to measure again from scratch. Its
  contents are scaffolding: re-opening an edge **re-measures** it, because an
  edge's observations are pixel lines against photographs, and the source
  deliberately knows nothing about photographs or pixels.
- No argument — the ordinary case: create the edge at this key.

## Notes

- **What comes out is decided by the image, not by the form.** Paint a curved
  edge under `(edit-edge-mark)` and you get `(curve-mark …)`; press `c` on a
  curve that really is circular and you get `(circle-mark …)`. Rewriting the
  right form is the program's job.
- The two photographs must look at the edge from **different sides**. Moving
  along the edge does not help, and neither does standing exactly opposite: half
  a turn is as blind as none.
- When the measurement is refused, the panel says why with its numbers —
  reprojection in pixels, the turn around the edge in degrees, and which
  photograph is the culprit when it can name one.
- `+` and `-` change the marker's nib; `l` hides the names drawn over the photo;
  `r` throws away the stroke in hand; Backspace removes this photograph's stroke.
- With several `edit-…` forms present, the first is opened and the others wait.

## See also

- `edit-curve-mark` — the same gesture for a curved edge
- `edge-mark` — the resting form this pairs with
- `plane-from-edges` — what measured edges are for
- `acquire` — the form whose `:edges` this lives in
