---
name: shell
category: generative-operations
since: ""
status: stable
---

# shell

## Signature

`(shell shape-or-fn & {:keys [thickness style invert? cap-top cap-bottom fn threshold] :as options})`

## Description

Shape-fn that turns a profile into a hollow tube with walls of a given
thickness, optionally perforated with a pattern and closed at the ends.
Used with `loft` (caps work only there) or `revolve`. Does not modify
turtle state.

`shell` has many options, so its documentation is split over four pages.
This one covers what you need for a plain hollow tube and points to the
others:

| To… | See |
|---|---|
| make a plain hollow tube | this page |
| perforate the wall with a pattern — `:voronoi`, `:lattice`, `:checkerboard`, `:pattern` — and tune it | [shell-styles](shell-styles.md) |
| close one or both ends, solid or patterned | [shell-caps](shell-caps.md) |
| design your own wall pattern with a function | [shell-custom-fn](shell-custom-fn.md) |

All options are written at the same level, one after the other — there is
no nested map, except for a patterned cap:
`(shell (circle 20) :thickness 2 :style :voronoi :cells 8 :cap-bottom 2)`.

For a true over/under woven surface, use `woven-shell`: a shell can only
vary the wall's thickness, not shift threads in and out.

## Parameters

| Parameter | Default | Description |
|---|---|---|
| `shape-or-fn` | — | Base profile (a shape or a shape-fn). |
| `:thickness` | `2` | Wall thickness. The wall is centred on the profile: half grows outward, half inward. |
| `:style` | `:solid` | Wall pattern: `:solid`, `:voronoi`, `:lattice`, `:checkerboard`, `:pattern`. Each has its own options — see [shell-styles](shell-styles.md). |
| `:invert?` | `false` | Swap wall and openings: bricks become brick-shaped holes, a voronoi wireframe becomes solid cells. Works with every style and with `:fn`. |
| `:cap-top` | — | Close the **arrival** end (where the path finishes): a number for a solid cap, or a map for a patterned one — see [shell-caps](shell-caps.md). |
| `:cap-bottom` | — | Same, for the **start** end (where the turtle stands when the loft begins). |
| `:fn` | — | Your own wall pattern, replacing `:style` — see [shell-custom-fn](shell-custom-fn.md). |
| `:threshold` | `0.05` | Wall values below this become openings — see [shell-custom-fn](shell-custom-fn.md). |

## Example

{{example: shell-solid}}

<!-- example-source: shell-solid -->
```clojure
(register tube (loft (shell (circle 20 64) :thickness 2) (f 50)))
```
<!-- /example-source -->

A plain hollow cylinder with a 2 mm wall — the simplest shell.

## Variations

{{example: shell-cup}}

<!-- example-source: shell-cup -->
```clojure
(register cup (loft (shell (circle 20 64) :thickness 2 :cap-bottom 3) (f 40)))
```
<!-- /example-source -->

A cup: the same tube closed at the start with a 3 mm solid bottom.

{{example: shell-voronoi-intro}}

<!-- example-source: shell-voronoi-intro -->
```clojure
(register voro-tube
  (loft (shell (circle 20 64) :thickness 2 :style :voronoi :cells 8 :rows 6)
        (f 60)))
```
<!-- /example-source -->

A perforated wall. The other patterns and their options are in
[shell-styles](shell-styles.md). This is a quick preview: for clean
openings raise the resolution of the profile and of the loft (e.g.
`(circle 20 512)` and `loft-n 512`) — see [shell-styles](shell-styles.md).

## Notes

- Where the wall value is 0 (an opening), the outer and inner surfaces
  meet on the profile: the hole goes through.
- Shape-fns compose:
  `(-> (circle 20 64) (shell :thickness 2 :style :voronoi) (tapered :to 0.5))`.
- `joint-mode` does not affect shell walls.
- **Curved rails:** patterned shells work best on straight or smoothly
  curved paths. An `arc-h`/`arc-v` rail is a chain of small corners, so the
  openings come out faceted along the curve (the mesh stays watertight).
  For a clean curved patterned wall, sweep along a bezier (`bezier-to`) or
  raise the resolution.

## See also

- **Shell pages:** [shell-styles](shell-styles.md), [shell-caps](shell-caps.md), [shell-custom-fn](shell-custom-fn.md)
- **Related:** `woven-shell`, `voronoi-shell`, `loft`, `capped`, `shape-fn`
