---
name: shape-centroid
category: generative-operations
since: ""
status: stable
---

# shape-centroid

## Signature

`(shape-centroid shape)`

## Description

The centroid of a shape's **outer** contour — the plain mean of its
points — as `[x y]`. Holes are ignored, and so is edge length: a contour
with many points on one side is pulled that way, which is exactly the
reference `shell` uses, so a custom thickness-fn that computes its own
angles with this function agrees with the built-in styles point for point.

`displace-radial` measures its directions from the same point.

Returns `[0 0]` for a shape with no points.

## Parameters

- `shape` — a shape map (`{:type :shape …}`), e.g. from `circle`, `rect`,
  `star`, `poly`, or `path-to-shape`.

## Example

{{example: shape-centroid-basic}}

<!-- example-source: shape-centroid-basic -->
```clojure
;; A displacement that reads the angle the way shell does — around the
;; centroid, not the origin — so it stays put on an off-centre profile
(def prof (translate-shape (rect 30 20) 15 0))

(defn ribbed [shape t]
  (let [[cx cy] (shape-centroid shape)]
    (displace-radial shape
      (fn [[x y]]
        (* 1.5 (cos (* 8 (atan2 (- y cy) (- x cx)))))))))

(register ribs (loft-n 32 (shape-fn prof ribbed) (f 40)))
```
<!-- /example-source -->

## Notes

- `angle` (the helper) measures from the **origin**, not the centroid;
  the two coincide only for shapes built centred, which is what the
  built-in constructors produce.
- For an area-weighted centroid of a polygon, integrate over the edges
  yourself — this one is the vertex mean on purpose, to match `shell`.

## See also

- **Related:** `shell`, `displace-radial`, `angle`, `shape-fn`,
  `bounds-2d`
