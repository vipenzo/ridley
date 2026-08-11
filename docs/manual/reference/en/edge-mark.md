---
name: edge-mark
category: acquisition
since: ""
status: stable
---

# edge-mark

## Signature

`(edge-mark edge-map)`

## Description

A **measured edge** of an acquisition: a pose that runs **along** the edge, plus
its two ends. It is what the measuring gesture writes into an `(acquire …)`'s
`:edges` block, and it **returns the map unchanged** — its job is grammatical,
like `plane-mark`'s.

```clojure
:edges {:bordo-alto (edge-mark {:position [-18.49 6.57 17.25]
                                :heading  [0.18 -0.98 0.03]
                                :up       [-0.01 0.03 0.99]
                                :a [-18.49 6.57 17.25]
                                :b [-15.66 -9.22 17.81]
                                :length 16.0473})}
```

Heading **along** the edge, not across it, is what makes it useful with no new
DSL: `(f …)` travels the heading, so the turtle starts at one end and arrives at
the other.

```clojure
;; a fillet down the whole edge
(turtle (:bordo-alto (:edges A)) (extrude (circle 2) (f 16.0473)))
```

It is gentle but not silent: it checks the expected keys and that `:length`
still matches the distance between `:a` and `:b`, and reports what looks wrong
**without changing or refusing the data**. An edge whose length was hand-edited
is still your edge; correcting it quietly would hide the fact that the two no
longer describe the same segment.

## Parameters

- `edge-map` — `{:position <one end> :heading <direction> :up … :a … :b …
  :length …}`, plus the display keys below.

Display keys, honoured by the stage:

- `:show` — `false` hides it, `:prove` also draws the points it came from,
  absent draws the plain segment;
- `:label` — `false` for no name over the photograph, a string for a different
  one.

## Notes

- To measure one, write `(edit-edge-mark)` at the key you want and Run.
- To delete one, delete its line. Everything that used it will say so on the
  next Run — including a `(plane-from-edges …)` that named it.
- A straight edge is the sturdiest evidence in this channel: it is recovered by
  intersecting the planes its image lines span, so **no point is ever paired
  with another** and none of a curve's ghost trouble applies.
- Two non-parallel edges of the same face pin its plane exactly.

## See also

- `edit-edge-mark` — measure one
- `plane-from-edges` — the plane through the edges you name
- `curve-mark` — curves measured before the gesture was removed
- `acquire` — the form whose `:edges` this lives in
