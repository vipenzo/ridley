---
name: acquire-union
category: acquisition
since: ""
status: experimental
---

# acquire-union

## Signature

`(acquire-union [[label acquisition] …])`
`(acquire-union [[label acquisition] …] [[ref ref …] …])`

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

## Naming the sessions, and the join

Sessions are always **labelled**, and the label is what lets every mark survive
the fusion under its own address — `:A/testa` and `:B/testa` are the two
sessions' measurements of the same zone, and both are kept.

That addressability is what makes the **implicit** rule safe: by default, marks
with the **same name** in two sessions are the same zone.

```clojure
(acquire-union [[:A A] [:B B]])
```

Give the zone the same name in both sessions and you have declared the
correspondence. Rename the stage's `:piano-1`, `:piano-2` … as you create them:
that is a text edit in the source, done while you still remember which zone was
which, and it leaves a source that reads `:testa`, `:fianco`, `:becco` instead
of counters.

When the names cannot agree — `:top` means one face in one session and another
face in the other, or you would rather not rename — say the correspondences out
loud instead:

```clojure
(acquire-union [[:A A] [:B B]]
               [[:A/testa :B/piano-1]
                [:A/becco :B/piano-3]])
```

Both routes end in the same place; the second only replaces the name-matching.

The session list is a **vector** because its order answers "whose frame is the
fused frame": the first one's.

### More than two sessions

With the implicit rule nothing changes: name the zone alike in every session
that sees it, and leave it out of the ones that do not. The anchors of each
session are the names it shares with the reference.

With an explicit list, a row is **one zone**, not one couple. List every session
that sees it:

```clojure
(acquire-union [[:A A] [:B B] [:C C]]
               [[:A/piano-1 :B/piano-1 :C/piano-3]     ; the head, seen by all three
                [:A/piano-2 :B/piano-2 :C/piano-1]     ; the flank
                [:A/piano-3 :B/piano-3]])              ; the beak — C cannot see it
```

Three zones cost three rows, not six: each secondary session reads the same rows
against the reference. A row that does not name a session simply gives that
session no anchor — and if what is left does not determine its motion, that
session is refused by name, with the others still fused.

Every session is aligned onto the **reference** directly, so a zone the
reference cannot see is of no use, however many other sessions share it. Choose
the pose that sees the most as the first one.

## Returns

The fused acquisition: the reference session's `:proxy`, `:pose`, `:faces` and
`:dir`, with

- `:marks` — every mark of every session under `:label/name`, the later ones
  carried into the reference frame, **plus** each declared zone under its bare
  name, bound to the reference session's measurement of it. So `:testa` is the
  zone, `:A/testa` and `:B/testa` are the two measurements. Nothing is averaged:
  with plane semantics the two origins are different points on the same plane,
  so their mean would be a third arbitrary one.
- `:sessions` — one entry per session: `:label`, `:dir`, `:proxy`, `:pose`, the
  `:transform` applied to it, and its `:rms-mm`.

Returns `nil`, with a printed reason, when the anchors do not determine a
motion — never a plausible-looking one.

## Example

```clojure
;; the same three zones are named alike in both sessions
(def A (acquire "scans/clip-in-piedi/"  {… :marks {:testa … :fianco … :becco …}}))
(def B (acquire "scans/clip-coricato/"  {… :marks {:testa … :fianco … :becco …}}))

(def U (acquire-union [[:A A] [:B B]]))

(turtle U :at :becco   (extrude (rect 20 12) (f 3)))   ; the zone (A's measurement)
(turtle U :at :B/becco (extrude (rect 20 12) (f 3)))   ; B's own origin on it
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
