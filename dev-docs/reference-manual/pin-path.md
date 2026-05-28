---
name: pin-path
category: path
since: ""
status: stable
---

# pin-path

## Signature

`(pin-path path)`

## Description

Resolve a path's marks at the **current turtle pose** and return the
resulting `{anchor-name → {:position [x y z] :heading [x y z] :up [x y z]}}`
map.

`pin-path` is the value-returning counterpart of `with-path`: it
captures the same anchor resolution but as plain data, without opening
a scope. Use it for introspection ("where would these marks be if I
pinned them here?") and for ad-hoc per-anchor logic that does not fit
the `on-anchors` dispatch shape.

Compare to `(anchors path)`, which always resolves marks at the world
origin regardless of the current turtle. `pin-path` is the natural
choice whenever the path is acting as a *relative* template positioned
by the caller; `anchors` is the right tool when the path's marks are
meaningful in absolute world coordinates.

Returns `nil` if `path` is not a path map.

## Parameters

- `path` — a recorded path value (the result of `path`, `quick-path`,
  etc.).

## Example

{{example: pin-path-basic}}

<!-- example-source: pin-path-basic
(def skel (path (mark :a) (f 20) (mark :b)))

;; At origin: marks resolve at their path-local positions.
(out (str "at origin: " (pin-path skel)))

;; Move the turtle and the marks follow.
(f 50)
(out (str "at f50: " (pin-path skel)))
-->

The two outputs share names but the second has positions shifted by
the turtle's `(f 50)`.

## Variations

{{example: pin-path-custom-loop}}

<!-- example-source: pin-path-custom-loop
(def skel (path (mark :foot-1) (rt 20)
                (mark :foot-2) (rt 20)
                (mark :foot-3)))

(register tripod
  (concat-meshes
    (for [[anchor-name pose] (pin-path skel)]
      (let [size (if (= :foot-2 anchor-name) 6 3)]
        (turtle :pose pose
          (attach (cyl size 12)))))))
-->

A custom loop over `pin-path`'s result is the right tool when the
per-anchor logic is more dynamic than a fixed set of dispatch clauses —
e.g. parameters that depend on the anchor name, predicates that
combine multiple marks, or post-processing applied after the loop.

## Notes

- **No scope.** Unlike `with-path`, `pin-path` does not pin the path
  into the turtle's `:anchors` map and does not mutate any state. The
  returned map is a plain Clojure value, safe to bind in a `let` and
  pass around.
- **Single snapshot.** The resolution happens at call time. If the
  turtle pose changes after `pin-path`, the captured map does not
  follow — call `pin-path` again, or use `with-path`/`on-anchors`
  which read the pose at the moment they need it.
- **`on-anchors` already does this internally.** When the target is a
  path, `on-anchors` calls the same resolver and dispatches against
  the result, so most use cases don't require a manual `pin-path`.
  Reach for `pin-path` when the natural shape is a `for` over anchors
  with custom logic, or when you want to inspect the anchor map.
- **Mesh anchors.** `pin-path` is path-only. To inspect a mesh's
  stored anchors, use `(anchors mesh)`; mesh anchors live in world
  coordinates and do not need pinning.

## See also

- **Guide:** placeholder → cap. 8 (Assemblaggio)
- **Related:** `anchors`, `with-path`, `on-anchors`, `mark`, `path`,
  `mark-pos`
