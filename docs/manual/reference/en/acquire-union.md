---
name: acquire-union
category: acquisition
since: ""
status: experimental
---

# acquire-union

## Signature

`(acquire-union a b …)`

## Description

Two (or more) shooting sessions of the **same object**, as one value, in the
**first session's frame**.

A turntable only reaches a band of angles: no view from above, none from
underneath. The cure is to shoot the object again lying in a different pose
and fuse the sessions. Because the object is rigid and both sessions are in
millimetres (the registration plate sets the scale), the two differ by exactly
**one rigid motion**, and `acquire-union` recovers it.

What ties the sessions together is **your declaration, not a detector**: marks
with the **same name** in both sessions are the same physical point. Nothing is
matched photometrically — the same principle that makes the whole channel work
on smooth, textureless plastic.

The motion is **recomputed at every eval** and never written into the source:
improve a mark, press Run, and the fusion improves with it. The price, stated
plainly: the anchor marks must **stay** in the source — they are the join, and
deleting one un-fuses the sessions.

## Parameters

- `a` — the reference acquisition. Its frame is the fused frame.
- `b …` — further acquisitions of the same object, each aligned onto `a`.

## Returns

The fused acquisition: the first session's `:proxy`, `:pose`, `:faces` and
`:dir`, with

- `:marks` — every mark of every session, the later ones carried into the first
  session's frame. Anchor marks (the shared names) collapse into one averaged
  pose, because after the fit they *are* one point.
- `:sessions` — one entry per session: `:dir`, `:proxy`, `:pose`, the
  `:transform` applied to it, and its `:rms-mm`.

Returns `nil`, with a printed reason, when the anchors do not determine a
motion — never a plausible-looking one.

## Example

```clojure
(def A (acquire "scans/reader-in-piedi/" {…}))
(def B (acquire "scans/reader-capovolto/" {…}))

(def U (acquire-union A B))

;; a plane measured in B is now usable in A's frame
(turtle U :at :fondo (extrude (rect 20 12) (f 3)))
```

## How many marks it takes

- **Two plane marks** with well-separated origins are enough: their origins fix
  the line between them, and their **normals** fix the rotation about it.
- **Three point-like marks**, not collinear, also do.
- **One mark never does** — it fixes a point and leaves the orientation free.

Marks closer together than 5 mm are refused: on an object of any size, the
rotation such a short baseline determines is click noise magnified.

## What it prints

One line per anchor — its residual in millimetres and how far its normal is
turned — plus the overall rms. These are the numbers that say whether to trust
the fusion: a fused frame that is quietly 2 mm out looks exactly like a good one
until an extrusion misses the object. When one anchor's residual stands out from
the rest, it is named: either it was clicked badly in one of the sessions, or
the two marks sharing that name are not the same physical point.

## Notes

- `:up` is deliberately **not** used by the fit. It is projected from the
  object's own pose, which differs between sessions by construction; only
  origins and normals carry across.
- `:shapes` are not fused in v1: a traced outline is geometry, not a pose, and
  carrying it across is a separate move. Each session's own value stays under
  `:sessions`.
- The acquisition stage keeps showing the **first** session, with all the marks
  drawn — including the transported ones. That is a free check: a mark measured
  in session B must land on the object in session A's photos.

## See also

- **Related:** `acquire`, `plane-mark`, `edit-plane-mark`, `anchors`, `turtle`
