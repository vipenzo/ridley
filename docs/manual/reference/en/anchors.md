---
name: anchors
category: path
since: ""
status: stable
---

# anchors

## Signature

`(anchors target)`

## Description

Return the named anchor map of a path or registered mesh — a value of
the form `{name → {:position [x y z] :heading [x y z] :up [x y z]}}`.

Two input shapes are accepted:

- **Path.** When `target` is a recorded path, `anchors` walks the path
  from the world origin (`[0 0 0]`, heading `+X`, up `+Z`) and
  resolves every `(mark …)` into a world-space pose. Useful for
  inspecting a skeleton path without pinning it via `with-path`.

- **Mesh.** When `target` is a registered mesh (by keyword/name or as
  the mesh value), `anchors` returns the mesh's `:anchors` map — the
  anchors attached to it by `attach-path` or by being registered
  inside a `with-path` scope.

Returns `nil` if the target has no resolvable anchors.

## Parameters

- `target` — a path map, a registered mesh keyword/name, or a mesh
  value.

## Example

{{example: anchors-path}}

<!-- example-source: anchors-path -->
```clojure
(def skel (path (mark :pin) (f 50) (mark :tip)))

(out (str "marks: " (sort (keys (anchors skel)))))
(out (str "tip @ " (:position (:tip (anchors skel)))))
```
<!-- /example-source -->

The path's marks are resolved at the world origin so they can be
inspected without going through a carrier mesh.

## Variations

{{example: anchors-mesh}}

<!-- example-source: anchors-mesh -->
```clojure
(def skel (path (mark :a) (f 20) (mark :b)))

(register Sk (sphere 0.5))
(attach-path :Sk skel)

(out (str "Sk anchors: " (sort (keys (anchors :Sk)))))
```
<!-- /example-source -->

After `attach-path`, the mesh carries the skeleton's anchors. `anchors`
returns the same map whether queried by keyword name, mesh value, or
the original path — useful for runtime introspection.

## Notes

- For a path, the anchors are returned in *world* coordinates with the
  origin pose. Inside `with-path`, marks are resolved relative to the
  current turtle pose; the two views are intentionally different.
- `anchors` is read-only: it never mutates the path, the mesh, or the
  registry.

## See also

- **Guide:** placeholder → cap. 5 (Paths and anchors)
- **Related:** `path`, `with-path`, `mark`, `attach-path`, `path-to`,
  `mark-pos`
