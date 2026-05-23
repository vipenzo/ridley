---
name: face-nearest
category: faces
since: ""
status: stable
---

# face-nearest

## Signature

`(face-nearest mesh point)`

## Description

Return the face whose **centroid** is closest to `point`. The result is a
face info map with `:id`, `:normal`, `:heading`, `:center`, and `:area`,
or `nil` if the mesh has no faces. Pure function; does not modify turtle
state.

`face-nearest` is the centroid-based counterpart of `face-at`. Use it when
the probe point is interior to the mesh, far from the surface, or when
you want stable behaviour as the point moves: centroid distance is smooth,
while plane distance can flip abruptly between parallel faces.

A common rule of thumb:

- Probe is near or just outside the surface → `face-at` (plane distance).
- Probe is anywhere else, or you want robustness to slight movements →
  `face-nearest` (centroid distance).

Internally calls `ensure-face-groups`, so it works on raw CSG output.

## Parameters

- `mesh` — a mesh value or a registered name.
- `point` — `[x y z]` in mesh coordinates.

## Example

{{example: face-nearest-interior}}

<!-- example-source: face-nearest-interior -->
```clojure
(register b (box 20))
;; A point inside the box — face-at could be ambiguous, face-nearest is stable
(:id (face-nearest :b [0 8 0]))
;; => :top
```
<!-- /example-source -->

Even from inside the box, the centroid of the top face (at `y = 10`) is
closer than any other, so `face-nearest` returns `:top`.

## Notes

- Centroid distance treats all faces uniformly. A large face whose
  centroid is far away can lose to a small face whose centroid is close —
  that is the intended behaviour, but worth knowing.
- For "which face does this point belong to" semantically, you may want
  to combine `face-nearest` with a normal-direction sanity check.

## See also

- **Related:** `face-at`, `find-faces`, `largest-face`, `face-info`,
  `attach-face`, `clone-face`
