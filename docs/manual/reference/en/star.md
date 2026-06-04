---
name: star
category: 2d-shapes
since: ""
status: stable
---

# star

## Signature

`(star n-points outer-r inner-r)`

## Description

Construct a star-shaped 2D contour centered at the origin. Alternates
between vertices on an outer circle (the tips) and vertices on an inner
circle (the valleys). The shape is centered (`:centered? true`). Does not
modify turtle state.

## Parameters

- `n-points` — number of tips (e.g. `5` for a pentagram outline).
- `outer-r` — radius of the tip vertices.
- `inner-r` — radius of the valley vertices.

## Example

{{example: star-basic}}

<!-- example-source: star-basic -->
```clojure
(register star-prism (extrude (star 5 15 7) (f 8)))
```
<!-- /example-source -->

A 5-pointed star with tips at radius 15 and valleys at radius 7.

## Variations

{{example: star-spiky}}

<!-- example-source: star-spiky -->
```clojure
(register spiky (extrude (star 12 20 16) (f 4)))
```
<!-- /example-source -->

Tweaking the ratio between `outer-r` and `inner-r` controls how spiky the
star looks: closer values produce a gear-like outline, larger gaps produce
sharp points.

## Notes

- The total vertex count is `2 * n-points`.

## See also

- **Related:** `polygon`, `circle`, `fillet-shape`
