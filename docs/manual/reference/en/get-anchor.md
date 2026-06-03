---
name: get-anchor
category: turtle-movement
since: ""
status: stable
---

# get-anchor

## Signature

`(get-anchor anchor-name)`

## Description

Return the pose of a single named anchor from the turtle's current anchor table. The result is a map `{:position [x y z] :heading [hx hy hz] :up [ux uy uz]}`, or `nil` when no anchor with that name is in scope.

`get-anchor` is the per-name version of `anchors` (which returns the whole map). Use it to read a specific anchor's pose for measurement, for branching, or to feed coordinates into a downstream calculation. It is read-only — querying an anchor never moves the turtle, never modifies the registry.

Anchors come from the same source as for `goto` / `look-at` / `path-to`: marks recorded in a path, pinned at the live turtle pose by `with-path`, or anchors associated with a registered mesh via `attach-path`.

## Parameters

- `anchor-name` — keyword. Looked up in `:anchors` on the current turtle state.

## Example

{{example: get-anchor-basic}}

<!-- example-source: get-anchor-basic -->
```clojure
(def arm-sk
  (path (mark :shoulder) (f 30) (mark :elbow) (f 20) (mark :wrist)))

(with-path arm-sk
  (let [w (get-anchor :wrist)]
    (out (str "wrist position: " (:position w)))
    (out (str "wrist heading:  " (:heading w)))))
```
<!-- /example-source -->

Inside a `with-path` scope, the wrist mark resolves to an anchor map. `get-anchor` returns its three pose components for inspection or downstream maths.

## Variations

{{example: get-anchor-distance-check}}

<!-- example-source: get-anchor-distance-check -->
```clojure
;; Use get-anchor to compute the length between two marks at runtime
(defn anchor-distance [a b]
  (let [pa (:position (get-anchor a))
        pb (:position (get-anchor b))
        d  (vec3- pb pa)]
    (sqrt (vec3-dot d d))))

(def skel (path (mark :a) (f 10) (th 30) (f 18) (mark :b)))

(with-path skel
  (out (str "a→b: " (anchor-distance :a :b))))
```
<!-- /example-source -->

A small helper reads two anchors and reports the Euclidean distance between them. This is the standard pattern when an extrusion length, a beam thickness, or a clearance margin depends on the live geometry of the skeleton.

## Notes

- **Returns `nil` on miss.** No exception is thrown when the name is unknown — handy in branching, dangerous when used as a plain map. Wrap accesses in `if-let` / `when-let` when the anchor's presence is not guaranteed.
- **Reads from the live turtle.** The anchor table is part of turtle state, so `get-anchor` only sees what is in scope at the time of the call. Outside a `with-path` (or other anchor-providing macro), the table is empty.
- **No movement, no draw.** Unlike `goto`, `get-anchor` has zero side effects — it is the pure "look up a pose by name" primitive.
- **For all anchors at once, use `anchors`.** `(anchors)` of a registered mesh or `(anchors path)` of a path returns the full `name → pose` map; `get-anchor` is the single-name version that reads from the *live turtle*.

## See also

- **Related:** `anchors`, `mark`, `with-path`, `goto`, `look-at`,
  `path-to`
