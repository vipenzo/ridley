---
name: acquire
category: acquisition
since: ""
status: stable
---

# acquire

## Signature

`(acquire dir)`
`(acquire dir opts)`

## Description

An **acquisition**: a photographed object, turned into a Ridley value you can
build on. It is what the acquisition session writes into your source when you
confirm, and from then on it is ordinary code — you edit it, delete lines of it,
and re-run it like anything else.

```clojure
(def A (acquire "/Users/me/scans/collare"
         {:proxy (registration-plate)
          :pose  {:position [0 0 0] :heading [0 0 1] :up [0 1 0]}
          :shapes {}
          :marks  {}
          :edges  {}}))
```

Evaluating it does two things. It returns a **map** you can take apart by name,
and it turns the viewport into the **stage**: the photographs of that folder
become clickable frustums, clicking one flies the camera into that photo and
shows it behind your geometry, and everything you have measured is drawn over
it. Nothing here is modal — the stage is viewport state, so your normal editing
carries on around it.

What comes back:

```clojure
(:proxy A)      ; the reference mesh, posed
(:pose A)       ; where the object sits
(:shapes A)     ; the traced outlines, by name
(:marks A)      ; the measured planes, by name
(:edges A)      ; the measured edges, by name
(:faces A)      ; the proxy's own faces, by name — computed, not stored
(:dir A)        ; the session folder
```

And the names are anchors, so the turtle can stand on any of them:

```clojure
(turtle A :at :coperchio (extrude (rect 18 16) (f 2)))
```

## Parameters

- `dir` — absolute path to the session folder (the photographs plus their
  `session.json`). Built in the app on first use from `NOTE.md` and the images.
- `opts` — the map that holds everything measured:
  - `:proxy` — the reference object the cameras were registered against, usually
    `(registration-plate)` or a `(box …)`;
  - `:pose` — where that object sits in your world;
  - `:shapes` — outlines traced on the photographs, as named `(poly …)`;
  - `:marks` — working planes, as named `(plane-mark …)` or
    `(plane-from-edges …)`;
  - `:edges` — measured edges, as named `(edge-mark …)`.

## Notes

- **Marks and edges are kept apart on purpose.** A mark's `:heading` is a
  surface normal; an edge's runs *along* the edge. Anything that reads marks as
  planes would quietly misread an edge as a plane whose normal points down its
  own length.
- If a mark and an edge share a name, `(turtle A :at name …)` uses the **mark**,
  and says so once in the output. The edge stays reachable as
  `(name (:edges A))`.
- A mark named like one of the proxy's faces wins over the face, for the same
  reason and with the same warning.
- Deleting a measurement is deleting its line. There is no undo command because
  none is needed: the source is the only place any of it lives.
- Re-opening the whole acquisition for re-registration is
  `(edit-acquire "dir" …)` — put `edit-` in front of the head and Run.

## See also

- `edit-edge-mark` — measure an edge into `:edges`
- `plane-from-edges` — a plane from the edges you have measured
- `plane-mark` · `edge-mark` — the resting forms
- `acquire-union` — fuse two shooting sessions of the same object
- `turtle` — how an acquisition's names pose the turtle
