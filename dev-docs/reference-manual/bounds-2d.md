---
name: bounds-2d
category: path
since: ""
status: stable
---

# bounds-2d

## Signature

`(bounds-2d path-or-shape)`

## Description

Compute the 2D axis-aligned bounding box of a path or a shape, in the
XY plane. Returns a map:

```
{:min      [x-min y-min]
 :max      [x-max y-max]
 :center   [cx cy]      ; midpoint of min/max
 :centroid [cx cy]      ; mean of points
 :size     [width height]}
```

For paths, the implicit origin `[0 0]` is included in the point set.
For shapes, only the contour vertices are considered.

Returns `nil` when the input is neither a path nor a shape, or when
the input has no points.

## Parameters

- `path-or-shape` — a path map (`{:type :path …}`) or a shape map
  (`{:type :shape …}`).

## Example

{{example: bounds-2d-path}}

<!-- example-source: bounds-2d-path -->
```clojure
(def trail (path (f 20) (th 90) (f 30) (th 90) (f 10)))
(let [b (bounds-2d trail)]
  (out (str "size = " (:size b) ", center = " (:center b))))
```
<!-- /example-source -->

The bbox includes the origin and every waypoint the path passes
through, so it covers the path's full 2D extent.

## Variations

{{example: bounds-2d-shape}}

<!-- example-source: bounds-2d-shape -->
```clojure
(def s (star 4 5 12 22))
(let [b (bounds-2d s)]
  (out (str "star bounds = " (:min b) " .. " (:max b))))

;; Use the size to derive a backing plate
(let [[w h] (:size (bounds-2d s))]
  (register plate (extrude (rect (+ w 4) (+ h 4)) (u 0.5))))
```
<!-- /example-source -->

For shapes, `bounds-2d` is the natural way to compute "fit-around"
geometry: a backing plate, a containing frame, or a registration mark.

## Notes

- `:center` and `:centroid` differ in general: `:center` is the bbox
  midpoint, `:centroid` is the unweighted mean of the points. For
  symmetric inputs they coincide.
- The bbox is computed in the path's local 2D coordinate frame
  (origin = path start, heading = `+X`). It does NOT reflect any
  positioning the turtle would impose at extrusion time.
- For 3D bounding information of a mesh, see `bounds` (in the
  registration & visibility category).

## See also

- **Guide:** placeholder → cap. 5 (Paths and anchors)
- **Related:** `bounds`, `mark-pos`, `fit`, `path`
