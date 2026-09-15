---
name: plane-by-eye
category: acquisition
since: ""
status: stable
---

# plane-by-eye

## Signature

`(plane-by-eye ring pose-map)`
`(plane-by-eye pose-map)`
`(plane-by-eye ring)`

## Description

A plane of an acquisition placed **by eye**: a named working plane on the
photographed object, an ordinary Ridley pose whose `:heading` is the surface
normal, that was carried into place by hand with the stage's gizmo rather than
fitted through measured points. It is what `edit-plane-by-eye` writes into the
`:marks` of an `(acquire …)`.

```clojure
:marks {:coperchio (plane-by-eye :big {:position [12.4 -3.1 41.0]
                                        :heading [0 0 1]
                                        :up [0 1 0]})}
```

With a pose map it **returns the map unchanged**, after `plane-mark`'s gentle
checks (the expected keys, `:heading` ⊥ `:up`), reported in the output panel
without touching the data. The ring is provenance: which ring of the
registration cage the plane started from, and how big its disc is drawn when
the mark is re-opened.

With only a ring — `(plane-by-eye :big)` — it is the plane **the ring itself
spans**: the cage's centre, normal along the ring's axis. The form returns a
deferred spec that `acquire` resolves against its own proxy (the `:marks` map is
evaluated before the acquire that owns the rings), the same two-step trick as
`plane-from-edges`. It is also what Esc leaves behind when a creation is
abandoned, and a perfectly usable mark on its own.

## Parameters

- `ring` — `:big`, `:medium`, `:small` (the cage's rings, by size) or `:x`,
  `:y`, `:z` (by axis). Required without a pose map.
- `pose-map` — `{:position … :heading … :up …}`. Display keys `:show` and
  `:label` are honoured as on `plane-mark`.

## Examples

```clojure
;; trace a contour on the plane
(turtle A :at :coperchio (edit-path-2d))

;; grow something out of it — :heading points away from the surface
(turtle A :at :coperchio (extrude (circle 5) (f 2)))

;; move it: put edit- in front and Run
:marks {:coperchio (edit-plane-by-eye :big {…})}
```

## See also

- `edit-plane-by-eye` — re-open this mark on the stage, gizmo and all
- `plane-mark` — the measured sibling, fitted through triangulated points
- `acquire` — the form whose `:marks` this lives in
