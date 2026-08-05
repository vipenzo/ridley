---
name: acquire-union
category: acquisition
since: ""
status: experimental
---

# acquire-union

## Signature

`(acquire-union a b …)`
`(acquire-union [[label acquisition] …] [[ref ref] …])`

## Description

Two (or more) shooting sessions of the **same object**, as one value, in the
**first session's frame**.

A turntable only reaches a band of angles: no view from above, none from
underneath. The cure is to shoot the object again lying in a different pose
and fuse the sessions. Because the object is rigid and both sessions are in
millimetres (the registration plate sets the scale), the two differ by exactly
**one rigid motion**, and `acquire-union` recovers it.

What ties the sessions together is **your declaration, not a detector**:
nothing is matched photometrically — the same principle that makes the whole
channel work on smooth, textureless plastic.

A mark is believed as a **plane**, not as a point. This matters in practice: a
flat zone of the object is easy to find again in another set of photos, while a
*specific point on it* is not — and the origin of a plane mark is only the
centroid of wherever you happened to click. So the fit uses each mark's plane
(its normal, and how far it sits along that normal) and lets the origin slide
freely within it. Moving the orange dot changes nothing.

When a mark's origin really is a physical feature — a corner, a printed dot —
say so and its full position is used:

```clojure
:marks {:spigolo (plane-mark {:position [...] :heading [...] :up [...] :point? true})}
```

The motion is **recomputed at every eval** and never written into the source:
improve a mark, press Run, and the fusion improves with it. The price, stated
plainly: the anchor marks must **stay** in the source — they are the join, and
deleting one un-fuses the sessions.

## The two call forms

**Short** — sessions positional, correspondence by equal mark name:

```clojure
(acquire-union A B)
```

**Declared** — labelled sessions and an explicit list of correspondences:

```clojure
(acquire-union [[:A A] [:B B]]
               [[:A/testa  :B/piano-1]
                [:A/becco  :B/piano-3]])
```

The declared form exists because a name is a weak declaration once marks are
believed as planes: the stage names them `:piano-1`, `:piano-2` … per session,
so two `:piano-1` collide *by accident*, and even when they genuinely are the
same zone their origins sit at different places on it — both legitimate, so
neither can be discarded. Here nothing is guessed: correspondences are said out
loud, and every mark keeps its session's label (`:A/testa`, `:B/piano-1`), so
both survive and nothing collides.

The session list is a **vector** because its order answers "whose frame is the
fused frame": the first one's.

## Returns

The fused acquisition: the reference session's `:proxy`, `:pose`, `:faces` and
`:dir`, with

- `:marks` — every mark of every session, the later ones carried into the
  reference frame. In the declared form each is keyed `:label/name`; in the
  short form names are bare and a shared name keeps the reference session's
  mark (they are *not* averaged: with plane semantics the two origins are
  different points on the same plane, so their mean is a third arbitrary one).
- `:sessions` — one entry per session: `:label`, `:dir`, `:proxy`, `:pose`, the
  `:transform` applied to it, and its `:rms-mm`.

Returns `nil`, with a printed reason, when the anchors do not determine a
motion — never a plausible-looking one.

## Example

```clojure
(def A (acquire "scans/clip-in-piedi/" {…}))
(def B (acquire "scans/clip-coricato/" {…}))

(def U (acquire-union [[:A A] [:B B]]
                      [[:A/testa :B/piano-1]
                       [:A/fianco :B/piano-2]
                       [:A/becco  :B/piano-3]]))

;; a plane measured in B, now usable in A's frame
(turtle U :at :B/piano-3 (extrude (rect 20 12) (f 3)))
```

## How many marks it takes

A plane correspondence constrains three of the six degrees of freedom — the
direction of the normal and the distance along it — so:

- **Three planes with independent normals** determine the motion outright. Three
  faces that look in three different directions: this is the case to aim for.
- **Two planes leave the slide along their line of intersection free.** Refused,
  with that explanation.
- **Planes whose normals are all parallel** pin only that one direction.
  Refused.
- **Two planes plus one real point** (`:point? true`) work — nobody enumerated
  that case, it follows from the rank of the system, which is what is actually
  checked.
- **Three point-like marks**, not collinear, also work.

Point anchors closer together than 5 mm are refused: the rotation such a short
baseline determines is click noise magnified. Planes are exempt — what matters
there is that the normals differ, not that the marks are far apart.

## What it prints

One line per anchor — for a plane, its distance from the twin **plane** and how
far its normal is turned; for a point, the full distance between the origins —
plus the overall rms and how many of each kind were used. These are the numbers
that say whether to trust the fusion: a fused frame that is quietly 2 mm out
looks exactly like a good one until an extrusion misses the object. When one
anchor's residual stands out from the rest, it is named: either it was measured
badly in one of the sessions, or the two marks you declared equal are not the
same zone.

## Notes

- `:up` is deliberately **not** used by the fit. It is projected from the
  object's own pose, which differs between sessions by construction; only the
  planes (and any declared points) carry across.
- `:shapes` are not fused in v1: a traced outline is geometry, not a pose, and
  carrying it across is a separate move. Each session's own value stays under
  `:sessions`.
- The acquisition stage keeps showing the **first** session, with all the marks
  drawn — including the transported ones. That is a free check: a mark measured
  in session B must land on the object in session A's photos.

## See also

- **Related:** `acquire`, `plane-mark`, `edit-plane-mark`, `anchors`, `turtle`
